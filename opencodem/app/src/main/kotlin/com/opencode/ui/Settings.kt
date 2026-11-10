package com.opencode.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.ui.platform.LocalContext
import com.opencode.data.UpdateStatus
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.BuildConfig
import com.opencode.Brain
import com.opencode.tr

@Composable
fun SettingsScreen(brain: Brain, back: () -> Unit) {
    var rules by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { rules = withContext(Dispatchers.IO) { brain.rulesFile.takeIf { it.exists() }?.readText().orEmpty() } }
    var wipe by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Page(tr("Настройки", "Settings"), back) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()) {
            Section(tr("Оформление", "Look"))
            for ((v, label) in listOf("system" to tr("Как в системе", "As the system"), "light" to tr("Светлая", "Light"), "dark" to tr("Тёмная", "Dark"))) {
                Row(Modifier.fillMaxWidth().clickable { brain.update { it.copy(theme = v) } }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(brain.prefs.theme == v, { brain.update { it.copy(theme = v) } })
                    Text(label)
                }
            }

            Section(tr("Язык", "Language"))
            for ((v, label) in listOf(
                "auto" to tr("Как в системе", "System default"),
                "ru" to "Русский",
                "en" to "English"
            )) {
                Row(Modifier.fillMaxWidth().clickable { brain.update { it.copy(language = v) } }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(brain.prefs.language == v, { brain.update { it.copy(language = v) } })
                    Text(label)
                }
            }

            Section(tr("Правила для модели", "Rules for the model"))
            Text(tr("Общий AGENTS.md opencode: что модель должна помнить в каждом чате.",
                "opencode's global AGENTS.md: what the model keeps in mind in every chat."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            rules?.let { r ->
                OutlinedTextField(r, { rules = it }, minLines = 3, maxLines = 12,
                    placeholder = { Text(tr("Например: отвечай кратко и по-русски", "For example: answer briefly")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                TextButton({ scope.launch { runCatching { brain.saveRules(r) } } }, Modifier.padding(start = 12.dp)) {
                    Text(tr("Сохранить правила", "Save the rules"))
                }
            }

            Section(tr("Файлы", "Files"))
            Row(Modifier.fillMaxWidth().clickable { brain.screens.add("linux") }.padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Linux для модели", "Linux for the model"))
                    Text(when {
                        !brain.linux.installed -> tr("Не установлен: команды идут в оболочку Android", "Not installed: commands go to Android's shell")
                        brain.prefs.linux -> "Alpine ${brain.linux.version.orEmpty()}"
                        else -> tr("Установлен, выключен", "Installed, off")
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Section(tr("Разрешения", "Permissions"))
            Text(tr("«Обычный» спрашивает перед root, телефоном и опасными командами. «Турбо» разрешает всё без вопросов.",
                "Default asks before root, phone control and risky commands. Turbo allows everything without asking."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in listOf("default", "turbo")) FilterChip(brain.prefs.permissionMode == mode, {
                    brain.update { it.copy(permissionMode = mode) }
                    brain.restart()
                }, label = { Text(if (mode == "default") tr("Обычный", "Default") else tr("Турбо", "Turbo")) })
            }
            TextButton({ brain.screens.add("permissions") }, Modifier.padding(horizontal = 12.dp)) {
                Text(tr("Правила разрешений и сохранённые ответы", "Permission rules and saved answers"))
            }

            Section(tr("Данные", "Data"))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((route, label) in listOf("agents" to tr("Агенты и модели", "Agents and models"),
                    "extensions" to tr("Расширения", "Extensions"),
                    "backup" to tr("Резервная копия и кэш", "Backup and cache"))) {
                    OutlinedButton({ brain.screens.add(route) }) { Text(label) }
                }
                val hasSu = brain.hasSu
                fun togglePhoneControl(on: Boolean) {
                    if (on) {
                        scope.launch {
                            val ok = brain.requestRootAccess()
                            if (ok) {
                                brain.update { it.copy(phoneControl = true) }
                                brain.restart()
                            } else {
                                brain.notice = tr("Root-доступ не получен. Разрешите доступ в Magisk или KernelSU.",
                                    "Root access was not granted. Allow it in Magisk or KernelSU.")
                            }
                        }
                    } else {
                        brain.update { it.copy(phoneControl = false) }
                        brain.restart()
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = hasSu) { togglePhoneControl(!brain.prefs.phoneControl) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Инструменты телефона", "Phone tools"))
                        Text(if (!hasSu) tr("Недоступно: root (su) не найден на этом устройстве.", "Not available: root (su) not found on this device.")
                            else tr("Скриншот, тап, свайп и приложения через root.", "Screenshot, tap, swipe and apps through root."),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!hasSu) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(brain.prefs.phoneControl && hasSu, { togglePhoneControl(it) }, enabled = hasSu)
                }
                OutlinedButton({ brain.screens.add("config") }) { Text(tr("Настройки opencode (opencode.json)", "opencode settings (opencode.json)")) }
                OutlinedButton({ brain.restart() }) { Text(tr("Перезапустить opencode", "Restart opencode")) }
                OutlinedButton({ wipe = true }, enabled = brain.sessions.isNotEmpty()) { Text(tr("Удалить все чаты", "Delete every chat")) }
            }

            Section(tr("О приложении", "About"))
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(44)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("OpencodeM ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("opencode", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(tr("Внутри работает opencode 2.2.5 (MIT) на официальном Bun. Ключи и чаты хранятся только на телефоне.",
                    "Inside runs opencode 2.2.5 (MIT) on the official Bun. Keys and chats stay on the phone."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ uri.openUri("https://opencode.ai") }) { Text("opencode.ai") }
                    TextButton({ uri.openUri("https://models.dev") }) { Text("models.dev") }
                }
            }

            Section(tr("Обновления", "Updates"))
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val updater = brain.updater
                when (val st = updater.status) {
                    is UpdateStatus.Idle, is UpdateStatus.UpToDate -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (st is UpdateStatus.UpToDate) tr("У вас последняя версия", "You have the latest version")
                                else tr("Версия ${BuildConfig.VERSION_NAME}", "Version ${BuildConfig.VERSION_NAME}"),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(onClick = { scope.launch { updater.check(manual = true) } }) {
                                Text(tr("Проверить", "Check"))
                            }
                        }
                    }
                    is UpdateStatus.Checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(tr("Проверка обновлений...", "Checking for updates..."), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    is UpdateStatus.Available -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    tr("Доступно обновление: v${st.version}", "Update available: v${st.version}"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                if (st.notes.isNotBlank()) {
                                    Text(
                                        st.notes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Button(
                                    onClick = { scope.launch { updater.downloadAndInstall(st) } },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(tr("Скачать и установить", "Download & Install"))
                                }
                            }
                        }
                    }
                    is UpdateStatus.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                tr("Загрузка обновления: ${(st.progress * 100).toInt()}%",
                                   "Downloading update: ${(st.progress * 100).toInt()}%"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            LinearProgressIndicator(
                                progress = { st.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    is UpdateStatus.ReadyToInstall -> {
                        Button(
                            onClick = { scope.launch { updater.install(st.file) } },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(tr("Установить v${st.version}", "Install v${st.version}"))
                        }
                    }
                    is UpdateStatus.Error -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                tr("Ошибка: ${st.message}", "Error: ${st.message}"),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = { scope.launch { updater.check(manual = true) } }) {
                                Text(tr("Повторить", "Retry"))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (wipe) AlertDialog(
        onDismissRequest = { wipe = false },
        title = { Text(tr("Удалить все чаты?", "Delete every chat?")) },
        text = { Text(tr("Вернуть их будет нельзя.", "They can't be brought back.")) },
        confirmButton = { TextButton({ brain.sessions.toList().forEach { brain.delete(it) }; wipe = false }) { Text(tr("Удалить", "Delete")) } },
        dismissButton = { TextButton({ wipe = false }) { Text(tr("Отмена", "Cancel")) } },
    )
}
