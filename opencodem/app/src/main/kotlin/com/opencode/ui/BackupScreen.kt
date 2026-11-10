package com.opencode.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.data.Backup
import com.opencode.tr
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(brain: Brain, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = brain.transferBusy
    val status = brain.transferStatus
    var cache by remember { mutableStateOf(0L) }
    var restore by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(Unit) { cache = brain.cacheSize() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) {
        brain.backup(uri, false)
    } }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restore = it }
    Page(tr("Данные", "Data"), back) { mod ->
        Column(mod.padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Копия включает чаты с вложениями, конфигурацию, правила, агентов, команды и скиллы. Модели, Alpine и отдельное хранилище авторизации провайдеров не входят.",
                "Includes chats with attachments, configuration, rules, agents, commands and skills. Models, Alpine and provider credential storage are excluded."))
            OutlinedButton({ save.launch("OpencodeM-backup.zip") }, enabled = !busy) { Text(tr("Экспортировать", "Export")) }
            OutlinedButton({ load.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) { Text(tr("Восстановить", "Restore")) }
            HorizontalDivider()
            Text(tr("Кэш вложений: ${cache / 1024 / 1024} МБ", "Attachment cache: ${cache / 1024 / 1024} MB"))
            TextButton({ scope.launch { brain.clearAttachmentCache(); cache = brain.cacheSize() } }, enabled = !busy) { Text(tr("Очистить кэш", "Clear cache")) }
            if (busy) CircularProgressIndicator()
            status?.let { Text(it) }
        }
    }
    restore?.let { uri -> AlertDialog(onDismissRequest = { restore = null }, title = { Text(tr("Восстановить копию?", "Restore backup?")) },
        text = { Text(tr("Добавятся отсутствующие чаты, конфигурация будет заменена с сохранением прежней копии в files/before-restore-*. Существующие чаты не удаляются.",
            "Adds missing chats and replaces configuration, saving the previous configuration under files/before-restore-*. Existing chats are kept.")) },
        confirmButton = { TextButton({ restore = null; brain.backup(uri, true) }) { Text(tr("Восстановить", "Restore")) } }, dismissButton = { TextButton({ restore = null }) { Text(tr("Отмена", "Cancel")) } }) }
}
