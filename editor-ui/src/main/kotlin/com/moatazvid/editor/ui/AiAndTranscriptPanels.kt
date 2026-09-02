package com.moatazvid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.moatazvid.ai.editor.*
import com.moatazvid.editor.*

@Composable
fun AiChatPanel(
    state: EditorUiState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onPreview: (Boolean) -> Unit,
    onApply: () -> Unit,
    onReject: () -> Unit,
    onRevise: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("مساعد المونتاج", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        Text("Transcript-first · Audio-first · Preview before apply", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.aiChat.providerMissing) ProviderMissingCard()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.aiChat.messages, key = { it.id }) { bubble -> ChatBubbleView(bubble) }
            state.pendingStrategy?.let { strategy ->
                item("strategy_${strategy.id}") {
                    VideoUseStrategyCard(
                        strategy = strategy,
                        onConfirm = { onSend(EditorController.STRATEGY_CONFIRM_COMMAND) },
                        onReject = { onSend(EditorController.STRATEGY_REJECT_COMMAND) },
                    )
                }
            }
            state.pendingPlan?.let { pending -> item("pending_${pending.id.value}") { AiPlanCard(pending, state.previewingPending, onPreview, onApply, onReject) } }
            if (state.aiChat.stage !in setOf(AiChatStage.IDLE, AiChatStage.DONE, AiChatStage.STRATEGY_READY, AiChatStage.PLAN_READY, AiChatStage.ERROR, AiChatStage.CANCELLED)) {
                item("status") { AiStatusIndicator(state.aiChat.stage, state.aiChat.statusText.orEmpty(), onCancel) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                input,
                { input = it },
                Modifier.weight(1f),
                placeholder = { Text(if (state.pendingPlan != null) "عدّل الخطة…" else if (state.pendingStrategy != null) "اكتب توجيهًا جديدًا لتغيير الاستراتيجية…" else "اكتب أمرًا مثل: احذف الصمت") },
                maxLines = 4,
            )
            IconButton(onClick = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    if (state.pendingPlan != null) onRevise(text) else onSend(text)
                    input = ""
                }
            }, enabled = input.isNotBlank(), modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Default.Send, "إرسال")
            }
        }
    }
}

@Composable
private fun VideoUseStrategyCard(
    strategy: PendingEditStrategy,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().semantics { contentDescription = "استراتيجية المونتاج قبل التنفيذ" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountTree, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("استراتيجية المونتاج", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "لن يتم إنشاء أو تطبيق أي قص قبل اعتماد هذه الاستراتيجية.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(strategy.summary, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
            HorizontalDivider()
            Text("سيتم تثبيت القطوع على حدود الكلمات، مع هامش آمن حولها ومعاينة قابلة للتراجع.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onConfirm, modifier = Modifier.weight(1f)) { Text("اعتماد الاستراتيجية") }
                OutlinedButton(onReject) { Text("رفض") }
            }
        }
    }
}

@Composable private fun ChatBubbleView(bubble: ChatBubble) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (bubble.fromUser) Arrangement.Start else Arrangement.End) {
        Surface(color = if (bubble.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 310.dp)) {
            Text(bubble.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
        }
    }
}

@Composable fun AiStatusIndicator(stage: AiChatStage, text: String, cancel: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(text.ifBlank { stage.name }, Modifier.weight(1f)); TextButton(cancel) { Text("إلغاء") }
        }
    }
}

@Composable fun AiPlanCard(pending: PendingEditTransaction, previewing: Boolean, onPreview: (Boolean) -> Unit, onApply: () -> Unit, onReject: () -> Unit) {
    val diff = pending.simulationResult.diff
    ElevatedCard(Modifier.fillMaxWidth().semantics { contentDescription = "خطة تعديل الذكاء الاصطناعي" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("AI Edit", style = MaterialTheme.typography.titleMedium) }
            Text(pending.editPlan.title, style = MaterialTheme.typography.titleSmall)
            diff?.let { Text("المدة: ${formatDuration(it.beforeDuration.value)} → ${formatDuration(it.afterDuration.value)}"); Text("${it.operationCount} تغييرات") }
            pending.editPlan.summary.takeIf(String::isNotBlank)?.let { Text(it) }
            diff?.let {
                if (it.removedRanges.isNotEmpty()) Text("• تقصير/حذف ${it.removedRanges.size} مقاطع")
                if (it.movedClips.isNotEmpty()) Text("• نقل ${it.movedClips.size} مقاطع")
                if (it.addedCaptions > 0) Text("• إضافة ${it.addedCaptions} ترجمات")
            }
            pending.editPlan.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (pending.status == PendingEditStatus.STALE) Text("تغير المشروع. أعد حساب الخطة قبل التطبيق.", color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ onPreview(!previewing) }, enabled = pending.status == PendingEditStatus.READY) { Text(if (previewing) "إيقاف المعاينة" else "معاينة") }
                Button(onApply, enabled = pending.status == PendingEditStatus.READY) { Text("تطبيق") }
                TextButton(onReject) { Text("رفض") }
            }
        }
    }
}

@Composable private fun ProviderMissingCard() { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("لم يتم إعداد مزود ذكاء اصطناعي بعد."); Row { Button({}) { Text("إعداد مزود") }; Spacer(Modifier.width(8.dp)); OutlinedButton({}) { Text("الوظائف المحلية") } } } } }

@Composable fun TranscriptPanel(state: EditorUiState, onSearch: (String) -> Unit, onSeek: (com.moatazvid.speech.TranscriptSearchHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val transcript = state.transcript
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("النص والتوقيت", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(query, { query = it; onSearch(it) }, Modifier.fillMaxWidth().padding(vertical = 8.dp), leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("ابحث داخل الكلام") }, singleLine = true)
        if (transcript == null) {
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("لا يوجد تفريغ صوتي لهذا المشروع."); Button({}) { Text("بدء التفريغ") } } }
        } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (query.isNotBlank()) items(state.transcriptSearch, key = { "${it.sourceId.value}_${it.sourceRange.start.value}" }) { hit ->
                ListItem(headlineContent = { Text(hit.text) }, supportingContent = { Text("${hit.sourceId.value} · ${formatDuration(hit.sourceRange.start.value)}") }, modifier = Modifier.clickable { onSeek(hit) })
            } else items(transcript.segments, key = { it.id.value }) { segment ->
                val active = state.playback.currentTime.value in segment.sourceRange.start.value until segment.sourceRange.endExclusive.value
                ListItem(headlineContent = { Text(segment.text, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) },
                    supportingContent = { Text(formatDuration(segment.sourceRange.start.value)) }, colors = ListItemDefaults.colors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent))
            }
        }
    }
}

private fun formatDuration(us: Long): String { val seconds = us / 1_000_000; return "%02d:%02d".format(seconds / 60, seconds % 60) }
