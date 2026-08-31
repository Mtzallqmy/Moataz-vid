package com.moatazvid.ai.editor

import com.moatazvid.core.*

enum class ValidationSeverity { ERROR, WARNING }
data class PlanValidationError(val code: String, val path: String, val message: String, val severity: ValidationSeverity = ValidationSeverity.ERROR)
data class PlanValidationResult(val valid: Boolean, val normalizedPlan: EditPlan, val errors: List<PlanValidationError>)

class EditPlanValidator(
    private val minClipDuration: DurationUs = DurationUs(50_000),
    private val speedRange: ClosedFloatingPointRange<Double> = 0.25..4.0,
) {
    fun validate(plan: EditPlan, project: AiEditableProject): PlanValidationResult {
        val errors = mutableListOf<PlanValidationError>()
        if (plan.projectId != project.snapshot.project.id) errors += error("PROJECT_MISMATCH", "projectId", "Plan belongs to another project")
        if (plan.sequenceId != project.snapshot.sequence.id) errors += error("SEQUENCE_MISMATCH", "sequenceId", "Plan belongs to another sequence")
        if (plan.baseProjectRevision != project.revision) errors += error("STALE_REVISION", "baseProjectRevision", "Expected ${project.revision}, got ${plan.baseProjectRevision}")
        val items = project.snapshot.items.associateBy { it.id }
        val tracks = project.snapshot.tracks.associateBy { it.id }
        val sources = project.sources.associateBy { it.id }
        val generatedIds = mutableSetOf<ClipId>()
        plan.operations.forEachIndexed { index, operation ->
            val path = "operations[$index]"
            fun item(id: ClipId): TimelineItem? = items[id].also { if (it == null) errors += error("UNKNOWN_CLIP", "$path.clipId", id.value) }
            fun editable(id: ClipId): TimelineItem? = item(id)?.also { if (it.locked || tracks[it.trackId]?.locked == true) errors += error("LOCKED_CLIP", "$path.clipId", id.value) }
            fun track(id: TrackId, compatible: Set<TrackType>? = null): Track? = tracks[id].also {
                if (it == null) errors += error("UNKNOWN_TRACK", "$path.trackId", id.value)
                else if (compatible != null && it.type !in compatible) errors += error("TRACK_INCOMPATIBLE", "$path.trackId", it.type.name)
                else if (it.locked) errors += error("LOCKED_TRACK", "$path.trackId", id.value)
            }
            fun sourceRange(sourceId: SourceId, range: TimeRangeUs) {
                val source = sources[sourceId]
                if (source == null) errors += error("UNKNOWN_SOURCE", "$path.sourceId", sourceId.value)
                else if (source.duration != null && range.endExclusive.value > source.duration.value) errors += error("SOURCE_RANGE_OUT_OF_BOUNDS", "$path.sourceRange", sourceId.value)
                project.protectedRanges.filter { it.active && it.sourceId == sourceId && it.sourceRange.overlaps(range) }.forEach {
                    errors += error("PROTECTED_RANGE", "$path.sourceRange", it.reason)
                }
            }
            fun generated(id: ClipId?) { if (id != null && (id in items || !generatedIds.add(id))) errors += error("DUPLICATE_ID", path, id.value) }
            when (operation) {
                is EditOperation.TrimClip -> editable(operation.clipId)?.let { clip ->
                    sourceRange(requireNotNull(clip.sourceId), operation.sourceRange)
                    if (operation.sourceRange.duration.value < minClipDuration.value) errors += error("CLIP_TOO_SHORT", "$path.sourceRange", "Minimum 50ms")
                }
                is EditOperation.SplitClip -> editable(operation.clipId)?.let { clip ->
                    if (operation.atTimeline.value <= clip.timelineStart.value || operation.atTimeline.value >= clip.timelineStart.value + clip.timelineDuration.value) errors += error("SPLIT_OUT_OF_RANGE", "$path.atTimeline", operation.clipId.value)
                }.also { generated(operation.leftClipId); generated(operation.rightClipId) }
                is EditOperation.RemoveRange -> editable(operation.clipId)?.let { clip -> sourceRange(requireNotNull(clip.sourceId), operation.sourceRange) }.also { generated(operation.leftClipId); generated(operation.rightClipId) }
                is EditOperation.RemoveClip -> editable(operation.clipId)?.let { clip -> clip.sourceId?.let { sourceRange(it, requireNotNull(clip.sourceRange)) } }
                is EditOperation.MoveClip -> { editable(operation.clipId); track(operation.targetTrackId); if (operation.targetIndex < 0) errors += error("INVALID_INDEX", "$path.targetIndex", "Negative") }
                is EditOperation.InsertRange -> { sourceRange(operation.sourceId, operation.sourceRange); track(operation.targetTrackId, setOf(TrackType.VIDEO, TrackType.AUDIO)); generated(operation.newClipId) }
                is EditOperation.ReplaceWithTake -> { editable(operation.oldClipId); sourceRange(operation.newSourceId, operation.sourceRange) }
                is EditOperation.ChangeSpeed -> { editable(operation.clipId); if (operation.speed !in speedRange) errors += error("INVALID_SPEED", "$path.speed", operation.speed.toString()) }
                is EditOperation.SetCrop -> { editable(operation.clipId); if (!operation.aspectRatio.matches(Regex("[1-9][0-9]*:[1-9][0-9]*"))) errors += error("INVALID_ASPECT", "$path.aspectRatio", operation.aspectRatio) }
                is EditOperation.SetTransform -> editable(operation.clipId)
                is EditOperation.AddZoom -> { editable(operation.clipId); if (operation.scaleFrom !in 0.5f..4f || operation.scaleTo !in 0.5f..4f) errors += error("INVALID_ZOOM", path, "Scale outside 0.5..4") }
                is EditOperation.AddText -> { track(operation.trackId, setOf(TrackType.OVERLAY)); generated(operation.id); if (operation.text.isBlank()) errors += error("EMPTY_TEXT", "$path.text", "Blank") }
                is EditOperation.AddCaptions -> { track(operation.trackId, setOf(TrackType.CAPTION)); if (operation.drafts.any { it.wordIds.isEmpty() }) errors += error("CAPTION_WITHOUT_WORDS", "$path.drafts", "Caption must link transcript words") }
                is EditOperation.UpdateCaptionStyle -> if (operation.wordsPerChunk !in 1..12 || operation.fontScale !in 0.5f..2f) errors += error("INVALID_CAPTION_STYLE", path, "Style limits")
                is EditOperation.AddAudio -> { track(operation.trackId, setOf(TrackType.AUDIO, TrackType.MUSIC)); generated(operation.id); if (operation.volume !in 0f..1f) errors += error("INVALID_VOLUME", "$path.volume", operation.volume.toString()) }
                is EditOperation.RemoveAudio -> editable(operation.clipId)?.let { if (it.type !in setOf(TimelineItemType.AUDIO, TimelineItemType.MUSIC)) errors += error("NOT_AUDIO", "$path.clipId", operation.clipId.value) }
                is EditOperation.SetAudioGain -> { editable(operation.clipId); if (operation.gainDb !in -60f..24f) errors += error("INVALID_GAIN", "$path.gainDb", operation.gainDb.toString()) }
                is EditOperation.AddFade -> editable(operation.clipId)?.let { if (operation.duration.value > it.timelineDuration.value) errors += error("FADE_TOO_LONG", "$path.duration", operation.clipId.value) }
                is EditOperation.ApplyColorAdjustment -> { editable(operation.clipId); if (operation.brightness !in -1f..1f || operation.contrast !in 0f..2f || operation.saturation !in 0f..2f) errors += error("INVALID_COLOR", path, "Color limits") }
                is EditOperation.SetProjectAspectRatio -> if (operation.width <= 0 || operation.height <= 0) errors += error("INVALID_CANVAS", path, "Positive dimensions required")
                is EditOperation.SetDurationTarget -> if (operation.duration.value < 1_000_000 || operation.tolerancePercent !in 0.0..25.0) errors += error("INVALID_DURATION_TARGET", path, "Target limits")
                is EditOperation.AddConstraint -> if (operation.constraint.projectId != plan.projectId) errors += error("CONSTRAINT_PROJECT_MISMATCH", path, operation.constraint.id.value)
                is EditOperation.RemoveConstraint -> if (project.constraints.none { it.id == operation.constraintId }) errors += error("UNKNOWN_CONSTRAINT", path, operation.constraintId.value)
            }
        }
        return PlanValidationResult(errors.none { it.severity == ValidationSeverity.ERROR }, plan, errors)
    }
    private fun error(code: String, path: String, message: String) = PlanValidationError(code, path, message)
}
