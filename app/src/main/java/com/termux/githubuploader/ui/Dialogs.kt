package com.termux.githubuploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.termux.githubuploader.PendingSecrets
import com.termux.githubuploader.core.AccountInfo
import com.termux.githubuploader.ui.theme.MonoStyle
import com.termux.githubuploader.ui.theme.TextMuted

@Composable
fun TokenDialog(
    account: AccountInfo?,
    checking: Boolean,
    onSave: (String) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 20.dp),
        title = { Text("Token GitHub") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (account != null) {
                    Text("Login sebagai ${account.login}", style = MaterialTheme.typography.bodyMedium)
                    account.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted) }
                    account.tokenExpiration?.let {
                        Text("Token berlaku sampai: $it", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    if (account.missingRepoScope) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Token classic ini tidak punya scope 'repo'. Upload bisa gagal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        "Tempel Personal Access Token (ghp_... atau github_pat_...). Token disimpan di penyimpanan privat aplikasi dan hanya dipakai untuk GitHub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (account != null) "Ganti token (opsional)" else "Token") },
                    singleLine = true,
                    visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "lihat token"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (checking) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Mengecek token...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(value) }, enabled = !checking && value.isNotBlank()) {
                Text("Simpan")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (account != null) {
                    TextButton(onClick = onLogout) { Text("Hapus token") }
                }
                TextButton(onClick = onDismiss) { Text("Tutup") }
            }
        }
    )
}

/** Konfirmasi seperti pertanyaan `confirm()` di skrip untuk file sensitif. */
@Composable
fun SecretsDialog(pending: PendingSecrets, onSkip: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp),
        title = { Text("File sensitif terdeteksi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "File berikut terindikasi berisi token / key / .env untuk repo ${pending.repoName}:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Spacer(Modifier.height(8.dp))
                pending.files.take(20).forEach {
                    Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.error)
                }
                if (pending.files.size > 20) {
                    Text("... dan ${pending.files.size - 20} file lainnya", style = MonoStyle, color = TextMuted)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pilih 'Lewati' agar file ini tidak ikut terupload (aman, tidak diblokir GitHub).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { Button(onClick = onSkip) { Text("Lewati file") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCancel) { Text("Batal") }
                OutlinedButton(onClick = onKeep) { Text("Tetap kirim") }
            }
        }
    )
}

@Composable
fun TextArea(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder, style = MaterialTheme.typography.bodySmall) }
        } else null,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun CloseIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Close, contentDescription = "tutup")
    }
}
