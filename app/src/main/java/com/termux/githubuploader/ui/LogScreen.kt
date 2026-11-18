package com.termux.githubuploader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.termux.githubuploader.LogLine
import com.termux.githubuploader.UiState
import com.termux.githubuploader.UploadViewModel
import com.termux.githubuploader.core.LogLevel
import com.termux.githubuploader.ui.theme.GitHubBlue
import com.termux.githubuploader.ui.theme.GitHubGreen
import com.termux.githubuploader.ui.theme.GitHubRed
import com.termux.githubuploader.ui.theme.GitHubYellow
import com.termux.githubuploader.ui.theme.MonoStyle
import com.termux.githubuploader.ui.theme.SurfaceDark
import com.termux.githubuploader.ui.theme.TextMuted

@Composable
fun LogScreen(state: UiState, vm: UploadViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    state.running -> state.progress ?: "Bekerja..."
                    state.log.isEmpty() -> "Belum ada log."
                    else -> "${state.log.size} baris log"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.clearLog() }, enabled = !state.running) { Text("Bersihkan") }
        }
        Spacer(Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            color = SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        ) {
            if (state.log.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Panel log", style = MonoStyle, color = TextMuted)
                    Text("Log upload akan muncul di sini,", style = MonoStyle, color = TextMuted)
                    Text("sama seperti output upload.sh.", style = MonoStyle, color = TextMuted)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(SurfaceDark),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(state.log) { line -> LogRow(line) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color: Color = when (line.level) {
        LogLevel.INFO -> Color(0xFFE6EDF3)
        LogLevel.OK -> GitHubGreen
        LogLevel.WARN -> GitHubYellow
        LogLevel.ERROR -> GitHubRed
    }
    val prefix = when (line.level) {
        LogLevel.INFO -> "[*]"
        LogLevel.OK -> "[+]"
        LogLevel.WARN -> "[!]"
        LogLevel.ERROR -> "[x]"
    }
    Row(Modifier.fillMaxWidth()) {
        Text(line.time, style = MonoStyle.copy(fontSize = MonoStyle.fontSize * 0.86f), color = GitHubBlue.copy(alpha = 0.75f))
        Spacer(Modifier.width(6.dp))
        Text(prefix, style = MonoStyle, color = color)
        Spacer(Modifier.width(6.dp))
        Text(line.text, style = MonoStyle, color = color)
    }
}
