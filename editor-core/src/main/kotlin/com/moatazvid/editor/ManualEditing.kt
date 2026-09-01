package com.moatazvid.editor

import com.moatazvid.ai.editor.*
import com.moatazvid.core.*

class ManualEditService(
    private val store: AiTimelineStore,
    private val ids: EditorIdFactory = SequentialEditorIdFactory(),
    private val simulator: EditSimulationEngine = EditSimulationEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun trim(projectId: ProjectId, clipId: ClipId, sourceRange: TimeRangeUs): ManualEditResult = apply(projectId, "Trim clip", listOf(EditOperation.TrimClip(clipId, sourceRange)))
    suspend fun split(projectId: ProjectId, clipId: ClipId, at: TimeUs): ManualEditResult = apply(projectId, "Split clip", listOf(EditOperation.SplitClip(clipId, at, ids.clip("split_left"), ids.clip("split_right"))))
    suspend fun delete(projectId: ProjectId, clipId: ClipId): ManualEditResult = apply(projectId, "Delete clip", listOf(EditOperation.RemoveClip(clipId, "manual_delete")))
    suspend fun move(projectId: ProjectId, clipId: ClipId, trackId: TrackId, index: Int): ManualEditResult = apply(projectId, "Move clip", listOf(EditOperation.MoveClip(clipId, trackId, index)))
    suspend fun setGain(projectId: ProjectId, clipId: ClipId, gainDb: Float): ManualEditResult = apply(projectId, "Adjust volume", listOf(EditOperation.SetAudioGain(clipId, gainDb)))
    suspend fun setSpeed(projectId: ProjectId, clipId: ClipId, speed: Double): ManualEditResult = apply(projectId, "Change speed", listOf(EditOperation.ChangeSpeed(clipId, speed)))

    suspend fun undo(sequenceId: SequenceId): ManualEditResult = when (val result = store.undo(sequenceId)) { is CommitResult.Success -> ManualEditResult.Success(result); is CommitResult.Failure -> ManualEditResult.Failure(result.error.toString()) }
    suspend fun redo(sequenceId: SequenceId): ManualEditResult = when (val result = store.redo(sequenceId)) { is CommitResult.Success -> ManualEditResult.Success(result); is CommitResult.Failure -> ManualEditResult.Failure(result.error.toString()) }

    private suspend fun apply(projectId: ProjectId, title: String, operations: List<EditOperation>): ManualEditResult {
        val project = store.load(projectId) ?: return ManualEditResult.Failure("Project not found")
        val plan = EditPlan(id = ids.plan(), projectId = projectId, sequenceId = project.snapshot.sequence.id, baseProjectRevision = project.revision,
            title = title, summary = title, operations = operations, estimatedResult = null, requiresUserApproval = false)
        val simulation = simulator.simulate(project, plan)
        val next = simulation.simulatedProject ?: return ManualEditResult.Failure(simulation.unsupportedOperations.joinToString().ifBlank { "Invalid edit" })
        val diff = simulation.diff ?: return ManualEditResult.Failure("Missing edit diff")
        val tx = AppliedEditTransaction(ids.transaction(), projectId, project.snapshot.sequence.id, title, EditOrigin.MANUAL, project.revision, project.revision + 1, null, diff, clock())
        return when (val commit = store.commitAtomic(project.revision, next, tx)) { is CommitResult.Success -> ManualEditResult.Success(commit); is CommitResult.Failure -> ManualEditResult.Failure(commit.error.toString()) }
    }
}
