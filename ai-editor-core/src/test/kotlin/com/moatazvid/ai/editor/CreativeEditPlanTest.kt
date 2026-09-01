package com.moatazvid.ai.editor

import com.moatazvid.core.*
import com.moatazvid.media.*
import com.moatazvid.storage.ProjectSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreativeEditPlanTest {
    @Test fun `creative plan passes validator and simulation`() {
        val project = fixture()
        val operations = listOf(
            EditOperation.AddText(ClipId("title"), TrackId("overlay"), range(0, 2_000_000), "عنوان", "clean"),
            EditOperation.AddImageOverlay(ClipId("logo"), AssetId("logo_asset"), TrackId("overlay"), range(0, 5_000_000), CreativeTransform(positionX = 0.9f, positionY = 0.1f, scaleX = 0.2f, scaleY = 0.2f)),
            EditOperation.AddEffect(ClipId("a"), EffectId("brightness"), EffectType.BRIGHTNESS, mapOf("amount" to 0.15)),
            EditOperation.AddTransition(CreativeTransition(TransitionId("cross"), CreativeTransitionType.CROSS_DISSOLVE, 400, ClipId("a"), ClipId("b"))),
            EditOperation.SetDucking(TrackId("music"), DuckingSettings(DuckingMode.AUTO_SPEECH_DUCK, -12f)),
        )
        val plan = plan(project, operations)
        val validation = EditPlanValidator().validate(plan, project)
        assertTrue(validation.valid, validation.errors.joinToString { it.code })
        val simulation = EditSimulationEngine().simulate(project, plan)
        assertTrue(simulation.valid)
        val output = requireNotNull(simulation.simulatedProject)
        assertEquals(2, output.creativeElements.size)
        assertEquals(1, output.creativeEffects[ClipId("a")]?.size)
        assertEquals(1, output.creativeTransitions.size)
        assertEquals(DuckingMode.AUTO_SPEECH_DUCK, output.ducking[TrackId("music")]?.mode)
    }

    @Test fun `creative edits remain undoable through pending transaction`() = runTest {
        val project = fixture()
        val store = InMemoryAiTimelineStore(project)
        val coordinator = PendingEditCoordinator(store, clock = { 10L })
        val plan = plan(project, listOf(EditOperation.AddText(ClipId("title"), TrackId("overlay"), range(0, 1_000_000), "CTA", "clean")))
        val pending = coordinator.create(PendingEditId("pending"), plan, null, null)
        assertEquals(PendingEditStatus.READY, pending.status)
        val applied = coordinator.apply(pending.id, TransactionId("tx"))
        assertTrue(applied is CommitResult.Success)
        val undone = store.undo(SequenceId("seq"))
        assertTrue(undone is CommitResult.Success)
        assertTrue((undone as CommitResult.Success).project.creativeElements.isEmpty())
    }

    @Test fun `stale revision rejects creative plan`() {
        val project = fixture()
        val validation = EditPlanValidator().validate(plan(project, listOf(EditOperation.AddText(ClipId("title"), TrackId("overlay"), range(0, 1_000_000), "x", "clean"))).copy(baseProjectRevision = 0), project)
        assertTrue(validation.errors.any { it.code == "STALE_REVISION" })
    }

    private fun fixture(): AiEditableProject {
        val projectId = ProjectId("p")
        val sequence = Sequence(SequenceId("seq"), projectId, "Main", 1080, 1920, Rational.FPS_30, ProjectColorMode.SDR, 4)
        val project = Project(projectId, "Project", sequence.id, 4, createdAtEpochMs = 0, updatedAtEpochMs = 0)
        val videoTrack = Track(TrackId("video"), sequence.id, TrackType.VIDEO, 0, CollisionPolicy.NO_OVERLAP)
        val overlay = Track(TrackId("overlay"), sequence.id, TrackType.OVERLAY, 0, CollisionPolicy.STACK)
        val music = Track(TrackId("music"), sequence.id, TrackType.MUSIC, 0, CollisionPolicy.ALLOW_OVERLAP)
        val a = TimelineItem(ClipId("a"), projectId, sequence.id, videoTrack.id, TimelineItemType.VIDEO, TimeUs(0), DurationUs(5_000_000), SourceId("src"), range(0, 5_000_000))
        val b = TimelineItem(ClipId("b"), projectId, sequence.id, videoTrack.id, TimelineItemType.VIDEO, TimeUs(5_000_000), DurationUs(5_000_000), SourceId("src"), range(5_000_000, 10_000_000))
        val source = MediaSource(SourceId("src"), projectId, MediaKind.VIDEO, "video", "file", ImportMode.LINKED_SAF, "video/mp4", DurationUs(10_000_000), 1920, 1080, 0, Rational.FPS_30, "fp", SourceAvailability.AVAILABLE)
        return AiEditableProject(ProjectSnapshot(project, sequence, listOf(videoTrack, overlay, music), listOf(a, b), 0), listOf(source))
    }

    private fun plan(project: AiEditableProject, operations: List<EditOperation>) = EditPlan(
        id = EditPlanId("plan"), projectId = project.snapshot.project.id, sequenceId = project.snapshot.sequence.id,
        baseProjectRevision = project.revision, title = "Creative", summary = "Creative", operations = operations,
        estimatedResult = EstimatedEditResult(project.duration, project.duration),
    )

    private fun range(start: Long, end: Long) = TimeRangeUs(TimeUs(start), TimeUs(end))
}
