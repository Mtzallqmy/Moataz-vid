package com.moatazvid.media.media3

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.moatazvid.core.DurationUs
import com.moatazvid.core.Rational
import com.moatazvid.media.OutputInspector
import com.moatazvid.media.OutputVerification
import java.io.File
import kotlin.math.roundToInt

/** Container/stream-level post-export verification for app files, content:// and SAF destinations. */
class AndroidOutputInspector(private val context: Context) : OutputInspector {
    override suspend fun inspect(uri: String): OutputVerification {
        val parsed = Uri.parse(uri)
        val issues = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        return try {
            if (parsed.scheme.isNullOrBlank()) {
                retriever.setDataSource(uri)
                extractor.setDataSource(uri)
            } else {
                retriever.setDataSource(context, parsed)
                extractor.setDataSource(context, parsed, null)
            }
            val containerDurationMs = retriever.string(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.string(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.string(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            var videoMime: String? = null
            var audioMime: String? = null
            var videoDurationUs: Long? = null
            var audioDurationUs: Long? = null
            var trackFps: Double? = null
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.string(MediaFormat.KEY_MIME)
                when {
                    mime?.startsWith("video/") == true -> {
                        videoMime = videoMime ?: mime
                        videoDurationUs = maxNullable(videoDurationUs, format.longOrNull(MediaFormat.KEY_DURATION))
                        trackFps = trackFps ?: format.intOrNull(MediaFormat.KEY_FRAME_RATE)?.toDouble()
                    }
                    mime?.startsWith("audio/") == true -> {
                        audioMime = audioMime ?: mime
                        audioDurationUs = maxNullable(audioDurationUs, format.longOrNull(MediaFormat.KEY_DURATION))
                    }
                }
            }
            val hasVideo = videoMime != null || retriever.string(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO).equals("yes", ignoreCase = true) || (width ?: 0) > 0
            val hasAudio = audioMime != null || retriever.string(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO).equals("yes", ignoreCase = true)
            val captureFps = retriever.string(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            val frameRate = (captureFps ?: trackFps)?.takeIf { it > 0.0 }?.let(::rationalApproximation)
            val size = runCatching {
                if (parsed.scheme.isNullOrBlank()) File(uri).length()
                else context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { it.length.coerceAtLeast(0) } ?: 0L
            }.getOrElse { 0L }
            val durationUs = videoDurationUs ?: containerDurationMs?.times(1_000)
            val avDrift = if (videoDurationUs != null && audioDurationUs != null) videoDurationUs!! - audioDurationUs!! else null
            if ((durationUs ?: 0L) <= 0L) issues += "UNREADABLE_DURATION"
            if (!hasVideo) issues += "VIDEO_STREAM_MISSING"
            if (size <= 0) issues += "EMPTY_OUTPUT"
            OutputVerification(
                valid = issues.isEmpty(),
                sizeBytes = size,
                duration = durationUs?.takeIf { it > 0 }?.let(::DurationUs),
                width = width,
                height = height,
                frameRate = frameRate,
                videoCodec = videoMime,
                audioCodec = audioMime,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                avDriftUs = avDrift,
                issues = issues,
            )
        } catch (failure: Throwable) {
            OutputVerification(
                valid = false,
                sizeBytes = 0,
                duration = null,
                width = null,
                height = null,
                frameRate = null,
                videoCodec = null,
                audioCodec = null,
                hasVideo = false,
                hasAudio = false,
                avDriftUs = null,
                issues = listOf("OUTPUT_INSPECTION_FAILED:${failure.javaClass.simpleName}"),
            )
        } finally {
            runCatching { extractor.release() }
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.string(key: Int): String? = extractMetadata(key)?.takeIf { it.isNotBlank() }
    private fun MediaFormat.string(key: String): String? = if (containsKey(key)) getString(key) else null
    private fun MediaFormat.longOrNull(key: String): Long? = runCatching { if (containsKey(key)) getLong(key) else null }.getOrNull()
    private fun MediaFormat.intOrNull(key: String): Int? = runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()
    private fun maxNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private fun rationalApproximation(value: Double): Rational {
        val known = listOf(Rational.FPS_23_976, Rational.FPS_24, Rational.FPS_25, Rational.FPS_29_97, Rational.FPS_30, Rational.FPS_50, Rational.FPS_59_94, Rational.FPS_60)
        known.minByOrNull { kotlin.math.abs(it.asDouble() - value) }?.takeIf { kotlin.math.abs(it.asDouble() - value) < 0.05 }?.let { return it }
        return Rational(value.roundToInt().coerceAtLeast(1), 1)
    }
}
