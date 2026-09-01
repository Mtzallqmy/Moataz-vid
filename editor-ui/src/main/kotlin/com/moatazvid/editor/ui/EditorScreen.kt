@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moatazvid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moatazvid.ai.editor.PendingEditStatus
import com.moatazvid.core.*
import com.moatazvid.editor.*
import kotlin.math.roundToInt

@Composable
fun MoatazProjectEditor(viewModel: EditorViewModel, previewSurface: @Composable BoxScope.() -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoatazVidTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            EditorScreen(state, previewSurface, viewModel::playPause, viewModel::select, viewModel::split, viewModel::delete,
                viewModel::undo, viewModel::redo, viewModel::send, viewModel::cancelAi, viewModel::previewPending,
                viewModel::applyPending, viewModel::rejectPending, viewModel::revise, viewModel::searchTranscript,
                viewModel::seekTranscript, viewModel::zoom, viewModel::beginTrim, viewModel::updateTrim, viewModel::commitTrim, viewModel::move)
        }
    }
}

@Composable
fun EditorScreen(
    state: EditorUiState,
    previewSurface: @Composable BoxScope.() -> Unit,
    onPlayPause: () -> Unit,
    onSelect: (ClipId?) -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSend: (String) -> Unit,
    onCancelAi: () -> Unit,
    onPreviewPending: (Boolean) -> Unit,
    onApplyPending: () -> Unit,
    onRejectPending: () -> Unit,
    onRevise: (String) -> Unit,
    onTranscriptSearch: (String) -> Unit,
    onTranscriptSeek: (com.moatazvid.speech.TranscriptSearchHit) -> Unit,
    onZoom: (Double, Double) -> Unit,
    onTrimStart: (ClipId, TrimEdge) -> Unit,
    onTrimUpdate: (TimeUs) -> Unit,
    onTrimCommit: () -> Unit,
    onMove: (ClipId, TrackId, Int) -> Unit,
) {
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    var inspectorOpen by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val large = maxWidth >= 840.dp
        if (state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "تحميل المشروع" }) }
        else if (large) Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) { PreviewSection(state, previewSurface, onPlayPause); ManualToolbar(state, onSplit, onDelete, onUndo, onRedo); TimelineView(state, onSelect, onZoom, onTrimStart, onTrimUpdate, onTrimCommit, onMove, Modifier.weight(1f)) }
            Surface(Modifier.width(360.dp).fillMaxHeight(), tonalElevation = 3.dp) { when { chatOpen -> AiChatPanel(state, onSend, onCancelAi, onPreviewPending, onApplyPending, onRejectPending, onRevise); inspectorOpen -> InspectorSheet(state); else -> TranscriptPanel(state, onTranscriptSearch, onTranscriptSeek) } }
        } else Column(Modifier.fillMaxSize()) {
            PreviewSection(state, previewSurface, onPlayPause)
            ManualToolbar(state, onSplit, onDelete, onUndo, onRedo)
            TimelineView(state, onSelect, onZoom, onTrimStart, onTrimUpdate, onTrimCommit, onMove, Modifier.weight(1f))
            NavigationBar {
                NavigationBarItem(selected = transcriptOpen, onClick = { transcriptOpen = !transcriptOpen; chatOpen = false; inspectorOpen = false }, icon = { Icon(Icons.Default.Subtitles, null) }, label = { Text("النص") })
                NavigationBarItem(selected = chatOpen, onClick = { chatOpen = !chatOpen; transcriptOpen = false; inspectorOpen = false }, icon = { Icon(Icons.Default.AutoAwesome, null) }, label = { Text("AI") })
                NavigationBarItem(selected = inspectorOpen, onClick = { inspectorOpen = !inspectorOpen; chatOpen = false; transcriptOpen = false }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("الخصائص") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Default.IosShare, null) }, label = { Text("تصدير") })
            }
        }
        if (!large && chatOpen) ModalBottomSheet(onDismissRequest = { chatOpen = false }, modifier = Modifier.fillMaxHeight(0.82f)) {
            AiChatPanel(state, onSend, onCancelAi, onPreviewPending, onApplyPending, onRejectPending, onRevise)
        }
        if (!large && transcriptOpen) ModalBottomSheet(onDismissRequest = { transcriptOpen = false }, modifier = Modifier.fillMaxHeight(0.75f)) {
            TranscriptPanel(state, onTranscriptSearch, onTranscriptSeek)
        }
        if (!large && inspectorOpen) ModalBottomSheet(onDismissRequest = { inspectorOpen = false }, modifier = Modifier.fillMaxHeight(0.55f)) { InspectorSheet(state) }
        state.errors.lastOrNull()?.let { error -> ErrorBanner(error, Modifier.align(Alignment.TopCenter)) }
        BackgroundJobs(state.jobs, Modifier.align(Alignment.TopStart))
    }
}

@Composable private fun PreviewSection(state: EditorUiState, surface: @Composable BoxScope.() -> Unit, onPlayPause: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().aspectRatio(state.displayProject?.snapshot?.sequence?.let { it.canvasWidth.toFloat() / it.canvasHeight } ?: 16f / 9f).semantics { contentDescription = "معاينة الفيديو" }, contentAlignment = Alignment.Center) {
            surface(); if (state.displayProject == null) Text("لا توجد معاينة", color = Color.White)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayPause) { Icon(if (state.playback.status == PlaybackStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow, "تشغيل أو إيقاف", tint = Color.White) }
            Text(formatTime(state.playback.currentTime.value), color = Color.White, modifier = Modifier.semantics { contentDescription = "الوقت ${formatTime(state.playback.currentTime.value)}" })
            LinearProgressIndicator(progress = { if (state.playback.duration.value == 0L) 0f else state.playback.currentTime.value.toFloat() / state.playback.duration.value }, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(formatTime(state.playback.duration.value), color = Color.White)
        }
    }
}

@Composable private fun ManualToolbar(state: EditorUiState, split: () -> Unit, delete: () -> Unit, undo: () -> Unit, redo: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AssistChip(split, { Text("تقسيم") }, leadingIcon = { Icon(Icons.Default.ContentCut, null) }, enabled = state.selection.clipIds.size == 1)
        AssistChip(delete, { Text("حذف") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, enabled = state.selection.clipIds.isNotEmpty())
        AssistChip(undo, { Text("تراجع") }, leadingIcon = { Icon(Icons.Default.Undo, null) }, enabled = state.canUndo)
        AssistChip(redo, { Text("إعادة") }, leadingIcon = { Icon(Icons.Default.Redo, null) }, enabled = state.canRedo)
        state.autosave.takeIf { it != AutosaveState.IDLE }?.let { Text(if (it == AutosaveState.SAVING) "جارٍ الحفظ…" else "محفوظ", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable fun TimelineView(state: EditorUiState, onSelect: (ClipId?) -> Unit, onZoom: (Double, Double) -> Unit,
    onTrimStart: (ClipId, TrimEdge) -> Unit, onTrimUpdate: (TimeUs) -> Unit, onTrimCommit: () -> Unit,
    onMove: (ClipId, TrackId, Int) -> Unit, modifier: Modifier = Modifier) {
    val project = state.displayProject ?: return Box(modifier) {}
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(Unit) {
        detectTransformGestures { centroid, _, zoom, _ -> if (zoom != 1f) onZoom(zoom.toDouble(), centroid.x.toDouble()) }
    }) {
        LazyColumn(Modifier.fillMaxSize().padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(project.snapshot.tracks, key = { it.id.value }) { track -> TimelineTrack(track, project.snapshot.items.filter { it.trackId == track.id }, state.viewport, state.selection.clipIds, state.clipVisuals, onSelect, onTrimStart, onTrimUpdate, onTrimCommit, onMove) }
        }
        TimeRuler(state.viewport, Modifier.fillMaxWidth().height(28.dp))
        Box(Modifier.width(2.dp).fillMaxHeight().align(Alignment.Center).background(MaterialTheme.colorScheme.error).semantics { contentDescription = "مؤشر التشغيل" })
        if (state.pendingPlan?.status == PendingEditStatus.STALE) Text("تغير المشروع بعد إنشاء الخطة — أعد حسابها", Modifier.align(Alignment.TopCenter).background(MaterialTheme.colorScheme.errorContainer).padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable private fun TimelineTrack(track: Track, clips: List<TimelineItem>, viewport: TimelineViewportState, selected: Set<ClipId>, visuals: Map<ClipId, ClipVisualUi>, onSelect: (ClipId?) -> Unit,
    onTrimStart: (ClipId, TrimEdge) -> Unit, onTrimUpdate: (TimeUs) -> Unit, onTrimCommit: () -> Unit, onMove: (ClipId, TrackId, Int) -> Unit) {
    val height = when (track.type) { TrackType.VIDEO -> 76.dp; TrackType.AUDIO, TrackType.MUSIC -> 56.dp; TrackType.CAPTION, TrackType.OVERLAY -> 44.dp }
    Row(Modifier.fillMaxWidth().height(height)) {
        Text(track.type.name, Modifier.width(54.dp).padding(4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(clips, key = { _, item -> item.id.value }) { index, clip -> ClipBlock(clip, visuals[clip.id], viewport, clip.id in selected, onSelect, onTrimStart, onTrimUpdate, onTrimCommit,
                { delta -> val widthPx = (clip.timelineDuration.value / 1_000_000.0 * viewport.pixelsPerSecond).coerceAtLeast(48.0); val target = (index + (delta / widthPx).roundToInt()).coerceIn(0, clips.lastIndex); if (target != index) onMove(clip.id, track.id, target) }, height) }
        }
    }
}

@Composable private fun ClipBlock(clip: TimelineItem, visual: ClipVisualUi?, viewport: TimelineViewportState, selected: Boolean, onSelect: (ClipId?) -> Unit,
    onTrimStart: (ClipId, TrimEdge) -> Unit, onTrimUpdate: (TimeUs) -> Unit, onTrimCommit: () -> Unit,
    onMoveEnd: (Float) -> Unit, height: Dp) {
    val width = (clip.timelineDuration.value / 1_000_000.0 * viewport.pixelsPerSecond).roundToInt().coerceAtLeast(48).dp
    val color = when (clip.type) { TimelineItemType.VIDEO -> MoatazColors.Video; TimelineItemType.AUDIO, TimelineItemType.MUSIC -> MoatazColors.Audio; TimelineItemType.TEXT, TimelineItemType.IMAGE -> MoatazColors.Overlay }
    var dragPx by remember(clip.id) { mutableFloatStateOf(0f) }
    Box(Modifier.width(width).height(height).padding(2.dp).clip(RoundedCornerShape(6.dp)).background(color).clickable { onSelect(clip.id) }
        .pointerInput(clip.id) { detectDragGesturesAfterLongPress(onDragStart = { dragPx = 0f; onSelect(clip.id) }, onDragEnd = { onMoveEnd(dragPx); dragPx = 0f }, onDragCancel = { dragPx = 0f }, onDrag = { change, amount -> change.consume(); dragPx += amount.x }) }
        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)) else Modifier)
        .semantics { contentDescription = "مقطع ${clip.id.value}، المدة ${formatTime(clip.timelineDuration.value)}" }) {
        if (clip.type == TimelineItemType.VIDEO && visual?.thumbnailRefs?.isNotEmpty() == true) Row(Modifier.matchParentSize()) {
            visual.thumbnailRefs.take(8).forEachIndexed { index, _ -> Box(Modifier.weight(1f).fillMaxHeight().background(if (index % 2 == 0) Color.White.copy(alpha = .08f) else Color.Black.copy(alpha = .08f))) }
        }
        val waveform = visual?.waveform
        if (waveform != null) Canvas(Modifier.matchParentSize().padding(4.dp)) {
            if (waveform.isNotEmpty()) waveform.forEachIndexed { index, amplitude ->
                val x = index.toFloat() / waveform.size * size.width; val half = amplitude.coerceIn(0f, 1f) * size.height / 2; drawLine(Color.White.copy(alpha = .85f), Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), 1.dp.toPx())
            }
        }
        if (visual?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        Text(clip.sourceId?.value ?: clip.type.name, Modifier.padding(8.dp), color = Color.White, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        val sourceRange = clip.sourceRange
        if (selected && sourceRange != null) {
            TrimHandle(clip, TrimEdge.LEFT, sourceRange.start, viewport, onTrimStart, onTrimUpdate, onTrimCommit, Modifier.align(Alignment.CenterStart))
            TrimHandle(clip, TrimEdge.RIGHT, sourceRange.endExclusive, viewport, onTrimStart, onTrimUpdate, onTrimCommit, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable private fun TrimHandle(clip: TimelineItem, edge: TrimEdge, initial: TimeUs, viewport: TimelineViewportState,
    onStart: (ClipId, TrimEdge) -> Unit, onUpdate: (TimeUs) -> Unit, onCommit: () -> Unit, modifier: Modifier) {
    var accumulatedPx by remember(clip.id, edge) { mutableFloatStateOf(0f) }
    Box(modifier.width(24.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.78f))
        .pointerInput(clip.id, edge, viewport.pixelsPerSecond) { detectDragGestures(
            onDragStart = { accumulatedPx = 0f; onStart(clip.id, edge) },
            onDragEnd = onCommit,
            onDragCancel = { accumulatedPx = 0f },
            onDrag = { change, amount -> change.consume(); accumulatedPx += amount.x; val deltaUs = (accumulatedPx / viewport.pixelsPerSecond * 1_000_000).toLong(); onUpdate(TimeUs((initial.value + deltaUs).coerceAtLeast(0))) },
        ) }.semantics { contentDescription = if (edge == TrimEdge.LEFT) "قص بداية المقطع" else "قص نهاية المقطع" })
}

@Composable private fun TimeRuler(viewport: TimelineViewportState, modifier: Modifier) {
    Canvas(modifier.background(MaterialTheme.colorScheme.surface)) {
        val intervalSeconds = when { viewport.pixelsPerSecond < 12 -> 30; viewport.pixelsPerSecond < 40 -> 10; viewport.pixelsPerSecond < 120 -> 2; else -> 1 }
        val startSecond = (viewport.visibleRange.start.value / 1_000_000 / intervalSeconds) * intervalSeconds
        var second = startSecond
        while (second * 1_000_000L < viewport.visibleRange.endExclusive.value) {
            val x = viewport.xFor(TimeUs(second * 1_000_000L)).toFloat(); drawLine(Color.Gray, Offset(x, size.height), Offset(x, size.height / 2), strokeWidth = 1.dp.toPx()); second += intervalSeconds
        }
    }
}

@Composable private fun ErrorBanner(error: EditorError, modifier: Modifier = Modifier) { Surface(modifier.padding(12.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), shadowElevation = 5.dp) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ErrorOutline, null); Spacer(Modifier.width(8.dp)); Text(error.message); error.recoverAction?.let { TextButton({}) { Text(it) } } } } }
@Composable private fun BackgroundJobs(jobs: List<BackgroundJobUiState>, modifier: Modifier = Modifier) { Column(modifier.padding(8.dp).widthIn(max = 240.dp)) { jobs.forEach { job -> ElevatedCard(Modifier.padding(2.dp)) { Column(Modifier.padding(8.dp)) { Text(job.statusText, style = MaterialTheme.typography.labelMedium); LinearProgressIndicator(progress = { job.progressPermille / 1000f }, Modifier.fillMaxWidth()) } } } } }
@Composable private fun InspectorSheet(state: EditorUiState) {
    val clip = state.project?.snapshot?.items?.firstOrNull { it.id == state.inspector.selectedClipId }
    val properties = clip?.let { state.project?.clipProperties?.get(it.id) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("الخصائص", style = MaterialTheme.typography.titleLarge)
        if (clip == null) Text("حدد مقطعًا لعرض خصائصه") else {
            Text("المصدر: ${clip.sourceId?.value ?: clip.type.name}")
            Text("المدة: ${formatTime(clip.timelineDuration.value)}")
            if (state.inspector.kind in setOf(InspectorKind.VIDEO, InspectorKind.AUDIO)) { Text("السرعة: ${properties?.speed ?: 1.0}×"); Text("الصوت: ${properties?.gainDb ?: 0f} dB") }
            if (state.inspector.kind == InspectorKind.VIDEO) { Text("Crop: ${properties?.cropAspectRatio ?: "أصلي"}"); Text("Opacity / Color / Transform", style = MaterialTheme.typography.labelMedium) }
            if (state.inspector.kind == InspectorKind.TEXT) Text("النص والموضع والحجم والشفافية")
        }
    }
}
private fun formatTime(us: Long): String { val total = us / 1_000_000; return "%02d:%02d.%02d".format(total / 60, total % 60, (us / 10_000) % 100) }
