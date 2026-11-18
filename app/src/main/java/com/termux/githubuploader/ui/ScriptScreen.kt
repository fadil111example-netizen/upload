package com.termux.githubuploader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.termux.githubuploader.UploadViewModel
import com.termux.githubuploader.ui.theme.MonoStyle
import com.termux.githubuploader.ui.theme.SurfaceDark
import com.termux.githubuploader.ui.theme.TextMuted

/**
 * Menampilkan upload.sh yang ikut dibundel di APK (assets/upload.sh).
 * Skrip asilinya tetap disertakan apa adanya agar bisa dibaca/dipakai di Termux.
 */
@Composable
fun ScriptScreen(vm: UploadViewModel) {
    val context = LocalContext.current
    val script = remember { vm.readBundledScript() }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text(
            "Isi assets/upload.sh (${script.lines().size} baris) — bahasa asli yang perilakunya diterjemahkan ke Kotlin.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("upload.sh", script))
                vm.toast("Script disalin ke clipboard.")
            }) { Text("Salin script") }
            OutlinedButton(onClick = { vm.toast("File: assets/upload.sh di dalam APK.") }) { Text("Info") }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxSize()
                .background(SurfaceDark, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(script, style = MonoStyle)
        }
    }
}

@Composable
fun HelpScreen() {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            CardBox {
                SectionHeader(Icons.Filled.Info, "Cara pakai")
                Spacer(Modifier.height(8.dp))
                listOf(
                    "1. Isi token: menu kanan atas > Token GitHub (butuh scope 'repo' atau Contents: Read and write).",
                    "2. Tambahkan project: tombol Folder (project di HP) atau Arsip (.zip/.tar/.tar.gz/.tgz/.7z).",
                    "3. Atur opsi: public/private, nama repo, branch, pesan commit, dry-run.",
                    "4. Tekan Mulai upload. Pantau proses di panel Log.",
                    "5. Link repository muncul di bagian Hasil."
                ).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        item {
            CardBox {
                SectionHeader(Icons.Filled.Terminal, "Sama seperti skrip?")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Aplikasi ini menjalankan alur yang sama dengan upload.sh v2.0: cek token, cek/buat repo, abaikan node_modules & __pycache__ bila tanpa .gitignore, lewati file >100MB, pindai rahasia, sambung ke history lama tanpa force push, push dengan 3x retry, lalu verifikasi commit.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Bedanya: di Android tidak ada binary git/unzip/tar, jadi git dikerjakan JGit, pembacaan folder lewat Storage Access Framework, dan arsip diekstrak dengan library Java. Folder asli di HP tidak pernah diubah.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
        item {
            CardBox {
                SectionHeader(Icons.Filled.Key, "Token aman?")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Token disimpan di penyimpanan privat aplikasi (setara chmod 600 di skrip), tidak ditulis ke log, dan hanya dikirim ke api.github.com / github.com saat upload.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
