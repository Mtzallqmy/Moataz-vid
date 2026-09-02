package com.moatazvid.media.media3

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.moatazvid.core.TimeUs
import com.moatazvid.videouse.VideoUseEvaluationIssue
import com.moatazvid.videouse.VideoUseEvaluationSeverity
import com.moatazvid.videouse.VideoUseSelfEvaluationReport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Post-render decode audit inspired by video-use's self-evaluation pass. It samples representative
 * frames and both sides of every edit boundary. Structural render invariants are checked before the
 * encode by [VideoUseMedia3Policy]; this pass confirms that the resulting file remains decodable at
 * the exact points most likely to expose broken cuts or timestamp discontinuities.
 */
class AndroidVideoUseSelfEvaluator(private val context: Context) {
    suspend fun evaluate(
        uri: String,
        expectedDurationUs: Long,
        cutTimesUs: List<Long>,
        pass: Int = 1,
    ): VideoUseSelfEvaluationReport = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val issues = mutableListOf<VideoUseEvaluationIssue>()
        var representativeChecked = 0
        var boundariesChecked = 0
        try {
            setSource(retriever, uri)
            val actualDurationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1_000)
                ?: expectedDurationUs
            val duration = minOf(expectedDurationUs.takeIf { it > 0 } ?: actualDurationUs, actualDurationUs).coerceAtLeast(0)
            if (duration <= 0) {
                issues += VideoUseEvaluationIssue("SELF_EVAL_NO_DURATION", VideoUseEvaluationSeverity.ERROR, "Rendered duration could not be read")
                return@withContext VideoUseSelfEvaluationReport(pass, 0, 0, issues)
            }

            representativeTimes(duration).forEach { timeUs ->
                val frame = runCatching { retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
                if (frame == null) {
                    issues += VideoUseEvaluationIssue(
                        "REPRESENTATIVE_FRAME_UNREADABLE",
                        VideoUseEvaluationSeverity.ERROR,
                        "Could not decode a representative frame at ${timeUs}us",
                        TimeUs(timeUs),
                    )
                } else {
                    representativeChecked++
                    frame.recycle()
                }
            }

            cutTimesUs.asSequence()
                .filter { it > 0 && it < duration }
                .distinct()
                .sorted()
                .forEach { cutUs ->
                    val before = (cutUs - BOUNDARY_SAMPLE_OFFSET_US).coerceAtLeast(0)
                    val after = (cutUs + BOUNDARY_SAMPLE_OFFSET_US).coerceAtMost(duration - 1)
                    val beforeFrame = runCatching { retriever.getFrameAtTime(before, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
                    val afterFrame = runCatching { retriever.getFrameAtTime(after, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
                    if (beforeFrame == null || afterFrame == null) {
                        issues += VideoUseEvaluationIssue(
                            "CUT_BOUNDARY_FRAME_UNREADABLE",
                            VideoUseEvaluationSeverity.ERROR,
                            "Could not decode both sides of the cut boundary at ${cutUs}us",
                            TimeUs(cutUs),
                        )
                    } else {
                        boundariesChecked++
                    }
                    beforeFrame?.recycle()
                    afterFrame?.recycle()
                }
        } catch (failure: Throwable) {
            issues += VideoUseEvaluationIssue(
                "SELF_EVAL_FAILED",
                VideoUseEvaluationSeverity.ERROR,
                failure.message ?: failure.javaClass.simpleName,
            )
        } finally {
            runCatching { retriever.release() }
        }
        VideoUseSelfEvaluationReport(pass, representativeChecked, boundariesChecked, issues)
    }

    private fun setSource(retriever: MediaMetadataRetriever, value: String) {
        val uri = Uri.parse(value)
        if (uri.scheme.isNullOrBlank() || uri.scheme == "file") {
            retriever.setDataSource(if (uri.scheme == "file") requireNotNull(uri.path) else value)
        } else {
            retriever.setDataSource(context, uri)
        }
    }

    private fun representativeTimes(durationUs: Long): List<Long> = listOf(
        minOf(100_000L, durationUs - 1),
        durationUs / 4,
        durationUs / 2,
        durationUs * 3 / 4,
        (durationUs - 100_000L).coerceAtLeast(0),
    ).map { it.coerceIn(0, durationUs - 1) }.distinct()

    companion object {
        private const val BOUNDARY_SAMPLE_OFFSET_US = 100_000L
    }
}
