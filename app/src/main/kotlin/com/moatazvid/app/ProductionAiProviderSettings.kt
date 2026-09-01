package com.moatazvid.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moatazvid.ai.provider.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderSettingsButton(runtime: ProductionAiProviderRuntime) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.SmartToy, "إعدادات الذكاء الاصطناعي") }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            AiProviderSettingsContent(runtime, Modifier.fillMaxWidth().fillMaxHeight(0.86f))
        }
    }
}

@Composable
fun AiProviderSettingsContent(
    runtime: ProductionAiProviderRuntime,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<ProviderProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<ProviderProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var modelTarget by remember { mutableStateOf<ProviderProfile?>(null) }
    var models by remember { mutableStateOf<List<ModelDescriptor>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            profiles = runCatching { runtime.profiles() }.getOrElse {
                message = it.message ?: "تعذر قراءة مزودي الذكاء الاصطناعي"
                emptyList()
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("مزودو الذكاء الاصطناعي", style = MaterialTheme.typography.titleLarge)
                Text("المفاتيح تُشفّر محليًا عبر Android Keystore ولا تُحفظ كنص صريح.", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "تحديث") }
            IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "إضافة مزود") }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (profiles.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("لم يتم إعداد أي مزود بعد.")
                    Button(onClick = { adding = true }) { Text("إضافة OpenAI / OpenRouter / مزود متوافق") }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(profiles, key = { it.id.value }) { profile ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text(profile.type.name + " • " + (profile.defaultModel ?: "لا يوجد نموذج محدد"), style = MaterialTheme.typography.bodySmall)
                                    Text(profile.baseUrl, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                                IconButton(onClick = { editing = profile }) { Icon(Icons.Default.Edit, "تعديل") }
                                IconButton(onClick = {
                                    scope.launch {
                                        runCatching { runtime.deleteProvider(profile.id) }
                                            .onSuccess { refresh() }
                                            .onFailure { message = it.message }
                                    }
                                }) { Icon(Icons.Default.DeleteOutline, "حذف") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        message = "جارٍ اختبار ${profile.displayName}…"
                                        val result = runCatching { runtime.testProvider(profile.id) }.getOrNull()
                                        message = when {
                                            result == null -> "تعذر اختبار المزود"
                                            result.connected -> "تم الاتصال بنجاح خلال ${result.latencyMs} ms"
                                            else -> result.error?.userMessageKey ?: "فشل الاتصال"
                                        }
                                    }
                                }) { Text("اختبار الاتصال") }
                                OutlinedButton(onClick = {
                                    modelTarget = profile
                                    models = emptyList()
                                    loadingModels = true
                                    scope.launch {
                                        when (val result = runtime.models(profile.id)) {
                                            is LlmResult.Success -> models = result.value
                                            is LlmResult.Failure -> message = result.error.userMessageKey
                                        }
                                        loadingModels = false
                                    }
                                }) { Text("اختيار النموذج") }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(it, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }

    if (adding || editing != null) {
        ProviderEditorDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { type, name, baseUrl, apiKey, model ->
                scope.launch {
                    runCatching {
                        runtime.saveProvider(editing?.id, type, name, baseUrl, apiKey, model)
                    }.onSuccess {
                        adding = false
                        editing = null
                        message = "تم حفظ المزود"
                        refresh()
                    }.onFailure { message = it.message ?: "تعذر حفظ المزود" }
                }
            },
        )
    }

    modelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { modelTarget = null },
            title = { Text("اختر نموذجًا لـ ${target.displayName}") },
            text = {
                when {
                    loadingModels -> CircularProgressIndicator()
                    models.isEmpty() -> Text("لم تُرجع نقطة /models أي نماذج. يمكنك كتابة اسم النموذج يدويًا من تعديل المزود.")
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(models, key = { it.id }) { model ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { runtime.assignEditingModel(target.id, model.id) }
                                            .onSuccess {
                                                modelTarget = null
                                                message = "تم تعيين ${model.id} للتحرير والتحليل"
                                                refresh()
                                            }
                                            .onFailure { message = it.message }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(model.displayName)
                                    Text(model.id, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { modelTarget = null }) { Text("إغلاق") } },
        )
    }
}

@Composable
private fun ProviderEditorDialog(
    initial: ProviderProfile?,
    onDismiss: () -> Unit,
    onSave: (ProviderType, String, String?, String?, String?) -> Unit,
) {
    var type by remember(initial?.id?.value) { mutableStateOf(initial?.type ?: ProviderType.OPENAI) }
    var name by remember(initial?.id?.value) { mutableStateOf(initial?.displayName ?: "OpenAI") }
    var baseUrl by remember(initial?.id?.value) { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember(initial?.id?.value) { mutableStateOf("") }
    var model by remember(initial?.id?.value) { mutableStateOf(initial?.defaultModel ?: "") }
    var typeMenu by remember { mutableStateOf(false) }
    val editableBase = type in setOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.CUSTOM)

    LaunchedEffect(type) {
        if (initial == null) {
            when (type) {
                ProviderType.OPENAI -> { name = "OpenAI"; baseUrl = "https://api.openai.com/v1" }
                ProviderType.OPENROUTER -> { name = "OpenRouter"; baseUrl = "https://openrouter.ai/api/v1" }
                ProviderType.HUGGINGFACE -> { name = "Hugging Face"; baseUrl = "https://router.huggingface.co/v1" }
                ProviderType.NVIDIA -> { name = "NVIDIA NIM"; baseUrl = "https://integrate.api.nvidia.com/v1" }
                ProviderType.OPENAI_COMPATIBLE, ProviderType.CUSTOM -> if (!editableBase) baseUrl = ""
                ProviderType.LOCAL -> Unit
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "إضافة مزود" else "تعديل المزود") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(type.name) }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        ProviderType.entries.filter { it != ProviderType.LOCAL }.forEach { value ->
                            DropdownMenuItem(text = { Text(value.name) }, onClick = { type = value; typeMenu = false })
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    baseUrl,
                    { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    enabled = editableBase || initial?.type in setOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.CUSTOM),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    apiKey,
                    { apiKey = it },
                    label = { Text(if (initial == null) "API key" else "API key (اتركه فارغًا للاحتفاظ بالحالي)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("يمكن تغيير النموذج لاحقًا من قائمة /models بعد اختبار الاتصال.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(type, name, baseUrl.takeIf(String::isNotBlank), apiKey.takeIf(String::isNotBlank), model.takeIf(String::isNotBlank)) },
                enabled = name.isNotBlank() && (!editableBase || baseUrl.isNotBlank()),
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
