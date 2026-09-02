package com.moatazvid.ai.editor

import com.moatazvid.core.*
import com.moatazvid.speech.TranscriptWord
import com.moatazvid.speech.TranscriptWordType

/**
 * Enforces the transcript-level hard rule inherited from video-use: no AI-generated cut may land
 * inside a spoken word. This is independent from prompting and therefore cannot be bypassed by a
 * provider returning malformed or over-aggressive edit coordinates.
 */
class VideoUsePlanValidator {
    fun validate(
        plan: EditPlan,
        project: AiEditableProject,
        words: List<TranscriptWord>,
    ): List<PlanValidationError> {
        if (words.isEmpty()) return emptyList()
        val clips = project.snapshot.items.associateBy { it.id }
        return buildList {
            plan.operations.forEachIndexed { index, operation ->
                when (operation) {
                    is EditOperation.TrimClip -> clips[operation.clipId]?.sourceId?.let { sourceId ->
                        validateRange(index, operation.type, sourceId, operation.sourceRange, words, this)
                    }
                    is EditOperation.RemoveRange -> clips[operation.clipId]?.sourceId?.let { sourceId ->
                        validateRange(index, operation.type, sourceId, operation.sourceRange, words, this)
                    }
                    is EditOperation.InsertRange -> validateRange(index, operation.type, operation.sourceId, operation.sourceRange, words, this)
                    is EditOperation.ReplaceWithTake -> validateRange(index, operation.type, operation.newSourceId, operation.sourceRange, words, this)
                    is EditOperation.SplitClip -> {
                        val clip = clips[operation.clipId] ?: return@forEachIndexed
                        val sourceId = clip.sourceId ?: return@forEachIndexed
                        val sourceRange = clip.sourceRange ?: return@forEachIndexed
                        val relativeTimelineUs = operation.atTimeline.value - clip.timelineStart.value
                        if (relativeTimelineUs !in 1 until clip.timelineDuration.value) return@forEachIndexed
                        val speed = project.clipProperties[clip.id]?.speed ?: 1.0
                        val sourceAt = sourceRange.start.value + (relativeTimelineUs * speed).toLong()
                        findContainingWord(sourceId, sourceAt, words)?.let { word ->
                            add(
                                PlanValidationError(
                                    code = "VIDEO_USE_CUT_INSIDE_WORD",
                                    path = "operations[$index].atTimeline",
                                    message = "Split falls inside word '${word.text}' (${word.sourceRange.start.value}-${word.sourceRange.endExclusive.value}us). Use an exact word or silence boundary.",
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun validateRange(
        index: Int,
        type: String,
        sourceId: SourceId,
        range: TimeRangeUs,
        words: List<TranscriptWord>,
        errors: MutableList<PlanValidationError>,
    ) {
        findContainingWord(sourceId, range.start.value, words)?.let { word ->
            errors += PlanValidationError(
                code = "VIDEO_USE_CUT_INSIDE_WORD",
                path = "operations[$index].sourceRange.start",
                message = "$type starts inside word '${word.text}'. Move the cut to ${word.sourceRange.start.value}us or ${word.sourceRange.endExclusive.value}us and retain safe padding.",
            )
        }
        findContainingWord(sourceId, range.endExclusive.value, words)?.let { word ->
            errors += PlanValidationError(
                code = "VIDEO_USE_CUT_INSIDE_WORD",
                path = "operations[$index].sourceRange.end",
                message = "$type ends inside word '${word.text}'. Move the cut to ${word.sourceRange.start.value}us or ${word.sourceRange.endExclusive.value}us and retain safe padding.",
            )
        }
    }

    private fun findContainingWord(sourceId: SourceId, timeUs: Long, words: List<TranscriptWord>): TranscriptWord? =
        words.firstOrNull {
            it.type == TranscriptWordType.WORD &&
                it.sourceId == sourceId &&
                timeUs > it.sourceRange.start.value &&
                timeUs < it.sourceRange.endExclusive.value
        }
}
