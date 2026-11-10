package com.opencode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.Brain
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LINK = Regex("""https://\S+""")

/**
 * The Claude Agent provider: Claude Code downloaded from npm, signed in with
 * `claude auth login`, then handed to opencode.
 */
@Composable
fun ClaudeSection(brain: Brain) {
    val claude = brain.claude
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(claude.installed) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf<Process?>(null) }
    var output by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val connected = brain.integrations.firstOrNull { it.id == "claude-agent" }?.connected == true

    suspend fun checkStatus() {
        status = runCatching { claude.run("auth", "status") }.getOrElse { it.message }
    }

    LaunchedEffect(installed) { if (installed) checkStatus() }

    // Reads `claude auth login` as it talks: the link comes first, then it waits for the code.
    LaunchedEffect(login) {
        val p = login ?: return@LaunchedEffect
        var opened = false
        withContext(Dispatchers.IO) {
            val r = p.inputStream.bufferedReader()
            val buf = CharArray(4096)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                val chunk = String(buf, 0, n)
                withContext(Dispatchers.Main) {
                    output += chunk
                    if (!opened) LINK.find(output)?.let { opened = true; runCatching { uri.openUri(it.value) } }
                }
            }
            p.waitFor()
        }
        val ok = p.exitValue() == 0
        login = null
        if (ok) runCatching { brain.connectClaude() }.onFailure { problem = it.message }
        else problem = output.lines().lastOrNull { it.isNotBlank() } ?: tr("Вход не удался", "Sign-in failed")
        checkStatus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Модели Claude по подписке Claude Pro или Max. opencode работает через официальный Claude Code, его приложение скачает с npm (около 145 МБ). Входите вы в сам Claude Code, opencode токен не видит.",
            "Claude models on a Claude Pro or Max plan. opencode works through the official Claude Code, which the app downloads from npm (about 145 MB). You sign in to Claude Code itself; opencode never sees the token."),
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!installed) {
            val p = progress
            if (p != null) {
                LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                Text(tr("Скачиваю Claude Code: ${(p * 100).toInt()}%", "Downloading Claude Code: ${(p * 100).toInt()}%"),
                    style = MaterialTheme.typography.bodySmall)
            } else Button({
                progress = 0f
                problem = null
                scope.launch {
                    runCatching {
                        claude.install { done, total -> if (total > 0) scope.launch(Dispatchers.Main) { progress = done.toFloat() / total } }
                    }.onSuccess { installed = true }.onFailure { problem = it.message ?: it.toString() }
                    progress = null
                }
            }) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                Text(tr("Скачать Claude Code", "Download Claude Code"), Modifier.padding(start = 8.dp))
            }
        } else {
            Row {
                Text("Claude Code ${claude.version.orEmpty()}", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(top = 12.dp))
                TextButton({ claude.remove(); installed = false; status = null }) { Text(tr("Удалить", "Remove")) }
            }
            status?.let {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    problem = null
                    output = ""
                    code = ""
                    login = runCatching { claude.login() }.onFailure { problem = it.message }.getOrNull()
                }, enabled = login == null) {
                    Icon(Icons.Outlined.Login, null, Modifier.size(18.dp))
                    Text(tr("Войти в Claude", "Sign in to Claude"), Modifier.padding(start = 8.dp))
                }
                if (!connected) OutlinedButton({
                    scope.launch { runCatching { brain.connectClaude() }.onFailure { problem = it.message } }
                }) { Text(tr("Подключить", "Connect")) }
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    login?.let { p ->
        val link = LINK.find(output)?.value
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Вход в Claude", "Signing in to Claude")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (link == null) Row {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(tr("Запускаю Claude Code…", "Starting Claude Code…"), Modifier.padding(start = 10.dp))
                    } else {
                        Text(tr("Войдите на открывшейся странице, скопируйте код и вставьте сюда.",
                            "Sign in on the page that opened, copy the code and paste it here."))
                        TextButton({ runCatching { uri.openUri(link) } }) { Text(tr("Открыть страницу ещё раз", "Open the page again")) }
                        OutlinedTextField(code, { code = it.trim() }, singleLine = true, label = { Text(tr("Код", "Code")) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch(Dispatchers.IO) {
                        runCatching { p.outputStream.write((code + "\n").toByteArray()); p.outputStream.flush() }
                    }
                }, enabled = link != null && code.isNotBlank()) { Text(tr("Готово", "Done")) }
            },
            dismissButton = { TextButton({ p.destroy() }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}
