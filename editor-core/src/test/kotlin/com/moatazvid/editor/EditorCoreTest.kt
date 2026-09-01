package com.moatazvid.editor

import com.moatazvid.ai.editor.*
import com.moatazvid.core.*
import com.moatazvid.storage.ProjectSnapshot
import com.moatazvid.speech.SilenceRange
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EditorCoreTest {
    @Test fun `timeline zoom preserves focal time and clamps scale`() {
        val state = TimelineViewportState(100.0, 500.0, 1_000.0)
        val before = state.timeFor(250.0)
        val zoomed = state.zoomBy(2.0, 250.0)
        assertEquals(before.value, zoomed.timeFor(250.0).value)
        assertEquals(200.0, zoomed.pixelsPerSecond)
        assertEquals(2_000.0, zoomed.zoomBy(100.0, 0.0).pixelsPerSecond)
    }

    @Test fun `large timeline virtualizer returns only visible neighborhood`() {
        val project = fixture(5_000)
        val viewport = TimelineViewportState(100.0, 250_000.0, 1_000.0)
        val visible = TimelineVirtualizer.visibleItems(project.snapshot.items, viewport)
        assertTrue(visible.size < 40)
        assertTrue(visible.all { it.timelineStart.value < viewport.visibleRange.endExclusive.value + viewport.visibleRange.duration.value })
    }

    @Test fun `manual trim commits once and undo redo restore state`() = runBlocking {
        val project = fixture(1); val store = InMemoryAiTimelineStore(project); val service = ManualEditService(store)
        val clip = project.snapshot.items.single()
        val trimmed = service.trim(PROJECT, clip.id, TimeRangeUs(TimeUs(1_000_000), TimeUs(4_000_000))) as ManualEditResult.Success
        assertEquals(2, trimmed.commit.project.revision)
        assertEquals(3_000_000, trimmed.commit.project.snapshot.items.single().timelineDuration.value)
        assertEquals(5_000_000, project.snapshot.items.single().timelineDuration.value)
        assertTrue(service.undo(SEQ) is ManualEditResult.Success)
        assertEquals(5_000_000, store.load(PROJECT)!!.snapshot.items.single().timelineDuration.value)
        assertTrue(service.redo(SEQ) is ManualEditResult.Success)
        assertEquals(3_000_000, store.load(PROJECT)!!.snapshot.items.single().timelineDuration.value)
    }

    @Test fun `manual split delete and reorder are atomic commands`() = runBlocking {
        val splitStore = InMemoryAiTimelineStore(fixture(1)); val splitService = ManualEditService(splitStore)
        val original = splitStore.load(PROJECT)!!.snapshot.items.single()
        assertTrue(splitService.split(PROJECT, original.id, TimeUs(2_000_000)) is ManualEditResult.Success)
        assertEquals(2, splitStore.load(PROJECT)!!.snapshot.items.size)
        val deleteId = splitStore.load(PROJECT)!!.snapshot.items.first().id
        assertTrue(splitService.delete(PROJECT, deleteId) is ManualEditResult.Success)
        assertEquals(1, splitStore.load(PROJECT)!!.snapshot.items.size)

        val moveStore = InMemoryAiTimelineStore(fixture(3)); val moveService = ManualEditService(moveStore)
        val last = moveStore.load(PROJECT)!!.snapshot.items.last().id
        assertTrue(moveService.move(PROJECT, last, TRACK, 0) is ManualEditResult.Success)
        assertEquals(last, moveStore.load(PROJECT)!!.snapshot.items.sortedBy { it.timelineStart.value }.first().id)
    }

    @Test fun `auto preview quality protects low memory devices`() {
        assertEquals(PreviewQuality.LOW, PreviewQualitySelector.resolve(PreviewQuality.AUTO, PreviewDeviceState(true, false, false, 10)))
        assertEquals(PreviewQuality.MEDIUM, PreviewQualitySelector.resolve(PreviewQuality.AUTO, PreviewDeviceState(false, false, true, 90)))
    }

    @Test fun `scenario A silence plan preview apply and undo`() = runBlocking {
        val project = fixture(1); val ids = SequentialEditorIdFactory(); val store = InMemoryAiTimelineStore(project)
        val plan = SilenceCommandPlanner(ids).plan(project, listOf(SilenceRange(project.sources.single().id, TimeRangeUs(TimeUs(1_000_000), TimeUs(3_000_000)), -55.0)))
        val coordinator = PendingEditCoordinator(store)
        val pending = coordinator.create(ids.pending(), plan, null, "local")
        assertEquals(PendingEditStatus.READY, pending.status); assertNotNull(pending.simulationResult.diff)
        assertTrue(coordinator.apply(pending.id, ids.transaction()) is CommitResult.Success)
        assertTrue(store.undo(SEQ, aiOnly = true) is CommitResult.Success)
        assertEquals(project.duration.value, store.load(PROJECT)!!.duration.value)
    }

    @Test fun `scenario B best take candidates produce reviewable duration plan`() {
        val project = fixture(3); val ids = SequentialEditorIdFactory()
        val clips = project.snapshot.items
        val takes = TakeCandidateGroup("g", clips.mapIndexed { index, clip -> TakeCandidate(clip.id, "same idea", clip.timelineDuration,
            audioScore = 0.5 + index * 0.2, visualScore = 0.6, speechConfidence = 0.7 + index * 0.1, fillerCount = 2 - index, slipCount = 0, silenceRatio = 0.1) })
        val takePlan = BestTakePlanner(ids).plan(project, listOf(takes))
        assertEquals(2, takePlan.operations.size)
        val duration = DurationPlanner().plan(project, DurationUs(5_000_000), takePlan.operations)
        assertTrue(duration.possible); assertTrue(duration.estimatedDuration.value <= 5_250_000)
    }

    @Test fun `scenario C protected price survives shortening`() {
        val project = fixture(2).let { base -> base.copy(protectedRanges = listOf(ProtectedRange("price", PROJECT, base.sources.first().id, TimeRangeUs(TimeUs(500_000), TimeUs(2_000_000)), "السعر"))) }
        val plan = EditPlan(id = EditPlanId("p"), projectId = PROJECT, sequenceId = SEQ, baseProjectRevision = project.revision, title = "Half", summary = "Half",
            operations = listOf(EditOperation.RemoveClip(project.snapshot.items.first().id, "shorten")), estimatedResult = null)
        assertTrue(EditPlanValidator().validate(plan, project).errors.any { it.code == "PROTECTED_RANGE" })
    }

    @Test fun `scenario D manual edit makes pending AI plan stale`() = runBlocking {
        val project = fixture(1); val store = InMemoryAiTimelineStore(project); val ids = SequentialEditorIdFactory(); val coordinator = PendingEditCoordinator(store)
        val plan = EditPlan(id = ids.plan(), projectId = PROJECT, sequenceId = SEQ, baseProjectRevision = project.revision, title = "AI", summary = "AI",
            operations = listOf(EditOperation.TrimClip(project.snapshot.items.single().id, TimeRangeUs(TimeUs(0), TimeUs(4_000_000)))), estimatedResult = null)
        val pending = coordinator.create(ids.pending(), plan, null, "fake")
        ManualEditService(store, ids).trim(PROJECT, project.snapshot.items.single().id, TimeRangeUs(TimeUs(500_000), TimeUs(5_000_000)))
        assertEquals(PendingEditStatus.STALE, coordinator.get(pending.id)?.status)
    }

    @Test fun `scenario E manual editing works without any provider`() = runBlocking {
        val project = fixture(1); val store = InMemoryAiTimelineStore(project)
        val result = ManualEditService(store).delete(PROJECT, project.snapshot.items.single().id)
        assertTrue(result is ManualEditResult.Success); assertTrue(store.load(PROJECT)!!.snapshot.items.isEmpty())
    }
}

private val PROJECT = ProjectId("project")
private val SEQ = SequenceId("sequence")
private val TRACK = TrackId("video")
private fun fixture(count: Int): AiEditableProject {
    val project = Project(PROJECT, "Editor", SEQ, 1, createdAtEpochMs = 1, updatedAtEpochMs = 1)
    val sequence = Sequence(SEQ, PROJECT, "Main", 1920, 1080, Rational.FPS_30, ProjectColorMode.SDR, 1)
    val track = Track(TRACK, SEQ, TrackType.VIDEO, 0, CollisionPolicy.NO_OVERLAP)
    val sources = (0 until count).map { index -> MediaSource(SourceId("source_$index"), PROJECT, MediaKind.VIDEO, "v$index.mp4", "ref_$index", ImportMode.MANAGED_COPY, "video/mp4", DurationUs(5_000_000), 1920, 1080, 0, Rational.FPS_30, "f$index", SourceAvailability.AVAILABLE) }
    val items = sources.mapIndexed { index, source -> TimelineItem(ClipId("clip_$index"), PROJECT, SEQ, TRACK, TimelineItemType.VIDEO, TimeUs(index * 5_000_000L), DurationUs(5_000_000), source.id, TimeRangeUs(TimeUs(0), TimeUs(5_000_000))) }
    return AiEditableProject(ProjectSnapshot(project, sequence, listOf(track), items, 0), sources)
}
