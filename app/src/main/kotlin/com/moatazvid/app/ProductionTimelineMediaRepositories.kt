package com.moatazvid.app

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.moatazvid.core.SourceId
import com.moatazvid.core.TimeRangeUs
import com.moatazvid.editor.ThumbnailRepository
import com.moatazvid.editor.WaveformRepository
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local-only filmstrip generation for timeline/decision-point inspection. */
class ProductionThumbnailRepository(
    context: Context,
    private val repository: ProductionProjectRepository,
) : ThumbnailRepository {
    private val appContext = context.applicationContext
    private val root = File(appContext.cacheDir, "timeline-thumbnails").apply { mkdirs() }

    override suspend fun visibleThumbnails(
        sourceId: SourceId,
        sourceRange: TimeRangeUs,
        pixelWidth: Int,
    ): List<String> = withContext(Dispatchers.IO) {
        val uri = repository.sourceUri(sourceId) ?: return@withContext emptyList()
        val count = ((pixelWidth.coerceAtLeast(1) + 119) / 120).coerceIn(1, 12)
        val key = sha256("${sourceId.value}:${sourceRange.start.value}:${sourceRange.endExclusive.value}:$count")
        val directory = File(root, key).apply { mkdirs() }
        val expected = (0 until count).map { File(directory, "frame-${it.toString().padStart(2, '0')}.jpg") }
        if (expected.all { it.isFile && it.length() > 0 }) return@withContext expected.map { it.absolutePath }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            expected.forEachIndexed { index, output ->
                if (output.isFile && output.length() > 0) return@forEachIndexed
                val fraction = (index + 0.5) / count.toDouble()
                val timeUs = sourceRange.start.value + (sourceRange.duration.value * fraction).toLong()
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@forEachIndexed
                val scaled = scaleDown(bitmap, THUMBNAIL_WIDTH_PX)
                FileOutputStream(output).use { stream -> scaled.compress(Bitmap.CompressFormat.JPEG, 78, stream) }
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
            expected.filter { it.isFile && it.length() > 0 }.map { it.absolutePath }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width <= targetWidth || bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        val height = (bitmap.height * targetWidth.toDouble() / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, height, true)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object { private const val THUMBNAIL_WIDTH_PX = 240 }
}

/**
 * Decodes audio once per source to a bounded peak envelope. Raw PCM is never retained, keeping the
 * timeline responsive even for long recordings while still providing real waveform data.
 */
class ProductionWaveformRepository(
    context: Context,
    private val repository: ProductionProjectRepository,
) : WaveformRepository {
    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, GlobalWaveform>()

    override suspend fun visibleWaveform(
        sourceId: SourceId,
        sourceRange: TimeRangeUs,
        pixelWidth: Int,
    ): FloatArray {
        val global = cache[sourceId.value] ?: withContext(Dispatchers.IO) { decode(sourceId) }?.also {
            cache[sourceId.value] = it
        } ?: return FloatArray(0)
        return slice(global, sourceRange, (pixelWidth / 3).coerceIn(24, 512))
    }

    private fun slice(global: GlobalWaveform, range: TimeRangeUs, targetBins: Int): FloatArray {
        if (global.durationUs <= 0 || global.peaks.isEmpty() || range.duration.value <= 0) return FloatArray(0)
        val start = ((range.start.value.coerceAtLeast(0) * global.peaks.size) / global.durationUs).toInt().coerceIn(0, global.peaks.lastIndex)
        val endExclusive = (((range.endExclusive.value.coerceAtMost(global.durationUs) * global.peaks.size) + global.durationUs - 1) / global.durationUs)
            .toInt().coerceIn(start + 1, global.peaks.size)
        val output = FloatArray(targetBins)
        for (outIndex in output.indices) {
            val from = start + ((endExclusive - start) * outIndex / targetBins)
            val to = start + ((endExclusive - start) * (outIndex + 1) / targetBins)
            var peak = 0f
            for (index in from until maxOf(from + 1, to).coerceAtMost(endExclusive)) peak = maxOf(peak, global.peaks[index])
            output[outIndex] = peak
        }
        return output
    }

    private suspend fun decode(sourceId: SourceId): GlobalWaveform? {
        val uri = repository.sourceUri(sourceId) ?: return null
        val durationUs = repository.database.mediaDao().source(sourceId.value)?.durationUs?.takeIf { it > 0 } ?: return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(appContext, uri, null)
            var trackIndex = -1
            var sourceFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = index
                    sourceFormat = candidate
                    break
                }
            }
            if (trackIndex < 0 || sourceFormat == null) return null
            extractor.selectTrack(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            val peaks = FloatArray(GLOBAL_BINS)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            var channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Missing waveform decoder input")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.order(ByteOrder.nativeOrder())
                            val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frameCount = info.size / bytesPerSample / channels
                            for (frame in 0 until frameCount) {
                                var amplitude = 0f
                                repeat(channels) {
                                    val value = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) output.float else output.short / 32768f
                                    amplitude = maxOf(amplitude, value.absoluteValue.coerceIn(0f, 1f))
                                }
                                val timeUs = info.presentationTimeUs + frame.toLong() * 1_000_000L / sampleRate
                                val bucket = ((timeUs.coerceIn(0, durationUs - 1) * GLOBAL_BINS) / durationUs).toInt().coerceIn(0, GLOBAL_BINS - 1)
                                peaks[bucket] = maxOf(peaks[bucket], amplitude)
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            GlobalWaveform(durationUs, peaks)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private data class GlobalWaveform(val durationUs: Long, val peaks: FloatArray)

    companion object { private const val GLOBAL_BINS = 4096 }
}
