package com.moatazvid.app

import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.moatazvid.ai.editor.AiContextBuilder
import com.moatazvid.ai.editor.AiEditorEngine
import com.moatazvid.core.ProjectId
import com.moatazvid.editor.EditorController
import com.moatazvid.editor.ManualEditService
import com.moatazvid.editor.ui.EditorViewModel
import com.moatazvid.editor.ui.MoatazProjectEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionEditorScreen(
    repository: ProductionProjectRepository,
    projectId: ProjectId,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as MoatazVidApplication
    val aiRuntime = remember { application.aiProviders }
    val uiScope = rememberCoroutineScope()
    val surfaceView = remember(projectId.value) { SurfaceView(context).apply { keepScreenOn = true } }
    val runtimeScope = remember(projectId.value) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val store = remember(projectId.value) { ProductionAiTimelineStore(repository) }
    val gateway = remember(projectId.value) { ProductionEditorGateway(repository) }
    val player = remember(projectId.value) { ProductionEditorPlayer(context, repository, surfaceView, runtimeScope) }
    val persistence = remember(projectId.value) { SharedPreferencesEditorStatePersistence(context) }
    val aiData = remember(projectId.value) { ProductionAiDataSource(repository) }
    val ai = remember(projectId.value, aiRuntime) {
        AiEditorEngine(
            data = aiData,
            store = store,
            contextBuilder = AiContextBuilder(aiData),
            modelResolver = aiRuntime.modelResolver,
            proposalClient = aiRuntime.proposalClient,
        )
    }
    val manual = remember(projectId.value) { ManualEditService(store) }
    val controller = remember(projectId.value) {
        EditorController(
            gateway = gateway,
            player = player,
            manual = manual,
            ai = ai,
            persistence = persistence,
            scope = runtimeScope,
        )
    }
    val viewModel = remember(projectId.value) { EditorViewModel(controller) }
    val exporter = remember(projectId.value) { ProductionVideoExporter(context, repository) }
    var exporting by remember { mutableStateOf(false) }
    var exportPercent by remember { mutableStateOf<Double?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var projectTitle by remember { mutableStateOf("Moataz-vid") }

    LaunchedEffect(projectId.value) {
        repository.loadEditableProject(projectId)?.let { projectTitle = it.snapshot.project.title }
        viewModel.open(projectId)
    }

    DisposableEffect(projectId.value) {
        onDispose {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                controller.close()
                runtimeScope.cancel()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destination ->
        if (destination == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            exporting = true
            exportPercent = 0.0
            exportMessage = null
            exporter.export(projectId, destination) { progress -> exportPercent = progress.percent }
                .onSuccess { exportMessage = "تم تصدير الفيديو والتحقق منه بنجاح" }
                .onFailure { exportMessage = it.message ?: "تعذر تصدير الفيديو" }
            exporting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        uiScope.launch {
                            controller.close()
                            onBack()
                        }
                    }) { Icon(Icons.Default.ArrowBack, "رجوع") }
                },
                actions = {
                    AiProviderSettingsButton(aiRuntime)
                    IconButton(
                        enabled = !exporting,
                        onClick = { exportLauncher.launch(safeExportName(projectTitle)) },
                    ) { Icon(Icons.Default.IosShare, "تصدير") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (exporting) {
                LinearProgressIndicator(
                    progress = { ((exportPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = exportPercent?.let { "جارٍ التصدير ${it.toInt()}%" } ?: "جارٍ التصدير…",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            exportMessage?.let { message ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MoatazProjectEditor(viewModel) {
                    AndroidView(
                        factory = { surfaceView },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun safeExportName(title: String): String {
    val clean = title.replace(Regex("[\\/:*?\"<>|]"), "-").trim().take(80).ifBlank { "Moataz-vid" }
    return "$clean.mp4"
}
