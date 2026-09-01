package com.moatazvid.editor

import com.moatazvid.ai.editor.*
import com.moatazvid.core.*
import com.moatazvid.speech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EditorController(
    private val gateway: EditorProjectGateway,
    private val player: EditorPlayer,
    private val manual: ManualEditService,
    private val ai: AiEditorEngine,
    private val persistence: EditorStatePersistence,
    private val scope: CoroutineScope,
    private val thumbnails: ThumbnailRepository? = null,
    private val waveforms: WaveformRepository? = null,
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = mutable.asStateFlow()
    private var projectId: ProjectId? = null
    private var aiJob: Job? = null
    private var visualJob: Job? = null

    suspend fun open(id: ProjectId) = mutex.withLock {
        projectId = id
        val project = gateway.load(id) ?: run { mutable.value = EditorUiState(loading = false, errors = listOf(EditorError("project.not_found", "تعذر فتح المشروع"))); return@withLock }
        val restored = persistence.restore(id)
        val transcript = gateway.transcript(id)
        val viewport = restored?.let { TimelineViewportState(it.pixelsPerSecond, it.scrollOffsetPx, playheadTime = TimeUs(it.playheadUs), selectedItems = it.selectedClipIds.map(::ClipId).toSet()) } ?: TimelineViewportState()
        mutable.value = EditorUiState(loading = false, project = project, viewport = viewport, playback = PlaybackUiState(PlaybackStatus.IDLE, viewport.playheadTime, project.duration),
            selection = EditorSelectionContext(viewport.selectedItems, null), transcript = transcript)
        player.prepare(project, useProxy = true, quality = PreviewQuality.AUTO)
        scope.launch { player.state.collect { playback -> mutable.update { it.copy(playback = playback, viewport = it.viewport.copy(playheadTime = playback.currentTime)) } } }
        scope.launch { gateway.observeJobs(id).collect { jobs -> mutable.update { it.copy(jobs = jobs) } } }
        refreshVisibleMedia()
    }

    fun setViewportWidth(widthPx: Double) { mutable.update { it.copy(viewport = it.viewport.copy(viewportWidthPx = widthPx.coerceAtLeast(1.0))) }; refreshVisibleMedia() }
    fun scrollTo(offsetPx: Double) { mutable.update { it.copy(viewport = it.viewport.copy(scrollOffsetPx = offsetPx.coerceAtLeast(0.0))) }; refreshVisibleMedia() }
    fun zoom(factor: Double, focalPointPx: Double) { mutable.update { it.copy(viewport = it.viewport.zoomBy(factor, focalPointPx)) }; refreshVisibleMedia() }
    fun selectClip(clipId: ClipId?) = mutable.update { state ->
        val selected = clipId?.let(::setOf) ?: emptySet(); val item = state.displayProject?.snapshot?.items?.firstOrNull { it.id == clipId }
        state.copy(viewport = state.viewport.copy(selectedItems = selected), selection = EditorSelectionContext(selected, item?.let { TimeRangeUs(it.timelineStart, TimeUs(it.timelineStart.value + it.timelineDuration.value)) }),
            inspector = InspectorUiState(item?.inspectorKind() ?: InspectorKind.NONE, item != null, clipId))
    }
    fun selectRange(range: TimeRangeUs?) = mutable.update { it.copy(selection = it.selection.copy(timelineRange = range)) }

    suspend fun seek(time: TimeUs) { player.seekTo(time); mutable.update { it.copy(viewport = it.viewport.copy(playheadTime = time)) } }
    suspend fun togglePlayback() { if (mutable.value.playback.status == PlaybackStatus.PLAYING) player.pause() else player.play() }
    suspend fun setMuted(muted: Boolean) { player.setMuted(muted) }

    fun beginTrim(clipId: ClipId, edge: TrimEdge) {
        val source = mutable.value.project?.snapshot?.items?.firstOrNull { it.id == clipId }?.sourceRange ?: return
        mutable.update { it.copy(trimGesture = TrimGestureState(clipId, edge, source, false)) }
    }
    fun updateTrim(sourceTime: TimeUs, snapWords: Boolean = true) {
        val gesture = mutable.value.trimGesture ?: return
        val clip = mutable.value.project?.snapshot?.items?.firstOrNull { it.id == gesture.clipId } ?: return
        val original = requireNotNull(clip.sourceRange)
        var value = sourceTime
        var snapped = false
        if (snapWords) {
            val boundaries = mutable.value.transcript?.words?.filter { it.sourceId == clip.sourceId }?.flatMap { listOf(it.sourceRange.start, it.sourceRange.endExclusive) }.orEmpty()
            boundaries.minByOrNull { kotlin.math.abs(it.value - sourceTime.value) }?.takeIf { kotlin.math.abs(it.value - sourceTime.value) <= 120_000 }?.let { value = it; snapped = true }
        }
        val range = when (gesture.edge) {
            TrimEdge.LEFT -> TimeRangeUs(TimeUs(value.value.coerceIn(original.start.value, original.endExclusive.value - 50_000)), original.endExclusive)
            TrimEdge.RIGHT -> TimeRangeUs(original.start, TimeUs(value.value.coerceIn(original.start.value + 50_000, original.endExclusive.value)))
        }
        mutable.update { it.copy(trimGesture = gesture.copy(previewSourceRange = range, snapped = snapped)) }
    }
    suspend fun commitTrim() { val gesture = mutable.value.trimGesture ?: return; mutable.update { it.copy(trimGesture = null) }; applyManual { manual.trim(requireNotNull(projectId), gesture.clipId, gesture.previewSourceRange) } }
    fun cancelTrim() = mutable.update { it.copy(trimGesture = null) }
    suspend fun splitSelected() { val clip = mutable.value.selection.clipIds.singleOrNull() ?: return; applyManual { manual.split(requireNotNull(projectId), clip, mutable.value.viewport.playheadTime) } }
    suspend fun deleteSelected() { val clip = mutable.value.selection.clipIds.singleOrNull() ?: return; applyManual { manual.delete(requireNotNull(projectId), clip) }; selectClip(null) }
    suspend fun moveSelected(trackId: TrackId, index: Int) { val clip = mutable.value.selection.clipIds.singleOrNull() ?: return; applyManual { manual.move(requireNotNull(projectId), clip, trackId, index) } }
    suspend fun undo() { val seq = mutable.value.project?.snapshot?.sequence?.id ?: return; applyManual { manual.undo(seq) } }
    suspend fun redo() { val seq = mutable.value.project?.snapshot?.sequence?.id ?: return; applyManual { manual.redo(seq) } }

    fun sendAiMessage(text: String) {
        val id = projectId ?: return; if (text.isBlank()) return
        aiJob?.cancel(); val bubble = ChatBubble("user_${System.nanoTime()}", true, text)
        mutable.update { it.copy(aiChat = it.aiChat.copy(stage = AiChatStage.THINKING, messages = it.aiChat.messages + bubble, statusText = "أفهم طلبك…")) }
        aiJob = scope.launch {
            val result = ai.analyzeMessage(id, text, mutable.value.pendingPlan) { progress -> mutable.update { it.copy(aiChat = it.aiChat.copy(stage = progress.stage.toUi(), statusText = progress.userVisibleStatus)) } }
            handleAiResult(result)
        }
    }
    fun cancelAiRequest() { aiJob?.cancel(); mutable.update { it.copy(aiChat = it.aiChat.copy(stage = AiChatStage.CANCELLED, statusText = "أُلغي الطلب")) } }
    suspend fun applyPending() {
        val pending = mutable.value.pendingPlan ?: return; mutable.update { it.copy(aiChat = it.aiChat.copy(stage = AiChatStage.APPLYING, statusText = "أطبق التعديلات…")) }
        when (val result = ai.applyPendingEdit(pending.id)) {
            is AiEditorResult.Applied -> mutable.update { it.copy(project = result.result.project, pendingPlan = null, previewingPending = false, canUndo = true,
                aiChat = it.aiChat.copy(stage = AiChatStage.DONE, statusText = "تم تطبيق ${pending.editPlan.operations.size} تعديلًا")) }
            is AiEditorResult.Failure -> addError(result.messageKey, "تعذر تطبيق الخطة")
            else -> Unit
        }
    }
    suspend fun rejectPending() { mutable.value.pendingPlan?.let { ai.cancelPendingEdit(it.id) }; mutable.update { it.copy(pendingPlan = null, previewingPending = false, aiChat = it.aiChat.copy(stage = AiChatStage.IDLE, statusText = null)) } }
    fun previewPending(enabled: Boolean) {
        val pending = mutable.value.pendingPlan ?: return
        mutable.update { it.copy(previewingPending = enabled) }
        scope.launch { if (enabled) pending.simulationResult.simulatedProject?.let { player.preview(it) } else mutable.value.project?.let { player.preview(it) } }
    }
    fun revisePending(feedback: String) {
        val pending = mutable.value.pendingPlan ?: return; val id = projectId ?: return
        aiJob?.cancel(); aiJob = scope.launch {
            when (val result = ai.revisePendingEdit(id, pending.id, feedback) { progress -> mutable.update { it.copy(aiChat = it.aiChat.copy(stage = progress.stage.toUi(), statusText = progress.userVisibleStatus)) } }) {
                is AiEditorResult.PlanReady -> mutable.update { it.copy(pendingPlan = result.pending, previewingPending = false, aiChat = it.aiChat.copy(stage = AiChatStage.PLAN_READY)) }
                is AiEditorResult.Failure -> addError(result.messageKey, "تعذر تعديل الخطة")
                else -> Unit
            }
        }
    }
    fun searchTranscript(query: String) {
        val words = mutable.value.transcript?.words.orEmpty()
        mutable.update { it.copy(transcriptSearch = TranscriptSearchEngine().search(words, TranscriptSearchQuery(query))) }
    }
    suspend fun seekTranscript(hit: TranscriptSearchHit) {
        val clip = mutable.value.project?.snapshot?.items?.firstOrNull { it.sourceId == hit.sourceId && it.sourceRange?.overlaps(hit.sourceRange) == true }
        if (clip != null) {
            val clipSourceRange = requireNotNull(clip.sourceRange)
            val timeline = clip.timelineStart.value + hit.sourceRange.start.value - clipSourceRange.start.value
            selectClip(clip.id); seek(TimeUs(timeline.coerceAtLeast(0)))
        }
    }
    suspend fun persist() { val value = mutable.value; val id = projectId ?: return; persistence.save(RestoredEditorState(id, value.viewport.playheadTime.value, value.viewport.pixelsPerSecond, value.viewport.scrollOffsetPx, value.selection.clipIds.map { it.value }, value.pendingPlan?.id?.value)) }
    suspend fun close() { persist(); aiJob?.cancel(); player.release() }

    private suspend fun applyManual(block: suspend () -> ManualEditResult) {
        when (val result = block()) {
            is ManualEditResult.Success -> mutable.update { state -> state.copy(project = result.commit.project, canUndo = true, canRedo = true,
                pendingPlan = state.pendingPlan?.let { if (it.baseRevision != result.commit.project.revision) it.copy(status = PendingEditStatus.STALE) else it }, previewingPending = false, autosave = AutosaveState.SAVED) }
            is ManualEditResult.Failure -> addError("manual.edit_failed", result.message)
        }
    }
    private fun handleAiResult(result: AiEditorResult) = when (result) {
        is AiEditorResult.PlanReady -> mutable.update { it.copy(pendingPlan = result.pending, aiChat = it.aiChat.copy(stage = AiChatStage.PLAN_READY, statusText = "الخطة جاهزة")) }
        is AiEditorResult.Analysis -> mutable.update { it.copy(aiChat = it.aiChat.copy(stage = AiChatStage.DONE, statusText = null, messages = it.aiChat.messages + ChatBubble("ai_${System.nanoTime()}", false, result.text))) }
        is AiEditorResult.ConstraintSaved -> mutable.update { it.copy(aiChat = it.aiChat.copy(stage = AiChatStage.DONE, messages = it.aiChat.messages + ChatBubble("ai_${System.nanoTime()}", false, "حفظت القيد: ${result.constraint.text}"))) }
        is AiEditorResult.HistoryChanged -> mutable.update { it.copy(project = result.result.project, aiChat = it.aiChat.copy(stage = AiChatStage.DONE, statusText = if (result.undo) "تم التراجع" else "تمت الإعادة")) }
        is AiEditorResult.Clarification -> mutable.update { it.copy(aiChat = it.aiChat.copy(stage = AiChatStage.DONE, messages = it.aiChat.messages + ChatBubble("ai_${System.nanoTime()}", false, result.question))) }
        is AiEditorResult.Failure -> { if (result.messageKey.contains("provider")) mutable.update { it.copy(aiChat = it.aiChat.copy(stage = AiChatStage.ERROR, providerMissing = true)) }; addError(result.messageKey, result.detail ?: "تعذر إكمال الطلب") }
        AiEditorResult.Cancelled -> mutable.update { it.copy(aiChat = it.aiChat.copy(stage = AiChatStage.CANCELLED)) }
        is AiEditorResult.Applied -> Unit
    }
    private fun addError(key: String, message: String) = mutable.update { it.copy(errors = it.errors + EditorError(key, message)) }
    private fun refreshVisibleMedia() {
        visualJob?.cancel(); visualJob = scope.launch {
            delay(120)
            val current = mutable.value; val project = current.displayProject ?: return@launch
            val visible = TimelineVirtualizer.visibleItems(project.snapshot.items, current.viewport)
            visible.forEach { clip ->
                val sourceId = clip.sourceId ?: return@forEach; val range = clip.sourceRange ?: return@forEach
                mutable.update { it.copy(clipVisuals = it.clipVisuals + (clip.id to (it.clipVisuals[clip.id] ?: ClipVisualUi()).copy(loading = true))) }
                val width = (clip.timelineDuration.value / 1_000_000.0 * current.viewport.pixelsPerSecond).toInt().coerceIn(48, 2_000)
                val thumbs = if (clip.type == TimelineItemType.VIDEO) thumbnails?.visibleThumbnails(sourceId, range, width).orEmpty() else emptyList()
                val wave = if (clip.type in setOf(TimelineItemType.AUDIO, TimelineItemType.MUSIC)) waveforms?.visibleWaveform(sourceId, range, width) else null
                mutable.update { it.copy(clipVisuals = it.clipVisuals + (clip.id to ClipVisualUi(thumbs, wave, false))) }
            }
        }
    }
    private fun TimelineItem.inspectorKind() = when (type) { TimelineItemType.VIDEO -> InspectorKind.VIDEO; TimelineItemType.AUDIO, TimelineItemType.MUSIC -> InspectorKind.AUDIO; TimelineItemType.TEXT, TimelineItemType.IMAGE -> InspectorKind.TEXT }
    private fun AiEditorStage.toUi() = when (this) { AiEditorStage.IDLE -> AiChatStage.IDLE; AiEditorStage.CLASSIFYING, AiEditorStage.BUILDING_CONTEXT -> AiChatStage.THINKING; AiEditorStage.USING_TOOLS -> AiChatStage.USING_TOOLS; AiEditorStage.BUILDING_PLAN, AiEditorStage.REPAIRING_PLAN -> AiChatStage.BUILDING_PLAN; AiEditorStage.SIMULATING -> AiChatStage.SIMULATING; AiEditorStage.PLAN_READY -> AiChatStage.PLAN_READY; AiEditorStage.APPLYING -> AiChatStage.APPLYING; AiEditorStage.DONE -> AiChatStage.DONE; AiEditorStage.ERROR -> AiChatStage.ERROR; AiEditorStage.CANCELLED -> AiChatStage.CANCELLED }
}
