package com.moatazvid.videouse

import com.moatazvid.core.DurationUs
import com.moatazvid.core.SourceId
import com.moatazvid.core.TimeRangeUs
import com.moatazvid.core.TimeUs
import com.moatazvid.speech.TranscriptWord
import com.moatazvid.speech.TranscriptWordId
import com.moatazvid.speech.TranscriptWordType
import kotlin.math.abs

/**
 * Android-native port of the production invariants and editorial primitives from browser-use/video-use.
 * The upstream Python helpers are not executed inside the APK; their load-bearing behavior is modeled here.
 */
object VideoUseOrigin {
    const val REPOSITORY = "https://github.com/browser-use/video-use"
    const val PINNED_REVISION = "9575612f066aa517354790a645fd90f9f95a743b"
}

enum class VideoUseHardRule {
    CAPTIONS_LAST,
    SINGLE_LOSSY_ENCODE,
    AUDIO_BOUNDARY_FADE,
    OVERLAY_LOCAL_TIME,
    OUTPUT_TIMELINE_CAPTIONS,
    NEVER_CUT_INSIDE_WORD,
    CUT_PADDING,
    WORD_LEVEL_ASR,
    TRANSCRIPT_CACHE,
    STRATEGY_CONFIRMATION,
    SESSION_OUTPUT_ISOLATION,
}

data class VideoUsePolicy(
    val phraseSilenceBreak: DurationUs = DurationUs(500_000),
    val minimumCutPadding: DurationUs = DurationUs(30_000),
    val maximumCutPadding: DurationUs = DurationUs(200_000),
    val leadingCutPadding: DurationUs = DurationUs(50_000),
    val trailingCutPadding: DurationUs = DurationUs(80_000),
    val boundaryAudioFade: DurationUs = DurationUs(30_000),
    val preferredSilenceCut: DurationUs = DurationUs(400_000),
    val maxSelfEvaluationPasses: Int = 3,
) {
    init {
        require(phraseSilenceBreak.value >= 0)
        require(minimumCutPadding.value in 0..maximumCutPadding.value)
        require(leadingCutPadding.value in minimumCutPadding.value..maximumCutPadding.value)
        require(trailingCutPadding.value in minimumCutPadding.value..maximumCutPadding.value)
        require(boundaryAudioFade.value > 0)
        require(maxSelfEvaluationPasses in 1..3)
    }

    companion object { val PRODUCTION = VideoUsePolicy() }
}

data class VideoUsePackedPhrase(
    val sourceId: SourceId,
    val range: TimeRangeUs,
    val speakerId: String?,
    val text: String,
    val wordIds: List<TranscriptWordId>,
)

/** Phrase packing compatible with video-use: break on silence >= 0.5s, source change, or speaker change. */
class VideoUsePackedTranscriptBuilder(
    private val policy: VideoUsePolicy = VideoUsePolicy.PRODUCTION,
) {
    fun build(words: List<TranscriptWord>): List<VideoUsePackedPhrase> {
        val ordered = words
            .filter { it.type != TranscriptWordType.OTHER && it.text.isNotBlank() }
            .sortedWith(compareBy<TranscriptWord>({ it.sourceId.value }, { it.sourceRange.start.value }, { it.index }))
        if (ordered.isEmpty()) return emptyList()

        val result = mutableListOf<VideoUsePackedPhrase>()
        var bucket = mutableListOf<TranscriptWord>()

        fun flush() {
            if (bucket.isEmpty()) return
            result += VideoUsePackedPhrase(
                sourceId = bucket.first().sourceId,
                range = TimeRangeUs(bucket.first().sourceRange.start, bucket.last().sourceRange.endExclusive),
                speakerId = bucket.firstNotNullOfOrNull { it.speakerId },
                text = render(bucket),
                wordIds = bucket.map { it.id },
            )
            bucket = mutableListOf()
        }

        ordered.forEach { word ->
            val previous = bucket.lastOrNull()
            val breakBefore = previous != null && (
                word.sourceId != previous.sourceId ||
                    (previous.speakerId != null && word.speakerId != null && previous.speakerId != word.speakerId) ||
                    word.sourceRange.start.value - previous.sourceRange.endExclusive.value >= policy.phraseSilenceBreak.value
                )
            if (breakBefore) flush()
            bucket += word
        }
        flush()
        return result
    }

    fun render(phrases: List<VideoUsePackedPhrase>): String = phrases.joinToString("\n") { phrase ->
        val speaker = phrase.speakerId?.removePrefix("speaker_")?.let { " S$it" }.orEmpty()
        val start = phrase.range.start.value / 1_000_000.0
        val end = phrase.range.endExclusive.value / 1_000_000.0
        "[${"%06.2f".format(start)}-${"%06.2f".format(end)}]$speaker ${phrase.text}"
    }

    private fun render(words: List<TranscriptWord>): String = buildString {
        words.forEach { word ->
            val punctuation = word.type == TranscriptWordType.PUNCTUATION
            if (isNotEmpty() && !punctuation) append(' ')
            if (word.type == TranscriptWordType.AUDIO_EVENT && !word.text.startsWith("(")) append('(')
            append(word.text.trim())
            if (word.type == TranscriptWordType.AUDIO_EVENT && !word.text.endsWith(")")) append(')')
        }
    }.replace(" ,", ",").replace(" .", ".").replace(" ?", "?").replace(" !", "!").trim()
}

data class VideoUseEdlSegment(
    val sourceId: SourceId,
    val sourceRange: TimeRangeUs,
    val beat: String? = null,
    val quote: String? = null,
    val reason: String? = null,
)

data class VideoUseMappedWord(
    val wordId: TranscriptWordId,
    val sourceId: SourceId,
    val outputRange: TimeRangeUs,
    val text: String,
)

/** Implements video-use output timeline offsets for subtitles after concatenation/re-ordering. */
object VideoUseOutputTimelineMapper {
    fun mapWords(segments: List<VideoUseEdlSegment>, words: List<TranscriptWord>): List<VideoUseMappedWord> {
        var outputOffset = 0L
        val result = mutableListOf<VideoUseMappedWord>()
        segments.forEach { segment ->
            words.asSequence()
                .filter { it.type == TranscriptWordType.WORD && it.sourceId == segment.sourceId && it.sourceRange.overlaps(segment.sourceRange) }
                .sortedBy { it.sourceRange.start.value }
                .forEach { word ->
                    val localStart = maxOf(segment.sourceRange.start.value, word.sourceRange.start.value) - segment.sourceRange.start.value
                    val localEnd = minOf(segment.sourceRange.endExclusive.value, word.sourceRange.endExclusive.value) - segment.sourceRange.start.value
                    if (localEnd > localStart) {
                        result += VideoUseMappedWord(
                            word.id,
                            word.sourceId,
                            TimeRangeUs(TimeUs(outputOffset + localStart), TimeUs(outputOffset + localEnd)),
                            word.text,
                        )
                    }
                }
            outputOffset += segment.sourceRange.duration.value
        }
        return result
    }
}

data class VideoUseCutValidation(
    val valid: Boolean,
    val issues: List<String>,
)

class VideoUseCutRules(
    private val policy: VideoUsePolicy = VideoUsePolicy.PRODUCTION,
) {
    fun snapOutward(
        sourceId: SourceId,
        requested: TimeRangeUs,
        words: List<TranscriptWord>,
        sourceDuration: DurationUs? = null,
    ): TimeRangeUs {
        val spoken = words.filter { it.type == TranscriptWordType.WORD && it.sourceId == sourceId }.sortedBy { it.sourceRange.start.value }
        if (spoken.isEmpty()) return requested
        val first = spoken.firstOrNull { it.sourceRange.endExclusive.value > requested.start.value } ?: return requested
        val last = spoken.lastOrNull { it.sourceRange.start.value < requested.endExclusive.value } ?: return requested
        var start = (first.sourceRange.start.value - policy.leadingCutPadding.value).coerceAtLeast(0L)
        var end = last.sourceRange.endExclusive.value + policy.trailingCutPadding.value
        sourceDuration?.let { end = end.coerceAtMost(it.value) }
        start = moveOutOfWord(start, spoken, towardStart = true)
        end = moveOutOfWord(end, spoken, towardStart = false)
        if (end <= start) return requested
        return TimeRangeUs(TimeUs(start), TimeUs(end))
    }

    fun validate(sourceId: SourceId, range: TimeRangeUs, words: List<TranscriptWord>): VideoUseCutValidation {
        val spoken = words.filter { it.type == TranscriptWordType.WORD && it.sourceId == sourceId }
        val issues = buildList {
            spoken.firstOrNull { range.start.value > it.sourceRange.start.value && range.start.value < it.sourceRange.endExclusive.value }?.let {
                add("start-inside-word:${it.id.value}")
            }
            spoken.firstOrNull { range.endExclusive.value > it.sourceRange.start.value && range.endExclusive.value < it.sourceRange.endExclusive.value }?.let {
                add("end-inside-word:${it.id.value}")
            }
        }
        return VideoUseCutValidation(issues.isEmpty(), issues)
    }

    fun nearestBoundary(sourceId: SourceId, time: TimeUs, words: List<TranscriptWord>, tolerance: DurationUs = DurationUs(200_000)): TimeUs? {
        val candidates = words.filter { it.type == TranscriptWordType.WORD && it.sourceId == sourceId }
            .flatMap { listOf(it.sourceRange.start, it.sourceRange.endExclusive) }
        return candidates.minByOrNull { abs(it.value - time.value) }?.takeIf { abs(it.value - time.value) <= tolerance.value }
    }

    private fun moveOutOfWord(value: Long, words: List<TranscriptWord>, towardStart: Boolean): Long {
        val containing = words.firstOrNull { value > it.sourceRange.start.value && value < it.sourceRange.endExclusive.value } ?: return value
        return if (towardStart) containing.sourceRange.start.value else containing.sourceRange.endExclusive.value
    }
}

enum class VideoUseSessionPhase { INVENTORY, CONVERSATION, STRATEGY_READY, STRATEGY_CONFIRMED, PLAN_READY, PREVIEW, SELF_EVALUATION, FINAL }

data class VideoUseStrategy(
    val id: String,
    val projectRevision: Long,
    val userInstruction: String,
    val summary: String,
    val createdAtEpochMs: Long,
)

data class VideoUseSelfEvaluationPolicy(
    val boundaryWindow: DurationUs = DurationUs(1_500_000),
    val sampleMidpoints: Int = 3,
    val maxPasses: Int = VideoUsePolicy.PRODUCTION.maxSelfEvaluationPasses,
) {
    init { require(boundaryWindow.value > 0); require(sampleMidpoints in 0..5); require(maxPasses in 1..3) }
}
