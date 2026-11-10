package com.opencode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.data.jsonOf
import com.opencode.data.objects
import com.opencode.data.str
import com.opencode.tr
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AgentsScreen(brain: Brain, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { brain.loadAgents(); brain.loadProviders(true) }.onFailure { error = it.message } }
    Page(tr("Агенты", "Agents"), back, actions = {
        IconButton({ editing = JSONObject() }) { Icon(Icons.Outlined.Add, tr("Добавить", "Add")) }
    }) { mod ->
        LazyColumn(mod.navigationBarsPadding()) {
            item { Text(tr("Основной агент выбирается в чате. Субагенты могут использовать собственную модель и правила.",
                "Choose the primary agent in chat. Subagents can have their own model and rules."),
                modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(brain.agents.filter { !it.optBoolean("hidden") }, key = { it.optString("id") }) { agent ->
                ListItem(headlineContent = { Text(agent.optString("id")) },
                    supportingContent = { Text(listOfNotNull(agent.str("description"), agent.str("mode"),
                        agent.optJSONObject("model")?.let { "${it.optString("providerID")}/${it.optString("id")}" }).joinToString("\n")) },
                    leadingContent = { Icon(Icons.Outlined.SmartToy, null) },
                    modifier = Modifier.clickable { editing = agent })
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp)) } }
        }
    }
    editing?.let { agent ->
        var id by remember(agent) { mutableStateOf(agent.str("id").orEmpty()) }
        var description by remember(agent) { mutableStateOf(agent.str("description").orEmpty()) }
        var mode by remember(agent) { mutableStateOf(agent.str("mode") ?: "subagent") }
        var system by remember(agent) { mutableStateOf(agent.str("system").orEmpty()) }
        var model by remember(agent) { mutableStateOf(agent.optJSONObject("model")?.let { "${it.optString("providerID")}/${it.optString("id")}" }.orEmpty()) }
        var permissions by remember(agent) { mutableStateOf(agent.optJSONArray("permissions")?.toString(2) ?: "[]") }
        var working by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!working) editing = null }, title = { Text(tr("Профиль агента", "Agent profile")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it }, label = { Text("ID") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(tr("Назначение", "Description")) })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (m in listOf("primary", "subagent", "all")) {
                    val label = when (m) {
                        "primary" -> tr("Основной", "Primary")
                        "subagent" -> tr("Субагент", "Subagent")
                        else -> tr("Все", "All")
                    }
                    FilterChip(mode == m, { mode = m }, label = { Text(label) }) } }
                ModelField(brain, model, { model = it })
                OutlinedTextField(system, { system = it }, label = { Text(tr("Инструкции", "Instructions")) }, minLines = 3)
                OutlinedTextField(permissions, { permissions = it }, label = { Text(tr("разрешения (JSON)", "permissions (JSON)")) }, minLines = 2)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton({ working = true; scope.launch {
                try {
                    val value = jsonOf("description" to description, "mode" to mode, "system" to system,
                        "model" to model.ifBlank { null }, "permissions" to JSONArray(permissions))
                    brain.saveAgent(id.trim(), value); editing = null
                } catch (e: Exception) { problem = e.message } finally { working = false }
            } }, enabled = id.isNotBlank() && !working) { Text(tr("Сохранить", "Save")) } },
            dismissButton = { TextButton({ editing = null }, enabled = !working) { Text(tr("Отмена", "Cancel")) } })
    }
}

@Composable
fun ModelField(brain: Brain, value: String, onValue: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton({ show = true }, Modifier.fillMaxWidth()) { Text(value.ifBlank { tr("Модель родительского чата", "Parent chat's model") }, maxLines = 2) }
    if (show) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { show = false }, title = { Text(tr("Модель", "Model")) }, text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text(tr("Поиск", "Search")) })
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item { ListItem(headlineContent = { Text(tr("Наследовать", "Inherit")) }, modifier = Modifier.clickable { onValue(""); show = false }) }
                    items(brain.models.filter { brain.isModelAvailable(it) && (it.ref.contains(query, true) || it.name.contains(query, true)) }, key = { it.ref }) { model ->
                        ListItem(headlineContent = { Text(model.name) }, supportingContent = { Text(model.provider) },
                            modifier = Modifier.clickable { onValue(model.ref); show = false })
                    }
                }
            }
        }, confirmButton = { TextButton({ show = false }) { Text(tr("Закрыть", "Close")) } })
    }
}

@Composable
fun TasksScreen(brain: Brain, back: () -> Unit) {
    val root = brain.rootSession(brain.open)
    Page(tr("Задачи и субагенты", "Tasks and subagents"), back) { mod ->
        LazyColumn(mod.navigationBarsPadding()) {
            items(brain.sessions.filter { brain.rootSession(it.id) == root || brain.busy[it.id] == true }, key = { it.id }) { s ->
                ListItem(headlineContent = { Text(s.title.ifBlank { s.agent ?: s.id }) },
                    supportingContent = { Text(listOfNotNull(s.agent, s.model,
                        if (brain.asks.any { it.session == s.id } || brain.forms.any { it.str("sessionID") == s.id }) tr("Ждёт ответа", "Needs reply")
                        else if (brain.busy[s.id] == true) tr("Работает", "Running") else tr("Завершено", "Idle")).joinToString(" · ")) },
                    leadingContent = { Icon(if (s.parent == null) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.AccountTree, null) },
                    trailingContent = { if (brain.busy[s.id] == true) IconButton({ brain.stopSession(s.id) }) { Icon(Icons.Outlined.Stop, tr("Стоп", "Stop")) } },
                    modifier = Modifier.clickable { brain.openSession(s.id); brain.screens.clear() })
            }
        }
    }
}

@Composable
fun PermissionsScreen(brain: Brain, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var saved by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var edit by remember { mutableStateOf<Int?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching {
        rules = brain.readConfig().optJSONArray("permissions").objects()
        saved = brain.api.get("/api/permission/saved").getJSONArray("data").objects()
    }.onFailure { problem = it.message } }
    fun save(next: List<JSONObject>) { scope.launch { runCatching { brain.savePermissions(JSONArray(next)); rules = next }.onFailure { problem = it.message } } }
    Page(tr("Разрешения", "Permissions"), back, actions = { IconButton({ edit = -1 }) { Icon(Icons.Outlined.Add, tr("Добавить правило", "Add rule")) } }) { mod ->
        LazyColumn(mod.navigationBarsPadding()) {
            item { Text(tr("Правила идут сверху вниз; последнее совпадение побеждает. shell — команды, edit — изменение файлов, subagent — запуск агентов.",
                "Rules run top to bottom; last match wins. shell controls commands, edit controls file changes, subagent controls delegation."), modifier = Modifier.padding(24.dp)) }
            items(rules.indices.toList()) { index -> val rule = rules[index]
                ListItem(headlineContent = { Text("${rule.optString("action")}: ${rule.optString("resource")}") },
                    supportingContent = { Text(rule.optString("effect")) }, modifier = Modifier.clickable { edit = index },
                    trailingContent = { IconButton({ save(rules.filterIndexed { i, _ -> i != index }) }) { Icon(Icons.Outlined.Delete, tr("Удалить", "Delete")) } })
            }
            if (saved.isNotEmpty()) item { Section(tr("Сохранённые «Всегда»", "Saved approvals")) }
            items(saved, key = { it.optString("id") }) { rule ->
                ListItem(headlineContent = { Text("${rule.optString("action")}: ${rule.optString("resource")}") },
                    trailingContent = { IconButton({ scope.launch { runCatching {
                        brain.api.delete("/api/permission/saved/${rule.getString("id")}"); saved = saved - rule
                    }.onFailure { problem = it.message } } }) { Icon(Icons.Outlined.Delete, tr("Удалить", "Delete")) } })
            }
            problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp)) } }
        }
    }
    edit?.let { index ->
        val old = rules.getOrNull(index)
        var action by remember(index) { mutableStateOf(old?.str("action") ?: "shell") }
        var resource by remember(index) { mutableStateOf(old?.str("resource") ?: "git *") }
        var effect by remember(index) { mutableStateOf(old?.str("effect") ?: "ask") }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(tr("Правило", "Rule")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(action, { action = it }, label = { Text(tr("Действие", "Action")) }, singleLine = true)
            OutlinedTextField(resource, { resource = it }, label = { Text(tr("Шаблон", "Pattern")) }, singleLine = true)
            Row { for (v in listOf("allow", "ask", "deny")) FilterChip(effect == v, { effect = v }, label = {
                Text(when (v) {
                    "allow" -> tr("Разрешить", "Allow")
                    "ask" -> tr("Спросить", "Ask")
                    else -> tr("Запретить", "Deny")
                })
            }) }
        } }, confirmButton = { TextButton({
            val next = rules.toMutableList(); val rule = jsonOf("action" to action, "resource" to resource, "effect" to effect)
            if (index < 0) next += rule else next[index] = rule
            save(next); edit = null
        }, enabled = action.isNotBlank() && resource.isNotBlank()) { Text(tr("Сохранить", "Save")) } },
            dismissButton = { TextButton({ edit = null }) { Text(tr("Отмена", "Cancel")) } })
    }
}
