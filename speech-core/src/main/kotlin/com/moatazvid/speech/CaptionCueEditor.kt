package com.moatazvid.speech

import com.moatazvid.core.TranscriptWordId

/**
 * Pure cue editor. It edits generated caption cues only and never writes back to Raw Transcript.
 * The caller persists the returned list through the normal project transaction/undo path.
 */
class CaptionCueEditor {
    fun updateText(cues: List<CaptionCue>, cueId: String, text: String): List<CaptionCue> {
        require(text.isNotBlank())
        return cues.map { if (it.id == cueId) it.copy(text = text) else it }
    }

    fun updateTiming(cues: List<CaptionCue>, cueId: String, startMs: Long, endMs: Long): List<CaptionCue> {
        require(startMs >= 0 && endMs > startMs)
        val index = cues.indexOfFirst { it.id == cueId }
        require(index >= 0) { "Unknown caption cue $cueId" }
        val cue = cues[index]
        val firstWordStart = cue.wordTimings.minOfOrNull { it.sourceRange.start.value / 1_000 }
        val lastWordEnd = cue.wordTimings.maxOfOrNull { it.sourceRange.endExclusive.value / 1_000 }
        if (firstWordStart != null) require(startMs <= firstWordStart) { "Cue cannot start after its first linked word" }
        if (lastWordEnd != null) require(endMs >= lastWordEnd) { "Cue cannot end before its last linked word" }
        if (index > 0) require(cues[index - 1].endMs <= startMs) { "Caption overlap" }
        if (index < cues.lastIndex) require(endMs <= cues[index + 1].startMs) { "Caption overlap" }
        return cues.toMutableList().also { it[index] = cue.copy(startMs = startMs, endMs = endMs) }
    }

    fun merge(cues: List<CaptionCue>, firstId: String, secondId: String, mergedId: String = "${firstId}_merged"): List<CaptionCue> {
        val firstIndex = cues.indexOfFirst { it.id == firstId }
        val secondIndex = cues.indexOfFirst { it.id == secondId }
        require(firstIndex >= 0 && secondIndex == firstIndex + 1) { "Only adjacent cues can be merged" }
        val first = cues[firstIndex]
        val second = cues[secondIndex]
        require(first.trackId == second.trackId && first.styleId == second.styleId) { "Cue track/style mismatch" }
        val merged = first.copy(
            id = mergedId,
            endMs = second.endMs,
            text = joinCaptionText(first.text, second.text),
            wordRefs = (first.wordRefs + second.wordRefs).distinct(),
            wordTimings = (first.wordTimings + second.wordTimings).distinctBy { it.wordId },
            rightToLeft = first.rightToLeft || second.rightToLeft,
        )
        return buildList {
            addAll(cues.take(firstIndex))
            add(merged)
            addAll(cues.drop(secondIndex + 1))
        }
    }

    /** Split is performed at a linked word boundary to preserve deterministic karaoke/timing data. */
    fun split(cues: List<CaptionCue>, cueId: String, splitBeforeWordId: TranscriptWordId): List<CaptionCue> {
        val index = cues.indexOfFirst { it.id == cueId }
        require(index >= 0) { "Unknown caption cue $cueId" }
        val cue = cues[index]
        val splitIndex = cue.wordRefs.indexOf(splitBeforeWordId)
        require(splitIndex in 1 until cue.wordRefs.size) { "Split must be inside the cue" }
        val leftRefs = cue.wordRefs.take(splitIndex)
        val rightRefs = cue.wordRefs.drop(splitIndex)
        val leftTimings = cue.wordTimings.filter { it.wordId in leftRefs }
        val rightTimings = cue.wordTimings.filter { it.wordId in rightRefs }
        require(leftTimings.isNotEmpty() && rightTimings.isNotEmpty()) { "Split word timings unavailable" }
        val boundaryMs = rightTimings.minOf { it.sourceRange.start.value / 1_000 }
        require(boundaryMs > cue.startMs && boundaryMs < cue.endMs)
        val left = cue.copy(
            id = "${cue.id}_a",
            endMs = boundaryMs,
            text = leftTimings.joinToString(" ") { it.text },
            wordRefs = leftRefs,
            wordTimings = leftTimings,
        )
        val right = cue.copy(
            id = "${cue.id}_b",
            startMs = boundaryMs,
            text = rightTimings.joinToString(" ") { it.text },
            wordRefs = rightRefs,
            wordTimings = rightTimings,
        )
        return buildList {
            addAll(cues.take(index))
            add(left)
            add(right)
            addAll(cues.drop(index + 1))
        }
    }

    private fun joinCaptionText(first: String, second: String): String = when {
        first.isBlank() -> second.trim()
        second.isBlank() -> first.trim()
        second.firstOrNull()?.let { it in ",.!?؟؛:،" } == true -> first.trimEnd() + second.trimStart()
        else -> first.trimEnd() + " " + second.trimStart()
    }
}
