package com.moatazvid.media.media3

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.moatazvid.core.DurationUs
import com.moatazvid.core.Rational
import com.moatazvid.media.OutputInspector
import com.moatazvid.media.OutputVerification

/** Container-level post-export verification for content://, file:// and SAF destinations. */
class AndroidOutputInspector(private val context: Context) : OutputInspector {
    override suspend fun inspect(uri: String): OutputVerification {
        val parsed = Uri.parse(uri)
        val issues = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        return try {
            if (parsed.scheme.isNullOrBlank()) retriever.setDataSource(uri) else retriever.setDataSource(context, parsed)
            val durationMs = retriever.string(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.string(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.string(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val mime = retriever.string(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val hasVideo = retriever.string(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO).equals("yes", ignoreCase = true) || (width ?: 0) > 0
            val hasAudio = retriever.string(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO).equals("yes", ignoreCase = true)
            val captureFps = retriever.string(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            val frameRate = captureFps?.takeIf { it > 0.0 }?.let(::rationalApproximation)
            val size = runCatching {
                if (parsed.scheme.isNullOrBlank()) java.io.File(uri).length()
                else context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { it.length.coerceAtLeast(0) } ?: 0L
            }.getOrElse { 0L }
            if (durationMs == null || durationMs <= 0) issues += "UNREADABLE_DURATION"
            if (!hasVideo) issues += "VIDEO_STREAM_MISSING"
            if (size <= 0) issues += "EMPTY_OUTPUT"
            OutputVerification(
                valid = issues.isEmpty(),
                sizeBytes = size,
                duration = durationMs?.takeIf { it > 0 }?.let { DurationUs(it * 1_000) },
                width = width,
                height = height,
                frameRate = frameRate,
                videoCodec = mime,
                audioCodec = if (hasAudio) "present" else null,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                avDriftUs = null,
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
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.string(key: Int): String? = extractMetadata(key)?.takeIf { it.isNotBlank() }

    private fun rationalApproximation(value: Double): Rational {
        val ntsc = listOf(Rational.FPS_23_976, Rational.FPS_29_97, Rational.FPS_59_94)
        ntsc.minByOrNull { kotlin.math.abs(it.asDouble() - value) }?.takeIf { kotlin.math.abs(it.asDouble() - value) < 0.02 }?.let { return it }
        return Rational(value.toInt().coerceAtLeast(1), 1)
    }
}
