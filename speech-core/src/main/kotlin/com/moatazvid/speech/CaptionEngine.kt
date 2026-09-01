package com.moatazvid.speech

import com.moatazvid.core.*

/** Grouping policy for generated caption cues. Raw transcript text remains immutable. */
enum class CaptionGroupingMode { ONE_WORD, TWO_WORDS, FIXED_WORD_COUNT, SENTENCE, PAUSE_BASED, SMART_SOCIAL, CUSTOM }
enum class CaptionCasingPolicy { PRESERVE, UPPERCASE_LATIN, LOWERCASE_LATIN }
enum class CaptionAnimationPreset { NONE, FADE, SCALE_IN, POP, SLIDE_UP, WORD_HIGHLIGHT }
enum class CaptionAnchor { TOP, CENTER, BOTTOM, CUSTOM }

data class CaptionPolicy(
    val mode: CaptionGroupingMode = CaptionGroupingMode.SMART_SOCIAL,
    val maxWords: Int = 4,
    val maxCharacters: Int = 42,
    val maxDurationMs: Long = 4_000,
    val minDurationMs: Long = 350,
    val breakOnPunctuation: Boolean = true,
    val breakOnSilenceMs: Long = 500,
    val preservePunctuation: Boolean = true,
    val casing: CaptionCasingPolicy = CaptionCasingPolicy.PRESERVE,
    val language: LanguageCode = LanguageCode.AUTO,
) {
    init {
        require(maxWords in 1..16)
        require(maxCharacters in 4..160)
        require(maxDurationMs in 250..15_000)
        require(minDurationMs in 100..maxDurationMs)
        require(breakOnSilenceMs in 50..10_000)
    }

    fun targetWordCount(): Int = when (mode) {
        CaptionGroupingMode.ONE_WORD -> 1
        CaptionGroupingMode.TWO_WORDS -> 2
        CaptionGroupingMode.FIXED_WORD_COUNT, CaptionGroupingMode.CUSTOM -> maxWords
        CaptionGroupingMode.SMART_SOCIAL -> maxWords.coerceAtMost(4)
        CaptionGroupingMode.SENTENCE, CaptionGroupingMode.PAUSE_BASED -> maxWords
    }
}

data class CaptionWordTiming(
    val wordId: TranscriptWordId,
    val sourceId: SourceId,
    val sourceRange: TimeRangeUs,
    val text: String,
)

data class CaptionCue(
    val id: String,
    val trackId: TrackId,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val wordRefs: List<TranscriptWordId>,
    val styleId: String,
    val positionOverride: CaptionAnchor? = null,
    val animationPreset: CaptionAnimationPreset = CaptionAnimationPreset.NONE,
    val wordTimings: List<CaptionWordTiming> = emptyList(),
    val rightToLeft: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(endMs > startMs)
        require(text.isNotBlank())
        require(wordRefs.isNotEmpty())
    }
}

/** Maps a source-time cue after trim/reorder/speed changes without modifying the transcript. */
class CaptionMapper {
    fun mapToTimeline(cue: CaptionCue, words: List<TranscriptWord>, items: List<TimelineItem>, constantSpeeds: Map<ClipId, Double> = emptyMap()): List<CaptionCue> {
        if (cue.wordRefs.isEmpty()) return emptyList()
        val byId = words.associateBy { it.id }
        val referenced = cue.wordRefs.mapNotNull(byId::get)
        if (referenced.isEmpty()) return emptyList()
        return items.mapNotNull { item ->
            val sourceId = item.sourceId ?: return@mapNotNull null
            val sourceRange = item.sourceRange ?: return@mapNotNull null
            val relevant = referenced.filter { it.sourceId == sourceId && it.sourceRange.overlaps(sourceRange) }
            if (relevant.isEmpty()) return@mapNotNull null
            val speed = constantSpeeds[item.id] ?: 1.0
            require(speed > 0.0)
            val first = relevant.first()
            val last = relevant.last()
            val clippedStart = maxOf(first.sourceRange.start.value, sourceRange.start.value)
            val clippedEnd = minOf(last.sourceRange.endExclusive.value, sourceRange.endExclusive.value)
            val timelineStartUs = item.timelineStart.value + ((clippedStart - sourceRange.start.value) / speed).toLong()
            val timelineEndUs = item.timelineStart.value + ((clippedEnd - sourceRange.start.value) / speed).toLong()
            cue.copy(
                id = "${cue.id}_${item.id.value}",
                startMs = timelineStartUs / 1_000,
                endMs = maxOf(timelineEndUs / 1_000, timelineStartUs / 1_000 + 1),
                wordRefs = relevant.map { it.id },
                wordTimings = relevant.map { CaptionWordTiming(it.id, it.sourceId, it.sourceRange, it.text) },
            )
        }
    }
}

class CaptionEngine {
    fun generate(
        words: List<TranscriptWord>,
        trackId: TrackId,
        styleId: String,
        policy: CaptionPolicy = CaptionPolicy(),
        animationPreset: CaptionAnimationPreset = CaptionAnimationPreset.NONE,
    ): List<CaptionCue> {
        if (words.isEmpty()) return emptyList()
        val sorted = words.sortedWith(compareBy<TranscriptWord> { it.sourceId.value }.thenBy { it.sourceRange.start.value }.thenBy { it.index })
        val chunks = mutableListOf<List<TranscriptWord>>()
        var current = mutableListOf<TranscriptWord>()
        fun flush() {
            if (current.any { it.type == TranscriptWordType.WORD }) chunks += current.toList()
            current = mutableListOf()
        }
        for (word in sorted) {
            if (current.isNotEmpty()) {
                val previous = current.last()
                val sourceChanged = previous.sourceId != word.sourceId
                val silenceBreak = word.sourceRange.start.value - previous.sourceRange.endExclusive.value >= policy.breakOnSilenceMs * 1_000
                val durationBreak = word.sourceRange.endExclusive.value - current.first().sourceRange.start.value > policy.maxDurationMs * 1_000
                if (sourceChanged || silenceBreak || durationBreak) flush()
            }
            current += word
            val wordCount = current.count { it.type == TranscriptWordType.WORD }
            val textLength = renderText(current, policy).length
            val sentenceEnd = word.type == TranscriptWordType.PUNCTUATION && word.text.any { it in ".!?؟؛" }
            val modeBreak = when (policy.mode) {
                CaptionGroupingMode.ONE_WORD, CaptionGroupingMode.TWO_WORDS, CaptionGroupingMode.FIXED_WORD_COUNT, CaptionGroupingMode.CUSTOM -> wordCount >= policy.targetWordCount()
                CaptionGroupingMode.SENTENCE -> sentenceEnd || wordCount >= policy.maxWords
                CaptionGroupingMode.PAUSE_BASED -> wordCount >= policy.maxWords
                CaptionGroupingMode.SMART_SOCIAL -> sentenceEnd || wordCount >= policy.targetWordCount()
            }
            if (modeBreak || textLength >= policy.maxCharacters || (policy.breakOnPunctuation && sentenceEnd)) flush()
        }
        flush()

        val result = mutableListOf<CaptionCue>()
        for ((index, chunk) in chunks.withIndex()) {
            val meaningful = chunk.filter { it.type == TranscriptWordType.WORD || (policy.preservePunctuation && it.type == TranscriptWordType.PUNCTUATION) }
            if (meaningful.isEmpty()) continue
            val first = meaningful.first()
            val last = meaningful.last()
            val naturalStartMs = first.sourceRange.start.value / 1_000
            val naturalEndMs = last.sourceRange.endExclusive.value / 1_000
            var startMs = naturalStartMs
            var endMs = maxOf(naturalEndMs, startMs + policy.minDurationMs)
            if (result.isNotEmpty() && result.last().wordTimings.lastOrNull()?.sourceId == first.sourceId && startMs < result.last().endMs) {
                startMs = result.last().endMs
                endMs = maxOf(endMs, startMs + 1)
            }
            endMs = minOf(endMs, naturalStartMs + policy.maxDurationMs)
            if (endMs <= startMs) endMs = startMs + 1
            val text = applyCasing(renderText(meaningful, policy), policy.casing)
            val rtl = when {
                policy.language != LanguageCode.AUTO -> policy.language.isRightToLeft
                else -> detectRtl(text)
            }
            result += CaptionCue(
                id = "caption_${first.sourceId.value}_${first.index}_$index",
                trackId = trackId,
                startMs = startMs,
                endMs = endMs,
                text = text,
                wordRefs = meaningful.filter { it.type == TranscriptWordType.WORD }.map { it.id },
                styleId = styleId,
                animationPreset = animationPreset,
                wordTimings = meaningful.filter { it.type == TranscriptWordType.WORD }.map { CaptionWordTiming(it.id, it.sourceId, it.sourceRange, it.text) },
                rightToLeft = rtl,
            )
        }
        return result
    }

    private fun renderText(words: List<TranscriptWord>, policy: CaptionPolicy): String = buildString {
        words.forEach { word ->
            if (word.type == TranscriptWordType.PUNCTUATION && !policy.preservePunctuation) return@forEach
            if (isNotEmpty() && word.type != TranscriptWordType.PUNCTUATION) append(' ')
            append(word.text)
        }
    }.trim()

    private fun applyCasing(text: String, policy: CaptionCasingPolicy): String = when (policy) {
        CaptionCasingPolicy.PRESERVE -> text
        CaptionCasingPolicy.UPPERCASE_LATIN -> text.map { if (it in 'a'..'z') it.uppercaseChar() else it }.joinToString("")
        CaptionCasingPolicy.LOWERCASE_LATIN -> text.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
    }

    private fun detectRtl(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        text.forEach { c ->
            when {
                c in '\u0590'..'\u08FF' || c in '\uFB1D'..'\uFDFF' || c in '\uFE70'..'\uFEFF' -> rtl++
                c in 'A'..'Z' || c in 'a'..'z' -> ltr++
            }
        }
        return rtl > ltr
    }
}
