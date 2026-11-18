package com.termux.githubuploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.githubuploader.core.Names
import com.termux.githubuploader.core.TargetKind
import com.termux.githubuploader.ui.MainScreen
import com.termux.githubuploader.ui.theme.GitHubUploaderTheme

enum class AppScreen { MAIN, LOG, SCRIPT, HELP }

class MainActivity : ComponentActivity() {

    private val viewModel: UploadViewModel by viewModels()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri)
            viewModel.addTargets(listOf(uri), TargetKind.FOLDER)
        }
    }

    /** Satu arsip saja: tetap bisa dipakai walau file manager tidak mendukung multi-pilih. */
    private val pickArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = displayName(uri)
        if (!Names.isArchive(name)) {
            viewModel.toast("Format tidak didukung: $name")
            return@registerForActivityResult
        }
        persist(uri)
        viewModel.addTargets(listOf(uri), TargetKind.ARCHIVE)
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val archives = uris.filter { Names.isArchive(displayName(it)) }
        val dropped = uris.size - archives.size
        if (dropped > 0) {
            viewModel.toast("$dropped file dilewati: hanya .zip/.tar/.tar.gz/.tgz/.tar.bz2/.tar.xz/.7z yang didukung.")
        }
        if (archives.isNotEmpty()) {
            archives.forEach { persist(it) }
            viewModel.addTargets(archives, TargetKind.ARCHIVE)
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (ignored: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "project"
    }

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: Exception) {
            // Sebagian provider tidak mengizinkan izin permanen; tetap bisa dipakai sesi ini.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GitHubUploaderTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var screen by remember { mutableStateOf(AppScreen.MAIN) }
                MainScreen(
                    state = state,
                    viewModel = viewModel,
                    screen = screen,
                    onScreenChange = { screen = it },
                    onPickFolder = { pickFolder.launch(null) },
                    onPickArchive = { pickArchive.launch(arrayOf("*/*")) },
                    onPickArchives = { pickFiles.launch(arrayOf("*/*")) }
                )
            }
        }
    }
}