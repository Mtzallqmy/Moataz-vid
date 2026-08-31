package com.moatazvid.speech

import com.moatazvid.core.*

data class TranscriptTimelineSpan(
    val clipId: ClipId,
    val sourceId: SourceId,
    val sourceRange: TimeRangeUs,
    val timelineRange: TimeRangeUs,
    val wordIds: List<TranscriptWordId>,
    val approximate: Boolean,
)

class TimelineTranscriptMapper {
    fun map(item: TimelineItem, words: List<TranscriptWord>, constantSpeed: Double = 1.0): List<TranscriptTimelineSpan> {
        require(constantSpeed > 0.0)
        val sourceId = item.sourceId ?: return emptyList()
        val sourceRange = item.sourceRange ?: return emptyList()
        return words.filter { it.sourceId == sourceId && it.sourceRange.overlaps(sourceRange) }.map { word ->
            val clippedStart = maxOf(word.sourceRange.start.value, sourceRange.start.value)
            val clippedEnd = minOf(word.sourceRange.endExclusive.value, sourceRange.endExclusive.value)
            val timelineStart = item.timelineStart.value + ((clippedStart - sourceRange.start.value) / constantSpeed).toLong()
            val timelineEnd = item.timelineStart.value + ((clippedEnd - sourceRange.start.value) / constantSpeed).toLong()
            TranscriptTimelineSpan(item.id, sourceId, TimeRangeUs(TimeUs(clippedStart), TimeUs(clippedEnd)),
                TimeRangeUs(TimeUs(timelineStart), TimeUs(timelineEnd)), listOf(word.id), approximate = false)
        }
    }
}

enum class CaptionChunkMode { FIXED_WORDS, SENTENCE, PAUSE }
data class CaptionDraftPolicy(
    val mode: CaptionChunkMode = CaptionChunkMode.FIXED_WORDS,
    val wordsPerChunk: Int = 2,
    val pauseBreak: DurationUs = DurationUs(450_000),
    val minimumDuration: DurationUs = DurationUs(350_000),
    val maximumDuration: DurationUs = DurationUs(4_000_000),
)
data class CaptionDraft(val id: String, val sourceId: SourceId, val sourceRange: TimeRangeUs, val text: String, val wordIds: List<TranscriptWordId>)

class CaptionDraftGenerator {
    fun generate(words: List<TranscriptWord>, policy: CaptionDraftPolicy): List<CaptionDraft> {
        require(policy.wordsPerChunk in 1..12)
        val chunks = mutableListOf<List<TranscriptWord>>()
        var current = mutableListOf<TranscriptWord>()
        fun flush() { if (current.isNotEmpty()) chunks += current.toList(); current = mutableListOf() }
        words.forEach { word ->
            if (current.isNotEmpty() && (word.sourceId != current.first().sourceId ||
                    word.sourceRange.start.value - current.last().sourceRange.endExclusive.value >= policy.pauseBreak.value ||
                    word.sourceRange.endExclusive.value - current.first().sourceRange.start.value > policy.maximumDuration.value)) flush()
            current += word
            val sentenceEnd = word.type == TranscriptWordType.PUNCTUATION && word.text.any { it in ".!?؟" }
            if ((policy.mode == CaptionChunkMode.FIXED_WORDS && current.count { it.type == TranscriptWordType.WORD } >= policy.wordsPerChunk) ||
                (policy.mode == CaptionChunkMode.SENTENCE && sentenceEnd)) flush()
        }
        flush()
        return chunks.mapIndexed { index, chunk ->
            val start = chunk.first().sourceRange.start
            val naturalEnd = chunk.last().sourceRange.endExclusive
            val end = TimeUs(maxOf(naturalEnd.value, start.value + policy.minimumDuration.value))
            CaptionDraft("caption_${chunk.first().sourceId.value}_$index", chunk.first().sourceId, TimeRangeUs(start, end),
                buildString { chunk.forEach { if (isNotEmpty() && it.type != TranscriptWordType.PUNCTUATION) append(' '); append(it.text) } }, chunk.map { it.id })
        }
    }
}
