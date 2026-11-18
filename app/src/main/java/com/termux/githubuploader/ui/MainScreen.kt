package com.termux.githubuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.githubuploader.AppScreen
import com.termux.githubuploader.UiState
import com.termux.githubuploader.UploadViewModel
import com.termux.githubuploader.ui.theme.GitHubGreen
import com.termux.githubuploader.ui.theme.TextMuted
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    viewModel: UploadViewModel,
    screen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,
    onPickFolder: () -> Unit,
    onPickArchive: () -> Unit,
    onPickArchives: () -> Unit
) {
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.toast?.id) {
        val toast = state.toast ?: return@LaunchedEffect
        snackbarHost.showSnackbar(toast.message)
        viewModel.toastShown()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHost) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(10.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (screen) {
                                AppScreen.LOG -> "Log upload"
                                AppScreen.SCRIPT -> "upload.sh asli"
                                AppScreen.HELP -> "Panduan"
                                else -> "Upload GitHub"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        if (screen == AppScreen.MAIN) {
                            Text("upload.sh v2.0 untuk Android", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                },
                navigationIcon = {
                    if (screen != AppScreen.MAIN) {
                        IconButton(onClick = { onScreenChange(AppScreen.MAIN) }) {
                            Icon(Icons.Filled.Close, contentDescription = "kembali")
                        }
                    }
                },
                actions = {
                    if (screen == AppScreen.MAIN) {
                        IconButton(onClick = { onScreenChange(AppScreen.SCRIPT) }) {
                            Icon(Icons.Filled.Info, contentDescription = "lihat script")
                        }
                        IconButton(onClick = { onScreenChange(AppScreen.LOG) }) {
                            Icon(Icons.Filled.History, contentDescription = "log")
                        }
                        IconButton(onClick = { onScreenChange(AppScreen.HELP) }) {
                            Icon(Icons.Filled.Menu, contentDescription = "panduan")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                AppScreen.MAIN -> UploadTab(
                    state = state,
                    vm = viewModel,
                    onPickFolder = onPickFolder,
                    onPickArchive = onPickArchive,
                    onPickArchives = onPickArchives,
                    onOpenLog = { onScreenChange(AppScreen.LOG) }
                )
                AppScreen.LOG -> LogScreen(state, viewModel)
                AppScreen.SCRIPT -> ScriptScreen(viewModel)
                AppScreen.HELP -> HelpScreen()
            }
        }
    }

    if (state.tokenDialogOpen) {
        TokenDialog(
            account = state.account,
            checking = state.checkingToken,
            onSave = { viewModel.saveToken(it) { ok -> if (ok) viewModel.closeTokenDialog() } },
            onLogout = {
                viewModel.logout()
                viewModel.closeTokenDialog()
            },
            onDismiss = { viewModel.closeTokenDialog() }
        )
    }

    state.pendingSecrets?.let { pending ->
        SecretsDialog(
            pending = pending,
            onSkip = { viewModel.answerSecrets(true) },
            onKeep = { viewModel.answerSecrets(false) },
            onCancel = { viewModel.cancelSecrets() }
        )
    }
}
