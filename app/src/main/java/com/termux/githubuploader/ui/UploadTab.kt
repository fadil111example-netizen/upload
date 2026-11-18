package com.termux.githubuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.termux.githubuploader.UiState
import com.termux.githubuploader.UploadViewModel
import com.termux.githubuploader.core.TargetKind
import com.termux.githubuploader.ui.theme.GitHubGreen
import com.termux.githubuploader.ui.theme.TextMuted

@Composable
fun UploadTab(
    state: UiState,
    vm: UploadViewModel,
    onPickFolder: () -> Unit,
    onPickArchive: () -> Unit,
    onPickArchives: () -> Unit,
    onOpenLog: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { AccountCard(state, vm) }
        item { ProjectsCard(state, vm, onPickFolder, onPickArchive, onPickArchives) }
        item { OptionsCard(state, vm) }
        item { RunCard(state, vm, onOpenLog) }
        if (state.outcomes.isNotEmpty()) {
            item { OutcomesCard(state) }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun AccountCard(state: UiState, vm: UploadViewModel) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val account = state.account
                if (state.checkingToken) {
                    Text("Mengecek token...", style = MaterialTheme.typography.bodyMedium)
                } else if (account != null) {
                    Text(account.login, style = MaterialTheme.typography.titleMedium)
                    val exp = account.tokenExpiration?.let { " • berlaku s/d $it" } ?: ""
                    Text(
                        if (account.name != null) account.name + exp else "Token tersimpan$exp",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else if (state.tokenPresent) {
                    Text("Token tidak valid", style = MaterialTheme.typography.bodyMedium)
                    Text("GitHub menolak token yang tersimpan.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    Text("Belum ada token", style = MaterialTheme.typography.bodyMedium)
                    Text("Simpan token GitHub sekali saja.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
            if (state.tokenPresent) {
                IconButton(onClick = { vm.refreshAccount() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "cek token")
                }
            }
            TextButton(onClick = { vm.openTokenDialog() }) {
                Text(if (state.tokenPresent) "Ganti" else "Isi token")
            }
        }
    }
}

@Composable
private fun ProjectsCard(
    state: UiState,
    vm: UploadViewModel,
    onPickFolder: () -> Unit,
    onPickArchive: () -> Unit,
    onPickArchives: () -> Unit
) {
    CardBox {
        SectionHeader(Icons.Filled.Folder, "Project (${state.targets.size})")
        Spacer(Modifier.height(8.dp))
        if (state.targets.isEmpty()) {
            EmptyHint("Belum ada yang dipilih.\nTambahkan folder project atau arsip (.zip/.tar/.tar.gz/.7z).")
        } else {
            state.targets.forEachIndexed { index, target ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(target.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (target.kind == TargetKind.FOLDER) "folder" else "arsip",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    TextButton(onClick = { vm.removeTarget(target.uri) }, enabled = !state.running) {
                        Text("hapus")
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onPickFolder,
                enabled = !state.running,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Folder", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = onPickArchive,
                enabled = !state.running,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Filled.Description, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Arsip", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onPickArchives,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Pilih beberapa arsip sekaligus", style = MaterialTheme.typography.bodySmall)
        }
        if (state.targets.size > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Beberapa project akan diupload satu per satu (seperti skrip: upload.sh A B C).",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun OptionsCard(state: UiState, vm: UploadViewModel) {
    val o = state.options
    val enabled = !state.running
    CardBox {
        SectionHeader(Icons.Filled.Terminal, "Opsi upload")
        Spacer(Modifier.height(6.dp))

        ToggleRow(
            title = "Repository public",
            subtitle = "Mati = private (sama seperti default skrip)",
            checked = o.isPublic,
            enabled = enabled,
            onCheckedChange = { v -> vm.updateOptions { it.copy(isPublic = v) } }
        )
        ToggleRow(
            title = "Dry-run",
            subtitle = "Simulasi: hitung file, tidak membuat repo / push",
            checked = o.dryRun,
            enabled = enabled,
            onCheckedChange = { v -> vm.updateOptions { it.copy(dryRun = v) } }
        )
        ToggleRow(
            title = "Ikutkan node_modules / __pycache__ / .gradle",
            subtitle = "Setara --all di skrip",
            checked = o.includeIgnored,
            enabled = enabled,
            onCheckedChange = { v -> vm.updateOptions { it.copy(includeIgnored = v) } }
        )
        ToggleRow(
            title = "Jangan hapus file di repo lama",
            subtitle = "Setara --no-delete: file yang tidak ada di project dibiarkan",
            checked = o.keepRemoteDeletions,
            enabled = enabled,
            onCheckedChange = { v -> vm.updateOptions { it.copy(keepRemoteDeletions = v) } }
        )
        ToggleRow(
            title = "Otomatis (tanpa tanya)",
            subtitle = "Setara -y: file sensitif langsung dilewati",
            checked = o.autoConfirm,
            enabled = enabled,
            onCheckedChange = { v -> vm.updateOptions { it.copy(autoConfirm = v) } }
        )

        Spacer(Modifier.height(8.dp))
        TextArea(
            value = o.repoName.orEmpty(),
            onValueChange = { v -> vm.updateOptions { it.copy(repoName = v.ifBlank { null }) } },
            label = "Nama repo kustom (--name, hanya 1 project)",
            placeholder = vm.suggestRepoName() ?: "otomatis dari nama folder",
            modifier = Modifier
        )
        Spacer(Modifier.height(8.dp))
        TextArea(
            value = o.branch.orEmpty(),
            onValueChange = { v -> vm.updateOptions { it.copy(branch = v.ifBlank { null }) } },
            label = "Branch (--branch)",
            placeholder = "main / branch utama repo"
        )
        Spacer(Modifier.height(8.dp))
        TextArea(
            value = o.commitMessage.orEmpty(),
            onValueChange = { v -> vm.updateOptions { it.copy(commitMessage = v.ifBlank { null }) } },
            label = "Pesan commit (--msg)",
            placeholder = "Initial commit from Android"
        )
        Spacer(Modifier.height(8.dp))
        TextArea(
            value = o.description,
            onValueChange = { v -> vm.updateOptions { it.copy(description = v) } },
            label = "Deskripsi repo (--desc)",
            placeholder = "Uploaded from Android"
        )
    }
}

@Composable
private fun RunCard(state: UiState, vm: UploadViewModel, onOpenLog: () -> Unit) {
    CardBox {
        if (state.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(state.progress ?: "Bekerja...", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = GitHubGreen)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { vm.cancelUpload() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Batalkan")
            }
        } else {
            Button(
                onClick = { vm.start() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.CloudUpload, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (state.options.dryRun) "Mulai dry-run" else "Mulai upload", fontWeight = FontWeight.SemiBold)
            }
            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                    Text("Lihat log lengkap (${state.log.size} baris)")
                }
            }
        }
        state.lastSummary?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun OutcomesCard(state: UiState) {
    CardBox {
        SectionHeader(Icons.Filled.CloudUpload, "Hasil")
        Spacer(Modifier.height(4.dp))
        state.outcomes.forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.project, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            o.error != null -> o.error
                            o.upToDate -> "sudah up-to-date, tidak ada perubahan"
                            o.dryRun -> "${o.fileCount} file (dry-run, tidak dikirim)"
                            else -> "${o.fileCount} file • ${o.seconds} detik" +
                                if (o.skippedBig > 0) " • ${o.skippedBig} file >100MB dilewati" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (o.success) TextMuted else MaterialTheme.colorScheme.error
                    )
                    o.repoUrl?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(o.success)
            }
        }
    }
}
