package com.moatazvid.ai.editor

import com.moatazvid.core.*
import com.moatazvid.storage.ProjectSnapshot
import kotlin.math.roundToLong

data class RemovedRangeDiff(val clipId: ClipId, val sourceRange: TimeRangeUs, val reason: String)
data class MovedClipDiff(val clipId: ClipId, val from: TimeUs, val to: TimeUs)
data class EditDiff(
    val beforeDuration: DurationUs,
    val afterDuration: DurationUs,
    val operationCount: Int,
    val removedRanges: List<RemovedRangeDiff>,
    val movedClips: List<MovedClipDiff>,
    val addedCaptions: Int,
    val addedItems: Int,
    val removedItems: Int,
    val userSummary: String,
)
enum class RenderComplexity { LOW, MEDIUM, HIGH }
data class SimulationResult(
    val valid: Boolean,
    val simulatedProject: AiEditableProject?,
    val diff: EditDiff?,
    val trackConflicts: List<String>,
    val protectedViolations: List<String>,
    val captionWarnings: List<String>,
    val audioWarnings: List<String>,
    val unsupportedOperations: List<String>,
    val renderComplexity: RenderComplexity,
)

class EditSimulationEngine(private val validator: EditPlanValidator = EditPlanValidator()) {
    fun simulate(project: AiEditableProject, plan: EditPlan, cancelled: () -> Boolean = { false }): SimulationResult {
        val validation = validator.validate(plan, project)
        if (!validation.valid) return SimulationResult(false, null, null, emptyList(), validation.errors.filter { it.code == "PROTECTED_RANGE" }.map { it.message }, emptyList(), emptyList(), emptyList(), RenderComplexity.LOW)
        return try {
            var state = project
            val removed = mutableListOf<RemovedRangeDiff>(); val moved = mutableListOf<MovedClipDiff>()
            var addedCaptions = 0; var addedItems = 0; var removedItems = 0
            plan.operations.forEach { operation ->
                if (cancelled()) return SimulationResult(false, null, null, emptyList(), emptyList(), emptyList(), emptyList(), listOf("CANCELLED"), RenderComplexity.LOW)
                val beforeIds = state.snapshot.items.map { it.id }.toSet()
                if (operation is EditOperation.RemoveRange) removed += RemovedRangeDiff(operation.clipId, operation.sourceRange, operation.reason)
                if (operation is EditOperation.RemoveClip) state.snapshot.items.firstOrNull { it.id == operation.clipId }?.sourceRange?.let { removed += RemovedRangeDiff(operation.clipId, it, operation.reason) }
                if (operation is EditOperation.MoveClip) state.snapshot.items.firstOrNull { it.id == operation.clipId }?.let { old ->
                    state = TimelineOperationApplier.apply(state, operation)
                    state.snapshot.items.firstOrNull { it.id == operation.clipId }?.let { moved += MovedClipDiff(old.id, old.timelineStart, it.timelineStart) }
                } else state = TimelineOperationApplier.apply(state, operation)
                val afterIds = state.snapshot.items.map { it.id }.toSet()
                addedItems += (afterIds - beforeIds).size; removedItems += (beforeIds - afterIds).size
                if (operation is EditOperation.AddCaptions) addedCaptions += operation.drafts.size
            }
            val conflicts = findConflicts(state)
            val diff = EditDiff(project.duration, state.duration, plan.operations.size, removed, moved, addedCaptions, addedItems, removedItems,
                summarize(project.duration, state.duration, removed, moved, addedCaptions, plan.operations.size))
            val complexity = when {
                plan.operations.any { it is EditOperation.AddZoom || it is EditOperation.ApplyColorAdjustment } || plan.operations.size > 30 -> RenderComplexity.HIGH
                plan.operations.size > 10 -> RenderComplexity.MEDIUM
                else -> RenderComplexity.LOW
            }
            SimulationResult(conflicts.isEmpty(), state, diff, conflicts, emptyList(), captionWarnings(state), audioWarnings(state), emptyList(), complexity)
        } catch (failure: Throwable) {
            SimulationResult(false, null, null, emptyList(), emptyList(), emptyList(), emptyList(), listOf(failure.message ?: "Simulation failed"), RenderComplexity.LOW)
        }
    }

    private fun findConflicts(project: AiEditableProject): List<String> = project.snapshot.tracks.filter { it.collisionPolicy == CollisionPolicy.NO_OVERLAP }.flatMap { track ->
        project.snapshot.items.filter { it.trackId == track.id }.sortedBy { it.timelineStart.value }.zipWithNext().mapNotNull { (a, b) ->
            if (a.timelineStart.value + a.timelineDuration.value > b.timelineStart.value) "${track.id.value}:${a.id.value}/${b.id.value}" else null
        }
    }
    private fun captionWarnings(project: AiEditableProject) = project.captions.filter { it.sourceRange.endExclusive.value > project.duration.value }.map { "Caption ${it.id} may be outside timeline" }
    private fun audioWarnings(project: AiEditableProject) = project.snapshot.items.filter { it.type == TimelineItemType.AUDIO || it.type == TimelineItemType.MUSIC }.zipWithNext().mapNotNull { (a, b) ->
        if (a.timelineStart.value + a.timelineDuration.value < b.timelineStart.value) "Audio gap after ${a.id.value}" else null
    }
    private fun summarize(before: DurationUs, after: DurationUs, removed: List<RemovedRangeDiff>, moved: List<MovedClipDiff>, captions: Int, count: Int) = buildString {
        append("خطة من $count تعديلات. المدة ${format(before)} → ${format(after)}.")
        if (removed.isNotEmpty()) append(" تقصير/حذف ${removed.size} مقاطع.")
        if (moved.isNotEmpty()) append(" نقل ${moved.size} مقاطع.")
        if (captions > 0) append(" إضافة $captions ترجمات.")
    }
    private fun format(duration: DurationUs): String { val seconds = duration.value / 1_000_000; return "%02d:%02d".format(seconds / 60, seconds % 60) }
}

object TimelineOperationApplier {
    fun apply(project: AiEditableProject, operation: EditOperation): AiEditableProject {
        var items = project.snapshot.items.toMutableList()
        var properties = project.clipProperties.toMutableMap()
        var constraints = project.constraints.toMutableList()
        var captions = project.captions.toMutableList()
        var sequence = project.snapshot.sequence
        fun find(id: ClipId) = items.first { it.id == id }
        fun replace(item: TimelineItem) { items[items.indexOfFirst { it.id == item.id }] = item }
        fun ripple(start: Long, delta: Long, excluding: Set<ClipId> = emptySet()) {
            items = items.map { if (it.id !in excluding && it.timelineStart.value >= start) it.copy(timelineStart = TimeUs((it.timelineStart.value + delta).coerceAtLeast(0))) else it }.toMutableList()
        }
        when (operation) {
            is EditOperation.TrimClip -> {
                val clip = find(operation.clipId); val speed = properties[clip.id]?.speed ?: 1.0
                replace(clip.copy(sourceRange = operation.sourceRange, timelineDuration = DurationUs((operation.sourceRange.duration.value / speed).roundToLong())))
            }
            is EditOperation.SplitClip -> {
                val clip = find(operation.clipId); val offset = operation.atTimeline.value - clip.timelineStart.value; val speed = properties[clip.id]?.speed ?: 1.0
                val clipSourceRange = requireNotNull(clip.sourceRange)
                val splitSource = clipSourceRange.start.value + (offset * speed).roundToLong()
                items.remove(clip)
                items += clip.copy(id = operation.leftClipId, timelineDuration = DurationUs(offset), sourceRange = TimeRangeUs(clipSourceRange.start, TimeUs(splitSource)))
                items += clip.copy(id = operation.rightClipId, timelineStart = operation.atTimeline, timelineDuration = DurationUs(clip.timelineDuration.value - offset), sourceRange = TimeRangeUs(TimeUs(splitSource), clipSourceRange.endExclusive))
                properties.remove(clip.id)?.let { properties[operation.leftClipId] = it; properties[operation.rightClipId] = it }
            }
            is EditOperation.RemoveRange -> {
                val clip = find(operation.clipId); val source = requireNotNull(clip.sourceRange); val speed = properties[clip.id]?.speed ?: 1.0
                val removedTimeline = (operation.sourceRange.duration.value / speed).roundToLong()
                val removeTimelineStart = clip.timelineStart.value + ((operation.sourceRange.start.value - source.start.value) / speed).roundToLong()
                items.remove(clip); val prop = properties.remove(clip.id)
                if (operation.sourceRange.start > source.start && operation.leftClipId != null) {
                    val leftDuration = ((operation.sourceRange.start.value - source.start.value) / speed).roundToLong()
                    items += clip.copy(id = operation.leftClipId, timelineDuration = DurationUs(leftDuration), sourceRange = TimeRangeUs(source.start, operation.sourceRange.start)); prop?.let { properties[operation.leftClipId] = it }
                }
                if (operation.sourceRange.endExclusive < source.endExclusive && operation.rightClipId != null) {
                    val rightDuration = ((source.endExclusive.value - operation.sourceRange.endExclusive.value) / speed).roundToLong()
                    items += clip.copy(id = operation.rightClipId, timelineStart = TimeUs(removeTimelineStart), timelineDuration = DurationUs(rightDuration), sourceRange = TimeRangeUs(operation.sourceRange.endExclusive, source.endExclusive)); prop?.let { properties[operation.rightClipId] = it }
                }
                ripple(removeTimelineStart + removedTimeline, -removedTimeline, setOfNotNull(operation.leftClipId, operation.rightClipId))
            }
            is EditOperation.RemoveClip -> { val clip = find(operation.clipId); items.remove(clip); properties.remove(clip.id); ripple(clip.timelineStart.value + clip.timelineDuration.value, -clip.timelineDuration.value) }
            is EditOperation.MoveClip -> {
                val clip = find(operation.clipId); items.remove(clip)
                val target = items.filter { it.trackId == operation.targetTrackId }.sortedBy { it.timelineStart.value }.toMutableList()
                target.add(operation.targetIndex.coerceAtMost(target.size), clip.copy(trackId = operation.targetTrackId))
                var cursor = 0L; val reordered = target.map { it.copy(timelineStart = TimeUs(cursor)).also { updated -> cursor += updated.timelineDuration.value } }
                items = (items.filter { it.trackId != operation.targetTrackId } + reordered).toMutableList()
            }
            is EditOperation.InsertRange -> items += TimelineItem(operation.newClipId, project.snapshot.project.id, sequence.id, operation.targetTrackId, TimelineItemType.VIDEO,
                operation.timelineStart, operation.sourceRange.duration, operation.sourceId, operation.sourceRange)
            is EditOperation.ReplaceWithTake -> { val clip = find(operation.oldClipId); replace(clip.copy(sourceId = operation.newSourceId, sourceRange = operation.sourceRange, timelineDuration = operation.sourceRange.duration)) }
            is EditOperation.ChangeSpeed -> { val clip = find(operation.clipId); properties[clip.id] = (properties[clip.id] ?: ClipEditProperties()).copy(speed = operation.speed, preservePitch = operation.preservePitch); replace(clip.copy(timelineDuration = DurationUs((requireNotNull(clip.sourceRange).duration.value / operation.speed).roundToLong()))) }
            is EditOperation.SetCrop -> properties[operation.clipId] = (properties[operation.clipId] ?: ClipEditProperties()).copy(cropAspectRatio = operation.aspectRatio)
            is EditOperation.SetTransform -> properties[operation.clipId] = (properties[operation.clipId] ?: ClipEditProperties()).copy(transform = operation.transform)
            is EditOperation.AddZoom -> properties[operation.clipId] = (properties[operation.clipId] ?: ClipEditProperties()).copy(effects = (properties[operation.clipId]?.effects.orEmpty() + "zoom:${operation.timelineRange.start.value}:${operation.timelineRange.endExclusive.value}:${operation.scaleFrom}:${operation.scaleTo}"))
            is EditOperation.AddText -> items += TimelineItem(operation.id, project.snapshot.project.id, sequence.id, operation.trackId, TimelineItemType.TEXT, operation.timelineRange.start, operation.timelineRange.duration, null, null)
            is EditOperation.AddCaptions -> captions += operation.drafts
            is EditOperation.UpdateCaptionStyle -> Unit
            is EditOperation.AddAudio -> items += TimelineItem(operation.id, project.snapshot.project.id, sequence.id, operation.trackId, TimelineItemType.MUSIC, operation.timelineStart, operation.duration, null, null).also { properties[it.id] = ClipEditProperties(gainDb = volumeToDb(operation.volume)) }
            is EditOperation.RemoveAudio -> { items.remove(find(operation.clipId)); properties.remove(operation.clipId) }
            is EditOperation.SetAudioGain -> properties[operation.clipId] = (properties[operation.clipId] ?: ClipEditProperties()).copy(gainDb = operation.gainDb)
            is EditOperation.AddFade -> properties[operation.clipId] = (properties[operation.clipId] ?: ClipEditProperties()).let { it.copy(fades = it.fades + (operation.fadeType to operation.duration)) }
            is EditOperation.ApplyColorAdjustment -> properties[operation.clipId] = (properties[operation.clipId] ?: ClipEditProperties()).let { it.copy(effects = it.effects + "color:${operation.brightness}:${operation.contrast}:${operation.saturation}") }
            is EditOperation.SetProjectAspectRatio -> sequence = sequence.copy(canvasWidth = operation.width, canvasHeight = operation.height)
            is EditOperation.SetDurationTarget -> Unit
            is EditOperation.AddConstraint -> constraints += operation.constraint
            is EditOperation.RemoveConstraint -> constraints.removeAll { it.id == operation.constraintId }
        }
        val snapshot = ProjectSnapshot(project.snapshot.project, sequence, project.snapshot.tracks, items.sortedBy { it.timelineStart.value }, project.snapshot.constraintsRevision)
        return project.copy(snapshot = snapshot, clipProperties = properties, constraints = constraints, captions = captions)
    }
    private fun volumeToDb(volume: Float): Float = if (volume <= 0f) -60f else (20 * kotlin.math.log10(volume.toDouble())).toFloat().coerceIn(-60f, 24f)
}
