package com.moatazvid.editor

import com.moatazvid.ai.editor.*
import com.moatazvid.core.*
import com.moatazvid.speech.*

data class TimelineViewportState(
    val pixelsPerSecond: Double = 64.0,
    val scrollOffsetPx: Double = 0.0,
    val viewportWidthPx: Double = 1.0,
    val playheadTime: TimeUs = TimeUs(0),
    val selectedItems: Set<ClipId> = emptySet(),
) {
    init { require(pixelsPerSecond in 4.0..2_000.0); require(scrollOffsetPx >= 0 && viewportWidthPx > 0) }
    val visibleRange: TimeRangeUs get() {
        val start = (scrollOffsetPx / pixelsPerSecond * 1_000_000).toLong().coerceAtLeast(0)
        val duration = (viewportWidthPx / pixelsPerSecond * 1_000_000).toLong().coerceAtLeast(1)
        return TimeRangeUs(TimeUs(start), TimeUs(start + duration))
    }
    fun zoomBy(factor: Double, focalPointPx: Double): TimelineViewportState {
        val old = pixelsPerSecond; val next = (old * factor).coerceIn(4.0, 2_000.0)
        val focalTimeSeconds = (scrollOffsetPx + focalPointPx) / old
        val nextOffset = (focalTimeSeconds * next - focalPointPx).coerceAtLeast(0.0)
        return copy(pixelsPerSecond = next, scrollOffsetPx = nextOffset)
    }
    fun xFor(time: TimeUs): Double = time.value / 1_000_000.0 * pixelsPerSecond - scrollOffsetPx
    fun timeFor(x: Double): TimeUs = TimeUs(((scrollOffsetPx + x).coerceAtLeast(0.0) / pixelsPerSecond * 1_000_000).toLong())
}

enum class PlaybackStatus { IDLE, READY, PLAYING, PAUSED, BUFFERING, ENDED, ERROR }
enum class PreviewQuality { AUTO, LOW, MEDIUM, HIGH }
data class PlaybackUiState(val status: PlaybackStatus = PlaybackStatus.IDLE, val currentTime: TimeUs = TimeUs(0), val duration: DurationUs = DurationUs(0), val muted: Boolean = false, val quality: PreviewQuality = PreviewQuality.AUTO)

data class EditorSelectionContext(val clipIds: Set<ClipId>, val timelineRange: TimeRangeUs?, val selectedTranscriptWordIds: Set<TranscriptWordId> = emptySet())
enum class InspectorKind { NONE, VIDEO, AUDIO, TEXT, CAPTION, IMAGE, TRANSITION }
data class InspectorUiState(
    val kind: InspectorKind = InspectorKind.NONE,
    val visible: Boolean = false,
    val selectedClipId: ClipId? = null,
    val selectedTransitionId: String? = null,
)

enum class AiChatStage { IDLE, THINKING, USING_TOOLS, BUILDING_PLAN, SIMULATING, PLAN_READY, APPLYING, DONE, ERROR, CANCELLED }
data class ChatBubble(val id: String, val fromUser: Boolean, val text: String, val streaming: Boolean = false, val error: Boolean = false)
data class AiChatUiState(
    val stage: AiChatStage = AiChatStage.IDLE,
    val statusText: String? = null,
    val messages: List<ChatBubble> = emptyList(),
    val providerMissing: Boolean = false,
    val transcriptRequired: Boolean = false,
    val expanded: Boolean = false,
)

enum class BackgroundJobType { TRANSCRIPTION, PROXY, EXPORT, THUMBNAILS, WAVEFORM }
data class BackgroundJobUiState(val id: String, val type: BackgroundJobType, val progressPermille: Int, val statusText: String, val cancellable: Boolean)
enum class AutosaveState { IDLE, SAVING, SAVED, ERROR }
data class EditorError(val key: String, val message: String, val recoverAction: String? = null)
data class TrimGestureState(val clipId: ClipId, val edge: TrimEdge, val previewSourceRange: TimeRangeUs, val snapped: Boolean)
enum class TrimEdge { LEFT, RIGHT }
data class ClipVisualUi(val thumbnailRefs: List<String> = emptyList(), val waveform: FloatArray? = null, val loading: Boolean = false)

data class EditorUiState(
    val loading: Boolean = true,
    val project: AiEditableProject? = null,
    val viewport: TimelineViewportState = TimelineViewportState(),
    val playback: PlaybackUiState = PlaybackUiState(),
    val selection: EditorSelectionContext = EditorSelectionContext(emptySet(), null),
    val inspector: InspectorUiState = InspectorUiState(),
    val aiChat: AiChatUiState = AiChatUiState(),
    val pendingPlan: PendingEditTransaction? = null,
    val previewingPending: Boolean = false,
    val trimGesture: TrimGestureState? = null,
    val clipVisuals: Map<ClipId, ClipVisualUi> = emptyMap(),
    val transcript: TranscriptBundle? = null,
    val transcriptSearch: List<TranscriptSearchHit> = emptyList(),
    val jobs: List<BackgroundJobUiState> = emptyList(),
    val errors: List<EditorError> = emptyList(),
    val autosave: AutosaveState = AutosaveState.IDLE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val displayProject: AiEditableProject? get() = if (previewingPending) pendingPlan?.simulationResult?.simulatedProject ?: project else project
}

data class RestoredEditorState(val projectId: ProjectId, val playheadUs: Long, val pixelsPerSecond: Double, val scrollOffsetPx: Double, val selectedClipIds: List<String>, val pendingEditId: String?)
