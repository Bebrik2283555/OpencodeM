package com.opencode.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.data.jsonOf
import com.opencode.data.str
import com.opencode.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private fun nameOf(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "skill"

private fun statusOf(server: JSONObject): String {
    val st = server.opt("status")
    val tag = when (st) {
        is JSONObject -> st.str("status") ?: st.str("type") ?: st.str("_tag")
        is String -> st
        else -> null
    }?.lowercase().orEmpty()
    return when {
        "connected" in tag -> tr("подключён", "connected")
        "pending" in tag -> tr("подключается", "connecting")
        "disabled" in tag -> tr("выключен", "disabled")
        "auth" in tag -> tr("нужен вход", "needs sign-in")
        "fail" in tag -> tr("ошибка", "failed") + ((st as? JSONObject)?.str("error")?.let { ": $it" } ?: "")
        else -> tag
    }
}

/** Skills and MCP servers, both kept where opencode on a computer keeps them. */
@Composable
fun ToolsScreen(brain: Brain, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var round by remember { mutableIntStateOf(0) }
    var skills by remember { mutableStateOf<List<JSONObject>?>(null) }
    var servers by remember { mutableStateOf<List<JSONObject>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    fun work(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }.onFailure { brain.notice = it.message ?: it.toString() }
            busy = false
            round++
        }
    }

    LaunchedEffect(round) {
        // Reloaded services settle asynchronously; keep their status fresh for a few seconds.
        repeat(6) {
            runCatching { brain.skills() }.onSuccess { skills = it }
            runCatching { brain.mcpServers() }.onSuccess { servers = it }
            delay(2000)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work { brain.installSkill(uri, nameOf(context, uri)) }
    }

    Page(tr("Скиллы и MCP", "Skills and MCP"), back, actions = {
        if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
        else IconButton({ work { brain.reload() } }) { Icon(Icons.Outlined.Refresh, tr("Перечитать конфигурацию", "Reload configuration")) }
    }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Section(tr("Скиллы", "Skills"))
            Text(tr("Инструкции, которые модель подгружает сама, когда они к месту: SKILL.md или архив .zip со SKILL.md внутри, как для Claude Code и opencode.",
                "Instructions the model loads by itself when they fit: a SKILL.md or a .zip with a SKILL.md inside, as for Claude Code and opencode."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp))
            val list = skills
            if (list == null) CircularProgressIndicator(Modifier.padding(24.dp).size(22.dp), strokeWidth = 2.dp)
            else if (list.isEmpty()) Text(tr("Скиллов пока нет", "No skills yet"), color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
            for (s in list.orEmpty()) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.str("name") ?: s.str("id") ?: "?", style = MaterialTheme.typography.bodyLarge)
                    s.str("description")?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val path = s.str("path").orEmpty()
                if (brain.removable(path)) IconButton({ work { brain.removeSkill(path) } }) {
                    Icon(Icons.Outlined.Delete, tr("Удалить", "Delete"))
                }
            }
            OutlinedButton({ pick.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Icon(Icons.Outlined.FileUpload, null, Modifier.size(18.dp))
                Text(tr("Добавить скилл из файла", "Add a skill from a file"), Modifier.padding(start = 8.dp))
            }

            Section("MCP")
            Text(tr("Удалённые MCP работают по адресу. node/npx запускаются встроенным Bun; Python, uv и npm требуют включённого Alpine Linux и установки соответствующего пакета в нём.",
                "Remote MCP uses a URL. node/npx use the built-in Bun; Python, uv and npm need Alpine Linux enabled with the corresponding package installed."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp))
            val mcp = servers
            if (mcp != null && mcp.isEmpty()) Text(tr("MCP-серверов пока нет", "No MCP servers yet"), color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
            for (m in mcp.orEmpty()) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val name = m.str("name") ?: "?"
                Column(Modifier.weight(1f)) {
                    Text(m.str("title") ?: name, style = MaterialTheme.typography.bodyLarge)
                    Text(statusOf(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                m.str("integrationID")?.let { id ->
                    if (statusOf(m) == tr("нужен вход", "needs sign-in")) TextButton({ brain.screens.add("provider:$id") }) {
                        Text(tr("Войти", "Sign in"))
                    }
                }
                androidx.compose.material3.Switch(m.optJSONObject("status")?.str("status") != "disabled",
                    { enabled -> work { brain.toggleMcp(name, enabled) } }, enabled = !busy)
                IconButton({ work { brain.removeMcp(name) } }) { Icon(Icons.Outlined.Delete, tr("Удалить", "Delete")) }
            }
            OutlinedButton({ adding = true }, enabled = !busy, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Text(tr("Добавить MCP-сервер", "Add an MCP server"), Modifier.padding(start = 8.dp))
            }
            TextButton({ brain.screens.add("config") }, Modifier.padding(horizontal = 12.dp)) {
                Text(tr("Всё остальное в opencode.json", "Everything else is in opencode.json"))
            }
            TextButton({ brain.screens.add("extensions") }, Modifier.padding(horizontal = 12.dp)) { Text(tr("Установить по ссылке или npm-пакету", "Install from URL or npm")) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (adding) McpDialog(onDone = { name, server -> adding = false; work { brain.addMcp(name, server) } }, onCancel = { adding = false })
}

@Composable
private fun McpDialog(onDone: (String, JSONObject) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(tr("MCP-сервер", "MCP server")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.trim() }, singleLine = true, label = { Text(tr("Имя", "Name")) },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(remote, { remote = true }, label = { Text(tr("Удалённый", "Remote")) })
                    FilterChip(!remote, { remote = false }, label = { Text(tr("Локальный", "Local")) })
                }
                if (remote) {
                    OutlinedTextField(url, { url = it.trim() }, singleLine = true, label = { Text("URL") },
                        placeholder = { Text("https://mcp.example.com/mcp") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(headers, { headers = it }, label = { Text(tr("Заголовки, по одному: Имя: значение", "Headers, one per line: Name: value")) },
                        minLines = 2, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(command, { command = it }, label = { Text(tr("Команда", "Command")) },
                        placeholder = { Text("python3 server.py") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton({
                val server = if (remote) jsonOf("type" to "remote", "url" to url, "headers" to JSONObject(
                    headers.lines().mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
                ).takeIf { it.length() > 0 })
                else jsonOf("type" to "local", "command" to JSONArray(command.trim().split(Regex("\\s+"))))
                onDone(name, server)
            }, enabled = name.isNotEmpty() && (if (remote) url.startsWith("http") else command.isNotBlank())) { Text(tr("Добавить", "Add")) }
        },
        dismissButton = { TextButton(onCancel) { Text(tr("Отмена", "Cancel")) } },
    )
}
