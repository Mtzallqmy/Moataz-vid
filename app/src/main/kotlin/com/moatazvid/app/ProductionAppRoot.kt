package com.moatazvid.app

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.moatazvid.core.ProjectId
import java.text.DateFormat
import java.util.Locale
import kotlinx.coroutines.launch

enum class RootDestination { HOME, EDITOR, SETTINGS, ABOUT, PRIVACY, LICENSES }

@Composable
fun ProductionAppRoot(repository: ProductionProjectRepository) {
    val context = LocalContext.current
    val preferences = remember { UserPreferences(context) }
    var language by remember { mutableStateOf(preferences.language) }
    var destination by remember { mutableStateOf(RootDestination.HOME) }
    var activeProject by remember { mutableStateOf<ProjectId?>(null) }

    LocalizedApp(language) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                when (destination) {
                    RootDestination.HOME -> ProductionHomeScreen(
                        repository = repository,
                        onOpenProject = { activeProject = it; destination = RootDestination.EDITOR },
                        onSettings = { destination = RootDestination.SETTINGS },
                        onAbout = { destination = RootDestination.ABOUT },
                    )
                    RootDestination.EDITOR -> activeProject?.let { projectId ->
                        ProductionEditorScreen(repository, projectId, onBack = { destination = RootDestination.HOME })
                    } ?: LaunchedEffect(Unit) { destination = RootDestination.HOME }
                    RootDestination.SETTINGS -> ProductionSettingsScreen(
                        preferences = preferences,
                        language = language,
                        onLanguageChanged = { value -> preferences.language = value; language = value },
                        onBack = { destination = RootDestination.HOME },
                        onPrivacy = { destination = RootDestination.PRIVACY },
                        onAbout = { destination = RootDestination.ABOUT },
                        onLicenses = { destination = RootDestination.LICENSES },
                    )
                    RootDestination.ABOUT -> AboutScreen(onBack = { destination = RootDestination.HOME }, onLicenses = { destination = RootDestination.LICENSES })
                    RootDestination.PRIVACY -> PrivacyScreen(onBack = { destination = RootDestination.SETTINGS })
                    RootDestination.LICENSES -> LicensesScreen(onBack = { destination = RootDestination.SETTINGS })
                }
            }
        }
    }
}

@Composable
private fun LocalizedApp(language: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val systemConfiguration = LocalConfiguration.current
    val locale = remember(language, systemConfiguration.locales) {
        when (language) {
            UserPreferences.LANGUAGE_ARABIC -> Locale("ar")
            UserPreferences.LANGUAGE_ENGLISH -> Locale.ENGLISH
            else -> systemConfiguration.locales[0] ?: Locale.getDefault()
        }
    }
    val localizedContext = remember(locale, systemConfiguration) {
        val config = Configuration(systemConfiguration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        baseContext.createConfigurationContext(config)
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalLayoutDirection provides if (android.text.TextUtils.getLayoutDirectionFromLocale(locale) == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductionHomeScreen(
    repository: ProductionProjectRepository,
    onOpenProject: (ProjectId) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val projects by repository.observeProjects().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ProductionProjectRepository.ProjectSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ProductionProjectRepository.ProjectSummary?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val project = repository.createProject(defaultProjectTitle(uri))
                repository.importMedia(project, uri)
                project
            }.onSuccess(onOpenProject).onFailure { error = it.message ?: "Import failed" }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, stringResource(R.string.about)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_project)) },
            )
        },
        snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.manual_mode_available), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("video/*", "audio/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.VideoFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.import_video))
                }
            }
            item { Text(stringResource(R.string.recent_projects), style = MaterialTheme.typography.titleMedium) }
            if (projects.isEmpty()) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) { Text(stringResource(R.string.empty_projects), Modifier.padding(20.dp)) }
                }
            } else {
                items(projects, key = { it.id.value }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onRename = { renameTarget = project; renameText = project.title },
                        onDelete = { deleteTarget = project },
                    )
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.new_project)) },
            text = { OutlinedTextField(createName, { createName = it }, label = { Text(stringResource(R.string.project_name)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val name = createName
                    showCreate = false
                    createName = ""
                    scope.launch {
                        busy = true
                        runCatching { repository.createProject(name) }.onSuccess(onOpenProject).onFailure { error = it.message }
                        busy = false
                    }
                }) { Text(stringResource(R.string.open)) }
            },
            dismissButton = { TextButton({ showCreate = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) },
            confirmButton = { TextButton({ scope.launch { runCatching { repository.renameProject(target.id, renameText) }.onFailure { error = it.message }; renameTarget = null } }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ renameTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(target.title) },
            confirmButton = { TextButton({ scope.launch { repository.deleteProject(target.id); deleteTarget = null } }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.import_failed)) }, text = { Text(message) }, confirmButton = { TextButton({ error = null }) { Text("OK") } })
    }
}

@Composable
private fun ProjectCard(
    project: ProductionProjectRepository.ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Movie, null, Modifier.size(42.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(project.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(project.updatedAtEpochMs), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, stringResource(R.string.rename)) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductionSettingsScreen(
    preferences: UserPreferences,
    language: String,
    onLanguageChanged: (String) -> Unit,
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onAbout: () -> Unit,
    onLicenses: () -> Unit,
) {
    var performance by remember { mutableStateOf(preferences.performanceMode) }
    var cloudText by remember { mutableStateOf(preferences.cloudTextAllowed) }
    var vision by remember { mutableStateOf(preferences.visionAllowed) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { TextButton(onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium) }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        UserPreferences.LANGUAGE_SYSTEM to stringResource(R.string.language_system),
                        UserPreferences.LANGUAGE_ARABIC to stringResource(R.string.language_arabic),
                        UserPreferences.LANGUAGE_ENGLISH to stringResource(R.string.language_english),
                    ).forEachIndexed { index, pair ->
                        SegmentedButton(selected = language == pair.first, onClick = { onLanguageChanged(pair.first) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(pair.second) }
                    }
                }
            }
            item { SettingsChoice(stringResource(R.string.performance), performance, listOf("AUTO", "BATTERY_SAVER", "BALANCED", "MAX_PERFORMANCE")) { performance = it; preferences.performanceMode = it } }
            item { HorizontalDivider() }
            item { Text(stringResource(R.string.privacy), style = MaterialTheme.typography.titleMedium) }
            item { ToggleSetting("Cloud text", cloudText) { cloudText = it; preferences.cloudTextAllowed = it } }
            item { ToggleSetting("Vision frames", vision) { vision = it; preferences.visionAllowed = it } }
            item { TextButton(onPrivacy) { Text(stringResource(R.string.privacy)) } }
            item { TextButton(onLicenses) { Text(stringResource(R.string.licenses)) } }
            item { TextButton(onAbout) { Text(stringResource(R.string.about)) } }
        }
    }
}

@Composable
private fun SettingsChoice(title: String, value: String, choices: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title); Text(value, style = MaterialTheme.typography.bodySmall) }
            Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { onSelected(choice); expanded = false }) }
        }
    }
}

@Composable
private fun ToggleSetting(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onChanged)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onBack: () -> Unit, onLicenses: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about)) }, navigationIcon = { TextButton(onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.developed_by))
            Text(stringResource(R.string.location))
            Text(stringResource(R.string.copyright))
            HorizontalDivider()
            Text(stringResource(R.string.local_first))
            Button(onClick = onLicenses) { Text(stringResource(R.string.licenses)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.privacy)) }, navigationIcon = { TextButton(onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.local_first), style = MaterialTheme.typography.bodyLarge) }
            item { Text("Local transcription does not upload audio. Cloud AI receives text/metadata only when you configure and use a provider. Vision frames are allowed only when Vision is enabled. API keys remain local and are excluded from project exports and diagnostics.") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.licenses)) }, navigationIcon = { TextButton(onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("video-use", style = MaterialTheme.typography.titleMedium); Text("Copyright © 2026 Browser Use — MIT License") }
            item { Text("whisper.cpp", style = MaterialTheme.typography.titleMedium); Text("MIT License") }
            item { Text("AndroidX / Media3 / Compose / Room", style = MaterialTheme.typography.titleMedium); Text("Apache License 2.0") }
            item { Text("Kotlin / kotlinx.coroutines / serialization", style = MaterialTheme.typography.titleMedium); Text("Apache License 2.0") }
        }
    }
}

private fun defaultProjectTitle(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported video"
