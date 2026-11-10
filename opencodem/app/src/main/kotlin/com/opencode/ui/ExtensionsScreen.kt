package com.opencode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.data.Extensions
import com.opencode.data.str
import com.opencode.tr
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ExtensionsScreen(brain: Brain, back: () -> Unit) {
    val context = LocalContext.current
    val extensions = remember { Extensions(context, brain) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    var builtins by remember { mutableStateOf(false) }
    fun work(block: suspend () -> Unit) { working = true; scope.launch {
        try { block(); list = extensions.plugins() } catch (e: Exception) { problem = e.message } finally { working = false }
    } }
    LaunchedEffect(Unit) { runCatching { list = extensions.plugins() }.onFailure { problem = it.message } }
    Page(tr("Расширения", "Extensions"), back, actions = {
        IconButton({ add = true }, enabled = !working) { Icon(Icons.Outlined.Add, tr("Установить", "Install")) }
        IconButton({ work { brain.reload() } }, enabled = !working) { Icon(Icons.Outlined.Refresh, tr("Перечитать", "Reload")) }
    }) { mod ->
        LazyColumn(mod.navigationBarsPadding()) {
            item { Text(tr("Плагины OpenCode, скиллы и MCP. Для плагина укажите npm-пакет или его репозиторий, для набора скиллов — GitHub URL.",
                "OpenCode plugins, skills and MCP. Use an npm package or its repository for a plugin; use a GitHub URL for skills."), modifier = Modifier.padding(24.dp)) }
            item { TextButton({ brain.screens.add("tools") }, Modifier.padding(horizontal = 12.dp)) { Text(tr("Скиллы и MCP", "Skills and MCP")) } }
            if (working) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            items(list.filter { builtins || it.optJSONObject("source")?.str("type") != "builtin" }, key = { it.str("id") ?: it.getJSONObject("source").toString() }) { plugin ->
                val ref = plugin.optJSONObject("source")?.str("target")
                ListItem(headlineContent = { Text(plugin.str("id") ?: ref ?: "plugin") },
                    supportingContent = { Text(plugin.optJSONObject("state")?.str("error") ?: listOfNotNull(ref, plugin.optJSONObject("state")?.str("status")).joinToString(" · ")) },
                    trailingContent = { if (ref != null) IconButton({ work { extensions.removePlugin(ref) } }, enabled = !working) { Icon(Icons.Outlined.Delete, tr("Удалить", "Remove")) } })
            }
            item { TextButton({ builtins = !builtins }, Modifier.padding(horizontal = 12.dp)) {
                Text(if (builtins) tr("Скрыть встроенные", "Hide built-ins") else tr("Показать встроенные", "Show built-ins"))
            } }
            problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp)) } }
        }
    }
    if (add) {
        var type by remember { mutableStateOf("plugin") }
        var value by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { add = false }, title = { Text(tr("Установить", "Install")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { for (v in listOf("plugin", "skills", "mcp")) FilterChip(type == v, { type = v }, label = {
                Text(when (v) {
                    "plugin" -> tr("Плагин", "Plugin")
                    "skills" -> tr("Скиллы", "Skills")
                    else -> "MCP"
                })
            }) }
            if (type == "mcp") OutlinedTextField(name, { name = it }, label = { Text(tr("Имя MCP", "MCP name")) }, singleLine = true)
            OutlinedTextField(value, { value = it }, label = { Text(if (type == "skills") "GitHub URL" else tr("npm-пакет или URL", "npm package or URL")) })
        } }, confirmButton = { TextButton({ add = false; work { when (type) {
            "plugin" -> extensions.addPlugin(value.trim())
            "skills" -> { val count = extensions.skills(value.trim()); problem = tr("Установлено скиллов: $count", "Installed skills: $count") }
            else -> extensions.mcp(name.trim(), value.trim())
        } } }, enabled = value.isNotBlank() && (type != "mcp" || name.isNotBlank())) { Text(tr("Установить", "Install")) } },
            dismissButton = { TextButton({ add = false }) { Text(tr("Отмена", "Cancel")) } })
    }
}
