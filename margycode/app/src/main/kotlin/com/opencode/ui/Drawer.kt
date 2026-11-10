package com.opencode.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.R
import com.opencode.data.Session
import com.opencode.data.objects
import com.opencode.tr
import java.util.Calendar

@Composable
fun Logo(size: Int) {
    Surface(color = androidx.compose.ui.graphics.Color(0xFF0B0F0D), shape = RoundedCornerShape((size * 0.24f).dp),
        modifier = Modifier.size(size.dp)) {
        Image(painterResource(R.drawable.logo), null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding((size * 0.16f).dp))
    }
}

private fun fileName(s: Session): String {
    val title = s.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifEmpty { "chat" }
    return "$title.opencode.json"
}

private fun section(updated: Long, day: Long): String {
    return when {
        updated >= day -> tr("Сегодня", "Today")
        updated >= day - 86_400_000L -> tr("Вчера", "Yesterday")
        updated >= day - 7 * 86_400_000L -> tr("На этой неделе", "This week")
        else -> tr("Раньше", "Earlier")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Drawer(brain: Brain, close: () -> Unit) {
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    var exporting by remember { mutableStateOf<Session?>(null) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Session>?>(null) }
    val day = remember { Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    LaunchedEffect(query) {
        if (query.isBlank()) found = null else {
            kotlinx.coroutines.delay(250)
            runCatching { brain.api.get("/api/session?limit=100&search=${com.opencode.engine.Api.enc(query)}") }
                .onSuccess { found = it.optJSONArray("data").objects().map { Session.from(it) } }
        }
    }
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val chat = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri != null) scope.launch { runCatching { brain.exportSession(chat.id, uri) }.onFailure { brain.notice = it.message } }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { brain.importSession(uri); close() }
    }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(310.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, bottom = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Logo(34)
            Column(Modifier.padding(start = 12.dp)) {
                Text("OpencodeM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(tr("opencode на телефоне", "opencode on the phone"), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        NavigationDrawerItem(
            label = { Text(tr("Новый чат", "New chat")) },
            icon = { Icon(Icons.Outlined.Add, null) },
            selected = false,
            onClick = { brain.openSession(null); close() },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            item { OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(tr("Поиск чатов", "Search chats")) },
                shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
            var last = ""
            val list = (found ?: brain.sessions.filter { it.parent == null }).sortedByDescending { it.updated }
            for (chat in list) {
                val s = section(chat.updated, day)
                if (s != last) {
                    last = s
                    item(key = "s:$s") {
                        Text(s, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                    }
                }
                item(key = chat.id) {
                    var menu by remember { mutableStateOf(false) }
                    val selected = chat.id == brain.open
                    Box {
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).combinedClickable(
                                onClick = { brain.openSession(chat.id); close() },
                                onLongClick = { menu = true },
                            ),
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(chat.title.ifEmpty { tr("Без названия", "Untitled") }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (brain.busy[chat.id] == true) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                                }
                            }
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(tr("Переименовать", "Rename")) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = { menu = false; renaming = chat })
                            DropdownMenuItem(text = { Text(tr("Выгрузить в файл", "Export to a file")) },
                                leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                                onClick = { menu = false; exporting = chat; save.launch(fileName(chat)) })
                            DropdownMenuItem(text = { Text(tr("Удалить", "Delete")) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menu = false; deleting = chat })
                        }
                    }
                }
            }
            if (list.isEmpty()) item {
                Text(tr("Здесь будут ваши чаты", "Your chats will be here"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NavigationDrawerItem(label = { Text(tr("Проекты", "Projects")) }, icon = { Icon(Icons.Outlined.Folder, null) }, selected = false,
                onClick = { brain.screens.add("projects"); close() })
            NavigationDrawerItem(
                label = { Text(tr("Загрузить диалог", "Import a chat")) },
                icon = { Icon(Icons.Outlined.FileUpload, null) },
                selected = false,
                onClick = { load.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            )
            NavigationDrawerItem(
                label = { Text(tr("Лимиты", "Limits")) },
                icon = { Icon(Icons.Outlined.DataUsage, null) },
                selected = false,
                onClick = { brain.screens.add("limits"); close() },
            )
            NavigationDrawerItem(
                label = { Text(tr("Скиллы и MCP", "Skills and MCP")) },
                icon = { Icon(Icons.Outlined.Extension, null) },
                selected = false,
                onClick = { brain.screens.add("tools"); close() },
            )
            NavigationDrawerItem(
                label = { Text(tr("Провайдеры и модели", "Providers and models")) },
                icon = { Icon(Icons.Outlined.Hub, null) },
                selected = false,
                onClick = { brain.screens.add("providers"); close() },
            )
            NavigationDrawerItem(
                label = { Text(tr("Настройки", "Settings")) },
                icon = { Icon(Icons.Outlined.Settings, null) },
                badge = if (brain.updater.status is com.opencode.data.UpdateStatus.Available) {
                    { Text("●", color = MaterialTheme.colorScheme.primary) }
                } else null,
                selected = false,
                onClick = { brain.screens.add("settings"); close() },
            )
        }
    }

    renaming?.let { chat ->
        var title by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(tr("Название чата", "Chat name")) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ brain.rename(chat, title); renaming = null }) { Text(tr("Сохранить", "Save")) } },
            dismissButton = { TextButton({ renaming = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Удалить чат?", "Delete the chat?")) },
            text = { Text(chat.title) },
            confirmButton = { TextButton({ brain.delete(chat); deleting = null }) { Text(tr("Удалить", "Delete")) } },
            dismissButton = { TextButton({ deleting = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}
