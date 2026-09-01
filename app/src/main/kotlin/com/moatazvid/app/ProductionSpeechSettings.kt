package com.moatazvid.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moatazvid.core.ProjectId
import com.moatazvid.core.SourceId
import com.moatazvid.speech.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechSettingsButton(
    runtime: ProductionSpeechRuntime,
    repository: ProductionProjectRepository,
    projectId: ProjectId,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.RecordVoiceOver, "التفريغ الصوتي المحلي") }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            SpeechSettingsContent(runtime, repository, projectId, Modifier.fillMaxWidth().fillMaxHeight(0.82f))
        }
    }
}

@Composable
private fun SpeechSettingsContent(
    runtime: ProductionSpeechRuntime,
    repository: ProductionProjectRepository,
    projectId: ProjectId,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val jobs by runtime.jobs.collectAsState(initial = emptyList())
    var pack by remember { mutableStateOf<WhisperModelPack?>(null) }
    var sources by remember { mutableStateOf<List<com.moatazvid.storage.room.MediaSourceEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<ModelInstallProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            pack = runtime.modelPacks().firstOrNull()
            sources = repository.database.mediaDao().sources(projectId.value)
            loading = false
        }
    }

    LaunchedEffect(projectId.value) { refresh() }

    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("التفريغ الصوتي المحلي", style = MaterialTheme.typography.titleLarge)
        Text("يعمل Whisper على الجهاز. لا يُرفع الصوت أو الفيديو إلى مزود سحابي.", style = MaterialTheme.typography.bodySmall)

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val current = pack
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(current?.displayName ?: "Whisper Base Multilingual", style = MaterialTheme.typography.titleMedium)
                    Text("الحالة: ${current?.status ?: ModelPackStatus.NOT_INSTALLED}")
                    Text("الحجم: ${formatBytes(current?.sizeBytes ?: ProductionSpeechRuntime.DEFAULT_MODEL.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                    if (installing) {
                        val progress = installProgress
                        val fraction = if (progress != null && progress.totalBytes > 0) progress.downloadedBytes.toFloat() / progress.totalBytes else 0f
                        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(progress?.let { "${it.stage} • ${(fraction * 100).toInt()}%" } ?: "بدء التنزيل…")
                    } else if (current?.status != ModelPackStatus.INSTALLED) {
                        Button(onClick = {
                            scope.launch {
                                installing = true
                                message = null
                                when (val result = runtime.installDefaultModel { installProgress = it }) {
                                    is SpeechResult.Success -> message = "تم تثبيت نموذج Whisper والتحقق من SHA-256 بنجاح"
                                    is SpeechResult.Failure -> message = "تعذر تثبيت النموذج: ${result.error}"
                                }
                                installing = false
                                refresh()
                            }
                        }) { Text("تنزيل وتثبيت النموذج") }
                    } else {
                        Text("النموذج جاهز للتفريغ دون إنترنت بعد التثبيت.", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Text("وسائط المشروع", style = MaterialTheme.typography.titleMedium)
            if (sources.isEmpty()) {
                Text("استورد فيديو أو ملفًا صوتيًا أولًا.")
            } else {
                sources.forEach { source ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(source.displayName, maxLines = 1)
                                Text(source.mimeType, style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                enabled = current?.status == ModelPackStatus.INSTALLED,
                                onClick = {
                                    scope.launch {
                                        message = when (val result = runtime.queueTranscription(SourceId(source.sourceId), LanguageCode.AUTO)) {
                                            is SpeechResult.Success -> "تمت إضافة ${source.displayName} إلى التفريغ المحلي"
                                            is SpeechResult.Failure -> "تعذر بدء التفريغ: ${result.error}"
                                        }
                                    }
                                },
                            ) { Text("تفريغ") }
                        }
                    }
                }
            }

            if (jobs.isNotEmpty()) {
                Text("المهام", style = MaterialTheme.typography.titleMedium)
                jobs.take(8).forEach { job ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(job.statusText, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("${job.progressPermille / 10}%", style = MaterialTheme.typography.labelSmall)
                        }
                        LinearProgressIndicator(progress = { job.progressPermille / 1000f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        message?.let {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(it, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> "%.2f GB".format(value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
    else -> "${value / 1024} KB"
}
