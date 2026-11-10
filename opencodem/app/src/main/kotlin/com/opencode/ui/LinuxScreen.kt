package com.opencode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.Brain
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Alpine Linux for the agent: install, switch on or off, and a small console for one's own commands. */
@Composable
fun LinuxScreen(brain: Brain, back: () -> Unit) {
    val linux = brain.linux
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(linux.installed) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    fun run(cmd: String) {
        if (cmd.isBlank() || running) return
        running = true
        log += "$ $cmd\n"
        scope.launch {
            val directory = brain.session()?.directory?.let { java.io.File(it) } ?: brain.folder()
            val out = runCatching { linux.run(cmd, brain.claude.dns(), directory, brain.prefs.runtime) }.getOrElse { it.message ?: it.toString() }
            log = (log + out + "\n\n").takeLast(20_000)
            running = false
        }
    }

    Page("Linux", back) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Alpine Linux внутри приложения, как в Termux. Модель запускает в нём свои команды и сама ставит что нужно через apk: python, node, git, компиляторы. Ваши папки видны по тем же путям.",
                "Alpine Linux inside the app, as in Termux. The model runs its commands there and installs what it needs with apk: python, node, git, compilers. Your folders keep the same paths."),
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (!installed) {
                val p = progress
                if (p != null) {
                    LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                    Text(if (p < 1f) tr("Скачиваю Alpine: ${(p * 100).toInt()}%", "Downloading Alpine: ${(p * 100).toInt()}%")
                        else tr("Распаковываю…", "Unpacking…"), style = MaterialTheme.typography.bodySmall)
                } else Button({
                    progress = 0f
                    problem = null
                    scope.launch {
                        runCatching {
                            linux.install { done, total -> if (total > 0) scope.launch(Dispatchers.Main) { progress = done.toFloat() / total } }
                        }.onSuccess {
                            installed = true
                            brain.restart()
                        }.onFailure { problem = it.message ?: it.toString() }
                        progress = null
                    }
                }) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                    Text(tr("Установить Alpine Linux (около 4 МБ)", "Install Alpine Linux (about 4 MB)"), Modifier.padding(start = 8.dp))
                }
            } else {
                Text("Alpine ${linux.version.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in listOf("auto", "native", "alpine")) FilterChip(brain.prefs.runtime == mode, {
                        brain.update { it.copy(runtime = mode) }
                        brain.restart()
                    }, label = { Text(mode) })
                }
                Text(tr("Агент выбирает runtime для каждого вызова. Настоящий root — отдельный запрос разрешения, не fake root Alpine.",
                    "The agent selects runtime per call. Real root is a separate permission request, not Alpine's fake root."),
                    style = MaterialTheme.typography.bodySmall)
                val hasSu = brain.hasSu
                fun toggleAlpineRoot(on: Boolean) {
                    if (on) {
                        scope.launch {
                            val ok = brain.requestRootAccess()
                            if (ok) {
                                brain.update { it.copy(alpineRoot = true) }
                                brain.linux.rootChroot = true
                                brain.restart()
                            } else {
                                problem = tr("Root-доступ не получен. Разрешите доступ в Magisk или KernelSU.",
                                    "Root access was not granted. Allow it in Magisk or KernelSU.")
                            }
                        }
                    } else {
                        brain.update { it.copy(alpineRoot = false) }
                        brain.linux.rootChroot = false
                        brain.restart()
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = hasSu) { toggleAlpineRoot(!brain.prefs.alpineRoot) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Alpine через root (chroot)", "Alpine through root (chroot)"))
                        Text(if (!hasSu) tr("Недоступно: root (su) не найден на этом устройстве.", "Not available: root (su) not found on this device.")
                            else tr("Надёжнее на ядрах, где proot зависает на пайпах. Требует root; /storage монтируется в окружение.",
                            "More reliable on kernels where proot hangs on pipes. Needs root; /storage is bind-mounted."),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!hasSu) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(brain.prefs.alpineRoot && hasSu, { toggleAlpineRoot(it) }, enabled = hasSu)
                }

                Section(tr("Консоль", "Console"))
                Text(tr("Команды запускаются в папке проекта; таймаут 30 секунд. Для curl проверен обход зависшего триггера: apk --no-scripts add curl. Он пропускает все установочные скрипты и подходит не каждому пакету.",
                    "Commands use the project directory; 30-second timeout. Tested curl workaround: apk --no-scripts add curl. This skips all installation scripts and is not suitable for every package."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (log.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(log.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                            modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()))
                    }
                }
                if (running) Text(if (linux.installing) tr("Разворачиваю окружение…", "Preparing environment…") else tr("Выполняется команда…", "Running command…"))
                OutlinedTextField(command, { command = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("apk add python3") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { run(command); command = "" }),
                    trailingIcon = {
                        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton({ run(command); command = "" }) { Icon(Icons.AutoMirrored.Outlined.Send, null) }
                    },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ run("cat /etc/alpine-release; uname -m; apk --version") }, enabled = !running) {
                        Text(tr("Проверить", "Check"))
                    }
                    TextButton({ removing = true }) { Text(tr("Удалить Linux", "Remove Linux")) }
                }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text(tr("Удалить Linux?", "Remove Linux?")) },
        text = { Text(tr("Удалится всё, что установлено внутри (пакеты, /root). Ваши папки на телефоне не тронутся.",
            "Everything installed inside goes (packages, /root). Your folders on the phone stay.")) },
        confirmButton = {
            TextButton({
                removing = false
                scope.launch(Dispatchers.IO) {
                    linux.remove()
                    launch(Dispatchers.Main) { installed = false; log = ""; brain.restart() }
                }
            }) { Text(tr("Удалить", "Remove")) }
        },
        dismissButton = { TextButton({ removing = false }) { Text(tr("Отмена", "Cancel")) } },
    )
}
