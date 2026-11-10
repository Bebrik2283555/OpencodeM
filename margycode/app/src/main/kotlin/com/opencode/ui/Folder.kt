package com.opencode.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Chat's project chip: shows active project for the conversation. */
@Composable
fun FolderChip(brain: Brain) {
    var dialog by remember { mutableStateOf(false) }
    val session = brain.session()
    val curDir = if (session != null) session.directory
        else brain.draft().folder.takeIf { it.isNotBlank() } ?: brain.activeProject ?: brain.folder().absolutePath
    val label = brain.projectLabel(curDir)
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = brain.ready && brain.busy[brain.open] != true) { dialog = true },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = 130.dp),
            )
        }
    }
    if (dialog) FolderDialog(brain, sessionID = session?.id, forNewChat = session == null) { dialog = false }
}

@Composable
fun FolderDialog(brain: Brain, sessionID: String? = null, forNewChat: Boolean = false, close: () -> Unit) {
    val context = LocalContext.current
    val phone = brain.phone.absolutePath
    val initialSelected = if (sessionID != null) brain.session(sessionID)?.directory.orEmpty()
        else if (forNewChat) brain.draft().folder.ifEmpty { brain.activeProject.orEmpty() }
        else brain.activeProject ?: brain.prefs.folder
    val defaultProject = brain.engine.workspace.absolutePath

    var choice by remember {
        mutableStateOf(if (initialSelected.isNotBlank()) initialSelected else defaultProject)
    }
    var isNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val tree = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val resolved = brain.pathOfTree(it)
            if (resolved != null) {
                val f = File(resolved)
                brain.addProject(f)
                choice = resolved
                isNew = false
            } else problem = tr("Не удалось определить путь папки", "Could not resolve the folder path")
        }
    }
    fun askFiles() {
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)))
        }.onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
    val allProjects = remember(brain.prefs.projects, brain.sessions) { brain.allProjects() }

    AlertDialog(
        onDismissRequest = close,
        icon = { Icon(Icons.Outlined.Folder, null) },
        title = { Text(tr("Проект", "Project")) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        if (sessionID == null) tr("Выберите проект для этого чата.", "Choose a project for this chat.")
                        else tr("Меняется проект этого чата, история остаётся.",
                            "Changes this chat's project without losing history."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(allProjects) { p ->
                    val isApp = p.absolutePath == defaultProject
                    val title = if (isApp) tr("Основной проект", "Default project") else p.name.ifEmpty { brain.folderLabel(p.absolutePath) }
                    val subtitle = brain.folderLabel(p.absolutePath)
                    val isSelected = !isNew && choice == p.absolutePath
                    Row(
                        Modifier.fillMaxWidth().clickable { choice = p.absolutePath; isNew = false }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(isSelected, { choice = p.absolutePath; isNew = false })
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(title, style = MaterialTheme.typography.bodyMedium)
                            if (!isApp && subtitle != title) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { isNew = true }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(isNew, { isNew = true })
                        Text(tr("Создать новый проект", "Create new project"), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (isNew) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            label = { Text(tr("Имя проекта", "Project name")) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { tree.launch(null) },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                        Text(tr("Выбрать папку", "Choose folder"), Modifier.padding(start = 8.dp))
                    }
                }
                if (!isNew && choice != defaultProject && !brain.filesGranted && choice.startsWith(phone)) item {
                    Text(
                        tr("Нужно разрешение «Доступ ко всем файлам».", "This needs the “Access all files” permission."),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(::askFiles) { Text(tr("Разрешить", "Allow")) }
                }
                problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    val folder = if (isNew) {
                        val clean = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        if (clean.isBlank()) return@launch
                        val targetDir = File(brain.engine.workspace, clean)
                        withContext(Dispatchers.IO) {
                            if (!targetDir.exists()) targetDir.mkdirs()
                        }
                        brain.addProject(targetDir)
                        targetDir.absolutePath
                    } else choice

                    if (folder.isNotEmpty() && folder.startsWith(phone) && !brain.filesGranted) { askFiles(); return@launch }
                    if (folder.isNotEmpty()) {
                        val made = runCatching { File(folder).let { it.isDirectory || it.mkdirs() } }.getOrDefault(false)
                        if (!made) {
                            problem = tr("Не удалось создать папку $folder. Проверьте доступ ко всем файлам.",
                                "Could not create $folder. Check all-files access.")
                            return@launch
                        }
                    }
                    working = true
                    try {
                        brain.activeProject = folder
                        if (sessionID == null && forNewChat) {
                            brain.draft().folder = folder
                            brain.saveDraft()
                        } else if (sessionID == null) {
                            brain.update { it.copy(folder = folder) }
                        } else brain.moveSession(sessionID, folder)
                        close()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { problem = e.message ?: e.toString() }
                    finally { working = false }
                }
            }, enabled = !working && (!isNew || newName.isNotBlank())) { Text(tr("Готово", "Done")) }
        },
        dismissButton = { TextButton(close) { Text(tr("Отмена", "Cancel")) } },
    )
}
