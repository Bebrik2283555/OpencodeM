package com.opencode.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.data.objects
import com.opencode.data.str
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(brain: Brain, back: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val projects = brain.allProjects()
    val currentDir = brain.projectDir().absolutePath

    val pickFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = brain.pathOfTree(uri)
            if (path != null) {
                brain.addProject(File(path))
            } else {
                brain.notice = tr("Не удалось получить путь к папке", "Could not get folder path")
            }
        }
    }

    Page(tr("Проекты", "Projects"), back, actions = {
        IconButton({ creating = true }) { Icon(Icons.Outlined.Add, tr("Новый проект", "New project")) }
    }) { mod ->
        LazyColumn(mod.navigationBarsPadding().padding(vertical = 8.dp)) {
            items(projects, key = { it.absolutePath }) { dir ->
                val isCurrent = dir.absolutePath == currentDir
                val chatCount = brain.sessions.count { it.directory == dir.absolutePath }
                var menu by remember { mutableStateOf(false) }

                Surface(
                    color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            brain.activeProject = dir.absolutePath
                            brain.screens.add("project")
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (dir.absolutePath == brain.engine.workspace.absolutePath) Icons.Outlined.Home
                            else Icons.Outlined.Folder,
                            null,
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (dir.absolutePath == brain.engine.workspace.absolutePath) tr("Основной проект", "Default project")
                                    else dir.name.ifEmpty { tr("Проект", "Project") },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (isCurrent) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(start = 8.dp),
                                    ) {
                                        Text(
                                            tr("Активен", "Active"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                brain.folderLabel(dir.absolutePath),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            if (chatCount > 0) {
                                Text(
                                    tr("Чатов: $chatCount", "Chats: $chatCount"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(tr("Новый чат здесь", "New chat here")) },
                                    leadingIcon = { Icon(Icons.Outlined.Add, null) },
                                    onClick = {
                                        menu = false
                                        brain.newSessionIn(dir.absolutePath)
                                        back()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(tr("Сделать по умолчанию", "Set as default")) },
                                    leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                                    onClick = {
                                        menu = false
                                        brain.update { it.copy(folder = dir.absolutePath) }
                                    },
                                )
                                if (dir.absolutePath != brain.engine.workspace.absolutePath) {
                                    DropdownMenuItem(
                                        text = { Text(tr("Убрать из списка", "Remove from list")) },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                        onClick = {
                                            menu = false
                                            brain.removeProject(dir.absolutePath)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { pickFolderLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                        Text(tr("Выбрать папку", "Choose folder"), Modifier.padding(start = 6.dp))
                    }
                    Button(
                        onClick = { creating = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Text(tr("Создать", "Create"), Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(tr("Новый проект", "New project")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("Будет создана папка проекта в рабочей директории приложения.",
                            "A project folder will be created in the app's workspace."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(tr("Имя проекта", "Project name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        if (clean.isBlank()) return@TextButton
                        scope.launch {
                            try {
                                val target = File(brain.engine.workspace, clean)
                                withContext(Dispatchers.IO) {
                                    if (!target.exists()) target.mkdirs()
                                }
                                brain.addProject(target)
                                brain.activeProject = target.absolutePath
                                creating = false
                                brain.screens.add("project")
                            } catch (e: Exception) {
                                problem = e.message ?: e.toString()
                            }
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text(tr("Создать", "Create")) }
            },
            dismissButton = { TextButton({ creating = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

@Composable
fun ProjectScreen(brain: Brain, back: () -> Unit) {
    val dir = brain.projectDir()
    Page(if (dir.absolutePath == brain.engine.workspace.absolutePath) tr("Основной проект", "Default project") else dir.name.ifEmpty { tr("Проект", "Project") }, back) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (dir.absolutePath == brain.engine.workspace.absolutePath) tr("Основной проект", "Default project")
                        else dir.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        brain.folderLabel(dir.absolutePath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(
                        onClick = {
                            brain.newSessionIn(dir.absolutePath)
                            brain.screens.clear()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Icon(Icons.Outlined.Chat, null, Modifier.size(18.dp))
                        Text(tr("Новый чат в проекте", "New chat in project"), Modifier.padding(start = 6.dp))
                    }
                }
            }

            Section(tr("Инструменты проекта", "Project tools"))
            for ((route, title, icon) in listOf(
                Triple("files", tr("Файлы", "Files"), Icons.Outlined.Folder),
                Triple("changes", tr("Изменения", "Changes"), Icons.Outlined.Difference),
                Triple("terminal", tr("Терминал", "Terminal"), Icons.Outlined.Terminal),
                Triple("tasks", tr("Задачи и субагенты", "Tasks and subagents"), Icons.Outlined.AccountTree),
                Triple("context", tr("Контекст и история", "Context and history"), Icons.Outlined.History),
            )) ListItem(
                headlineContent = { Text(title) },
                leadingContent = { Icon(icon, null) },
                modifier = Modifier.clickable { brain.screens.add(route) },
            )
        }
    }
}

@Composable
fun FilesScreen(brain: Brain, attach: Boolean, back: () -> Unit) {
    val root = remember { brain.projectDir() }
    val editor = brain.editors.getOrPut(root.absolutePath) { com.opencode.data.Editor(root) }
    var directory by editor::directory
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by editor::file
    var text by editor::text
    var original by editor::original
    var problem by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun leave() { if (selected != null && text != original) discard = true else if (selected != null) selected = null else back() }
    BackHandler { leave() }
    LaunchedEffect(directory, selected) {
        if (selected == null) files = withContext(Dispatchers.IO) {
            directory.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).orEmpty()
        }
    }
    Page(selected?.name ?: tr("Файлы", "Files"), ::leave, actions = {
        if (selected != null && !attach) IconButton({ scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val file = selected!!
                check(file.readText() == original) { "File changed outside the editor; reopen it before saving" }
                val atomic = android.util.AtomicFile(file)
                val stream = atomic.startWrite()
                try { stream.write(text.toByteArray()); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
            }; original = text }.onFailure { problem = it.message }
        } }, enabled = text != original) { Icon(Icons.Outlined.Save, tr("Сохранить", "Save")) }
    }) { mod ->
        Column(mod.navigationBarsPadding()) {
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (selected != null) OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxSize().padding(12.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            else LazyColumn {
                item { Text(directory.absolutePath, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
                if (directory != root) item { ListItem(headlineContent = { Text("..") }, modifier = Modifier.clickable { directory = directory.parentFile ?: root }) }
                items(files, key = { it.absolutePath }) { file ->
                    ListItem(headlineContent = { Text(file.name) },
                        supportingContent = { if (file.isFile) Text("${file.length() / 1024} KB") },
                        leadingContent = { Icon(if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null) },
                        modifier = Modifier.clickable {
                            if (file.isDirectory) directory = file
                            else if (attach) { brain.draft().files.add(file.absolutePath); brain.saveDraft(); back() }
                            else scope.launch { runCatching { withContext(Dispatchers.IO) {
                                check(file.length() <= 1024 * 1024) { "Text editor limit: 1 MB" }
                                val bytes = file.readBytes(); check(0.toByte() !in bytes) { "Binary file: attach it to chat instead" }
                                bytes.toString(Charsets.UTF_8)
                            } }.onSuccess { original = it; text = it; selected = file; problem = null }.onFailure { problem = it.message } }
                        })
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(tr("Не сохранять изменения?", "Discard editor changes?")) },
        confirmButton = { TextButton({ discard = false; selected = null }) { Text(tr("Не сохранять", "Discard")) } },
        dismissButton = { TextButton({ discard = false }) { Text(tr("Продолжить редактирование", "Keep editing")) } })
}

@Composable
fun ChangesScreen(brain: Brain, back: () -> Unit) {
    var changes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var installingGit by remember { mutableStateOf(false) }
    var gitOutput by remember { mutableStateOf<String?>(null) }
    var gitInstalledKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val hasGit = remember(brain.linux.installed, gitInstalledKey) {
        File(brain.linux.rootfs, "usr/bin/git").exists() || File("/system/bin/git").exists() || File("/system/xbin/git").exists()
    }
    fun refresh() {
        scope.launch {
            runCatching {
                changes = brain.api.get("/api/vcs/diff${brain.locationQuery(brain.projectDir().absolutePath)}&mode=working").getJSONArray("data").objects()
                problem = null
            }.onFailure { problem = it.message }
        }
    }
    LaunchedEffect(brain.open) { refresh() }
    Page(tr("Изменения", "Changes"), back) { mod ->
        LazyColumn(mod.navigationBarsPadding()) {
            if (!hasGit) item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                            Text(tr("Git не установлен", "Git is not installed"), style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            tr("Для отслеживания изменений и диффов файлов установите Git в Alpine Linux.",
                               "Install Git in Alpine Linux to track file changes and diffs."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (brain.linux.installed) {
                            Button(
                                onClick = {
                                    installingGit = true
                                    scope.launch {
                                        val directory = brain.projectDir()
                                        val out = runCatching {
                                            brain.linux.run("apk add git", brain.claude.dns(), directory, brain.prefs.runtime)
                                        }.getOrElse { it.message ?: it.toString() }
                                        gitOutput = out
                                        gitInstalledKey++
                                        installingGit = false
                                        refresh()
                                    }
                                },
                                enabled = !installingGit
                            ) {
                                if (installingGit) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(tr("Установка...", "Installing..."))
                                } else {
                                    Icon(Icons.Outlined.Download, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(tr("Установить Git (apk add git)", "Install Git (apk add git)"))
                                }
                            }
                        } else {
                            Button(onClick = { brain.screens.add("linux") }) {
                                Icon(Icons.Outlined.Terminal, null)
                                Spacer(Modifier.width(8.dp))
                                Text(tr("Настроить Alpine Linux", "Setup Alpine Linux"))
                            }
                        }
                        gitOutput?.let {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp)) } }
            if (changes.isEmpty() && problem == null) item { Text(tr("Нет изменений в Git. Откат шагов доступен в истории чата.", "No Git changes. Turn rollback is available in chat history."), modifier = Modifier.padding(24.dp)) }
            items(changes, key = { it.optString("file") }) { file ->
                ListItem(headlineContent = { Text(file.optString("file")) },
                    supportingContent = { Text("+${file.optInt("additions")} −${file.optInt("deletions")}") },
                    modifier = Modifier.clickable { selected = file })
            }
        }
    }
    selected?.let { file ->
        val patch = file.optString("patch")
        val annotated = remember(patch) {
            buildAnnotatedString {
                for (line in patch.lines()) {
                    when {
                        line.startsWith('+') && !line.startsWith("+++") -> withStyle(SpanStyle(color = Color(0xFF2E7D32))) { append(line + "\n") }
                        line.startsWith('-') && !line.startsWith("---") -> withStyle(SpanStyle(color = Color(0xFFC62828))) { append(line + "\n") }
                        line.startsWith("@@") -> withStyle(SpanStyle(color = Color(0xFF0277BD))) { append(line + "\n") }
                        else -> append(line + "\n")
                    }
                }
            }
        }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(file.optString("file")) },
            text = { SelectionContainer { Text(annotated, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) } },
            confirmButton = { TextButton({ selected = null }) { Text(tr("Закрыть", "Close")) } })
    }
}

@Composable
fun ContextScreen(brain: Brain, back: () -> Unit) {
    val id = brain.open
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var staged by remember { mutableStateOf(false) }
    fun work(block: suspend () -> Unit) { scope.launch { runCatching { block() }.onFailure { problem = it.message } } }
    Page(tr("Контекст и история", "Context and history"), back) { mod ->
        LazyColumn(mod.navigationBarsPadding()) {
            item {
                val currentTokens = brain.currentContextTokens()
                val last = brain.messages.lastOrNull { it.role == "assistant" && it.tokensIn > 0 }
                val cacheRead = last?.tokensCacheRead ?: 0L
                val cacheWrite = last?.tokensCacheWrite ?: 0L
                val context = brain.currentModel()?.context ?: 0L
                val sessionCost = brain.messages.sumOf { it.cost }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Окно контекста: $currentTokens / $context токенов", "Context window: $currentTokens / $context tokens"),
                        style = MaterialTheme.typography.titleMedium)
                    if (context > 0) {
                        val ratio = (currentTokens.toFloat() / context).coerceIn(0f, 1f)
                        val barColor = if (ratio > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        LinearProgressIndicator({ ratio }, Modifier.fillMaxWidth().height(8.dp))
                    }
                    if (cacheRead > 0) {
                        Text(tr("Кэш промпта: чтение $cacheRead · запись $cacheWrite", "Prompt Cache: read $cacheRead · write $cacheWrite"),
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                    }
                    if (sessionCost > 0.0) {
                        Text(tr("Стоимость (200 сообщ.): $${String.format(java.util.Locale.US, "%.4f", sessionCost)}", "Cost (last 200): $${String.format(java.util.Locale.US, "%.4f", sessionCost)}"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (staged && id != null) {
                        Text(tr("Откат подготовлен. Подтвердите или отмените.", "Revert staged. Confirm or undo."))
                        Row { TextButton({ work { brain.commitRevert(id); staged = false } }) { Text(tr("Подтвердить", "Commit")) }
                            TextButton({ work { brain.clearRevert(id); staged = false } }) { Text(tr("Отменить откат", "Undo revert")) } }
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    Text(tr("Откат диалога к шагу (Time travel)", "Rollback dialog to step (Time travel)"),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(brain.messages.filter { it.user }, key = { it.id }) { message ->
                ListItem(headlineContent = { Text(message.text, maxLines = 3) }, trailingContent = {
                    TextButton({ if (id != null) work { brain.stageRevert(id, message.id); staged = true } }, enabled = id != null && brain.busy[id] != true) {
                        Text(tr("Откат", "Revert"))
                    }
                })
            }
        }
    }
}
