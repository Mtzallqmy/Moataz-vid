package com.moatazvid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MoatazVidRoot() }
    }
}

private enum class RootPage { HOME, SETTINGS, ABOUT, PRIVACY }

@Composable
private fun MoatazVidRoot() {
    var page by remember { mutableStateOf(RootPage.HOME) }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            when (page) {
                RootPage.HOME -> HomeScreen(
                    onSettings = { page = RootPage.SETTINGS },
                    onAbout = { page = RootPage.ABOUT },
                )
                RootPage.SETTINGS -> SettingsScreen(
                    onBack = { page = RootPage.HOME },
                    onPrivacy = { page = RootPage.PRIVACY },
                    onAbout = { page = RootPage.ABOUT },
                )
                RootPage.ABOUT -> AboutScreen(onBack = { page = RootPage.HOME })
                RootPage.PRIVACY -> PrivacyScreen(onBack = { page = RootPage.SETTINGS })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(onSettings: () -> Unit, onAbout: () -> Unit) {
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
            ExtendedFloatingActionButton(onClick = {}, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.new_project)) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.manual_mode_available), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Default.VideoFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.import_video))
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.recent_projects), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.empty_projects), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit, onPrivacy: () -> Unit, onAbout: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { TextButton(onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SettingsRow(stringResource(R.string.language), stringResource(R.string.language_system)) }
            item { SettingsRow(stringResource(R.string.performance), "AUTO") }
            item { SettingsRow(stringResource(R.string.ai_providers), stringResource(R.string.manual_mode_available)) }
            item { SettingsRow(stringResource(R.string.local_speech), "Model packs") }
            item { SettingsRow(stringResource(R.string.storage), "Cache & models") }
            item { HorizontalDivider() }
            item { TextButton(onPrivacy) { Text(stringResource(R.string.privacy)) } }
            item { TextButton(onAbout) { Text(stringResource(R.string.about)) } }
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(value, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about)) }, navigationIcon = { TextButton(onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.developed_by))
            Text(stringResource(R.string.location))
            Text(stringResource(R.string.copyright))
            HorizontalDivider()
            Text(stringResource(R.string.local_first))
            Text(stringResource(R.string.licenses), style = MaterialTheme.typography.titleMedium)
            Text("video-use — Browser Use — MIT\nwhisper.cpp — MIT\nAndroidX / Media3 / Compose / Room — Apache-2.0")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.privacy)) }, navigationIcon = { TextButton(onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.local_first), style = MaterialTheme.typography.bodyLarge)
            Text("Local media processing does not require a Moataz vid backend. When you configure a cloud AI provider, transcript text and metadata may be sent to that provider for the request you initiate. Vision frames are sent only for workflows where you explicitly allow vision. API credentials stay on the device and are never written to project exports.")
        }
    }
}
