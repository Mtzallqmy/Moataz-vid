package com.moatazvid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MoatazVidApplication
        setContent {
            val scope = rememberCoroutineScope()
            var result by remember { mutableStateOf<Result<ProductionProjectRepository>?>(null) }

            LaunchedEffect(Unit) {
                result = app.verifiedRepositoryResult()
            }

            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                when (val current = result) {
                    null -> StartupLoadingScreen()
                    else -> {
                        val repository = current.getOrNull()
                        if (repository != null) {
                            ProductionAppRoot(repository)
                        } else {
                            StartupFailureScreen(
                                message = current.exceptionOrNull().toSafeStartupMessage(),
                                onRetry = {
                                    scope.launch {
                                        result = null
                                        result = app.retryVerifiedRepository()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Throwable?.toSafeStartupMessage(): String {
    if (this == null) return "Unknown error"
    val detail = message?.replace(Regex("[\\r\\n]+"), " ")?.trim().orEmpty()
    return if (detail.isBlank()) javaClass.simpleName else "${javaClass.simpleName}: ${detail.take(600)}"
}

@Composable
private fun StartupLoadingScreen() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun StartupFailureScreen(message: String, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.startup_failed), style = MaterialTheme.typography.headlineSmall)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}
