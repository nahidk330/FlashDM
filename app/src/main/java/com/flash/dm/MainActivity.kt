package com.flash.dm

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FlashApp() } }
    }

    @Composable
    fun FlashApp() {
        var url by remember { mutableStateOf("") }
        val tasks = remember { mutableStateListOf<DownloadTask>() }
        val clipboard = LocalClipboardManager.current

        LaunchedEffect(Unit) {
            val t = clipboard.getText()?.text?.trim()
            if (t != null && t.startsWith("http")) url = t
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("⚡ Flash Download Manager", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("ডাউনলোড লিংক") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (url.startsWith("http")) {
                    val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "file" }
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                    val task = DownloadTask(url, File(dir, name), client, scope)
                    tasks.add(0, task); task.start(); url = ""
                }
            }) { Text("ডাউনলোড শুরু") }
            LazyColumn { items(tasks) { TaskRow(it) } }
        }
    }

    @Composable
    fun TaskRow(t: DownloadTask) {
        val s by t.state.collectAsState()
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(s.fileName)
                val p = if (s.total > 0) s.downloaded.toFloat() / s.total else 0f
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text("${fmt(s.speedBps)}/s  •  ${(p * 100).toInt()}%  •  ${s.status}")
                when (s.status) {
                    Status.RUNNING -> TextButton(onClick = { t.pause() }) { Text("⏸ Pause") }
                    Status.PAUSED, Status.FAILED -> TextButton(onClick = { t.start() }) { Text("▶ Resume") }
                    else -> {}
                }
            }
        }
    }

    private fun fmt(b: Long): String = when {
        b > 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
        b > 1_000 -> "%.0f KB".format(b / 1_000.0)
        else -> "$b B"
    }
}
