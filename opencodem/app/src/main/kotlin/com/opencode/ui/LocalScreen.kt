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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.engine.Local
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun gb(bytes: Long) = "%.1f".format(bytes / 1e9).replace('.', if (com.opencode.Lang.ru) ',' else '.') +
    tr(" ГБ", " GB")

/** Models that run on the phone: download from the list or by a link, and how much they remember. */
@Composable
fun LocalScreen(brain: Brain, back: () -> Unit) {
    val local = brain.local
    var round by remember { mutableIntStateOf(0) }
    val installed = remember(round, brain.localRevision) { local.installed() }
    val progress = brain.downloads
    var pictures by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    val ram = remember { local.ramGb() }
    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(local, brain.localStatus, brain.downloads.size) {
        var idle = 0
        while (idle < 3) {
            local.checkError()
            if (brain.localStatus != null || brain.downloads.isNotEmpty()) idle = 0 else idle++
            delay(1000)
        }
    }

    fun get(id: String, urls: List<String>) {
        brain.downloadModel(id, urls)
    }

    Page(tr("Модели на телефоне", "Models on the phone"), back) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Работают без интернета и бесплатно, прямо на процессоре телефона. Они заметно медленнее и слабее облачных. Памяти в телефоне: ${"%.0f".format(ram)} ГБ, модель должна быть заметно меньше.",
                "They work offline and for free, right on the phone's processor. They are much slower and weaker than cloud ones. This phone has ${"%.0f".format(ram)} GB of memory; a model should be well under that."),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            (local.error ?: brain.localError)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton({ showLog = !showLog }) { Text(if (showLog) tr("Скрыть llama.log", "Hide llama.log") else tr("Показать llama.log", "Show llama.log")) }
                if (showLog) SelectionContainer {
                    Text(local.logTail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(Modifier.fillMaxWidth().clickable { pictures = !pictures }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(pictures, { pictures = it })
                Text(tr("Скачивать и чтение картинок (ещё около 1 ГБ)", "Also download picture reading (about 1 GB more)"))
            }

            for (o in Local.OFFERS) {
                val have = o.id in installed
                val p = progress[o.id]
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(o.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(o.note + " · " + (if (have) gb(local.size(o.id)) else "${o.gb}" + tr(" ГБ", " GB")) +
                                    (if (have && local.sees(o.id)) tr(" · видит картинки", " · sees pictures") else ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            when {
                                p != null -> TextButton({ brain.cancelDownload(o.id) }) { Text(tr("Стоп", "Stop")) }
                                have -> {
                                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                    IconButton({ brain.removeLocalModel(o.id); round++ }) { Icon(Icons.Outlined.Delete, tr("Удалить", "Delete")) }
                                }
                                else -> IconButton({
                                    get(o.id, listOf(Local.url(o.repo, o.file)) + if (pictures && o.vision) listOf(Local.url(o.repo, o.mmproj)) else emptyList())
                                }) { Icon(Icons.Outlined.Download, tr("Скачать", "Download")) }
                            }
                        }
                        if (p != null) {
                            LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                        if (!have && p == null && o.gb > ram * 0.6) Text(tr("Скорее всего, не поместится в память этого телефона", "Probably won't fit in this phone's memory"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            val own = installed.filter { id -> Local.OFFERS.none { it.id == id } }
            if (own.isNotEmpty()) {
                Section(tr("Свои", "Your own"))
                for (id in own) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(id, Modifier.weight(1f))
                    Text(gb(local.size(id)), style = MaterialTheme.typography.bodySmall)
                    IconButton({ brain.removeLocalModel(id); round++ }) { Icon(Icons.Outlined.Delete, tr("Удалить", "Delete")) }
                }
            }
            Section(tr("Своя модель", "A model of your own"))
            Text(tr("Ссылка на файл .gguf на Hugging Face (кнопка «download» у файла).", "A link to a .gguf file on Hugging Face (the file's download button)."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(link, { link = it.trim() }, singleLine = true, placeholder = { Text("https://huggingface.co/…/model.gguf") },
                modifier = Modifier.fillMaxWidth())
            val linkId = link.substringAfterLast('/').substringBefore('?').removeSuffix(".gguf").lowercase().replace(Regex("[^a-z0-9._-]+"), "-")
            OutlinedButton({
                val fixed = link.replace("/blob/", "/resolve/")
                get(linkId, listOf(fixed))
                link = ""
            }, enabled = link.startsWith("https://") && link.contains(".gguf") && progress[linkId] == null) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                Text(tr("Скачать", "Download"), Modifier.padding(start = 8.dp))
            }
            progress.filterKeys { k -> Local.OFFERS.none { it.id == k } }.forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$k: ${(v * 100).toInt()}%", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ brain.cancelDownload(k) }) { Text(tr("Стоп", "Stop")) }
                }
                LinearProgressIndicator({ v }, Modifier.fillMaxWidth())
            }

            Section(tr("Память разговора", "Conversation memory"))
            Text(tr("Сколько текста модель держит в голове. Больше помнит, но ест больше памяти и медленнее начинает.",
                "How much text the model keeps in mind. More remembers more but eats more memory and starts slower."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in listOf(8192, 16384, 32768)) FilterChip(brain.prefs.localContext == c, {
                    brain.update { it.copy(localContext = c) }
                    brain.restart()
                }, label = { Text("${c / 1024}k") })
            }
            brain.downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
