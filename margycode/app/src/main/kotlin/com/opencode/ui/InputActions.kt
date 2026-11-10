package com.opencode.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opencode.Brain
import com.opencode.data.str
import com.opencode.tr
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

@Composable
fun AttachmentPreview(path: String, size: Int) {
    val ctx = LocalContext.current
    val image = File(path).extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "heic")
    if (image) Thumb(path, size)
    else Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.width(size.dp).clickable {
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(path))
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path).extension) ?: "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.InsertDriveFile, null)
            Text(File(path).name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun AgentChip(brain: Brain) {
    var menu by remember { mutableStateOf(false) }
    val selected = brain.session()?.agent ?: brain.currentModel()?.let { brain.agentFor(it) } ?: brain.prefs.agent
    Box {
        TextButton({ menu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Outlined.SmartToy, null, Modifier.size(16.dp))
            Text(when (selected) { "build" -> "Build"; "plan" -> "Plan"; "chat" -> tr("Чат", "Chat"); else -> selected },
                Modifier.padding(start = 5.dp), maxLines = 1)
        }
        DropdownMenu(menu, { menu = false }) {
            for (agent in brain.agents.filter { it.str("mode") != "subagent" && !it.optBoolean("hidden") }) {
                val id = agent.str("id") ?: continue
                DropdownMenuItem(text = { Text(id) }, onClick = { brain.pickAgent(id); menu = false },
                    trailingIcon = { if (id == selected) Icon(Icons.Outlined.Check, null) })
            }
            DropdownMenuItem(text = { Text(tr("Настроить агентов", "Manage agents")) },
                onClick = { menu = false; brain.screens.add("agents") })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputActions(brain: Brain, text: String, onText: (String) -> Unit, onFiles: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraPath?.let { if (ok) onFiles(listOf(brain.cameraUri(File(it)))) else File(it).delete() }
        cameraPath = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onFiles(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onFiles(it) }
    var listening by remember { mutableStateOf(false) }
    val currentText by rememberUpdatedState(text)
    val currentOnText by rememberUpdatedState(onText)
    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false; brain.notice = tr("Голосовой ввод: ошибка $error", "Voice input: error $error") }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    currentOnText(listOf(currentText, it).filter { s -> s.isNotBlank() }.joinToString(" "))
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { recognizer?.destroy() }
    }
    fun listen() {
        if (recognizer == null) { brain.notice = tr("Служба распознавания речи не установлена", "Speech recognition service is not installed"); return }
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        listening = true
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) listen() }
    IconButton({ if (listening) { recognizer?.cancel(); listening = false } else menu = true }) {
        Icon(if (listening) Icons.Outlined.Mic else Icons.Outlined.Add, tr("Вложения и команды", "Attachments and commands"))
    }
    if (menu) {
        ModalBottomSheet(onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    tr("Вложения и действия", "Attachments & Actions"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                ListItem(
                    headlineContent = { Text(tr("Галерея (изображения)", "Gallery (images)")) },
                    leadingContent = { Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        menu = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                ListItem(
                    headlineContent = { Text(tr("Файлы устройства", "Device files")) },
                    leadingContent = { Icon(Icons.Outlined.AttachFile, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        menu = false
                        files.launch(arrayOf("*/*"))
                    },
                )
                ListItem(
                    headlineContent = { Text(tr("Сделать фото", "Take photo")) },
                    leadingContent = { Icon(Icons.Outlined.PhotoCamera, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        menu = false
                        runCatching {
                            val file = brain.cameraFile()
                            cameraPath = file.absolutePath
                            photo.launch(brain.cameraUri(file))
                        }.onFailure { brain.notice = it.message }
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ListItem(
                    headlineContent = { Text(tr("Команды (/)", "Commands (/)")) },
                    leadingContent = { Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = false; sheet = "commands" },
                )
                ListItem(
                    headlineContent = { Text(tr("Контекст и скиллы (@)", "Context & skills (@)")) },
                    leadingContent = { Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = false; sheet = "context" },
                )
                ListItem(
                    headlineContent = { Text(tr("Команда оболочки (!)", "Shell command (!)")) },
                    leadingContent = { Icon(Icons.Outlined.Code, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = false; onText("!" + text.removePrefix("!")) },
                )
                ListItem(
                    headlineContent = { Text(tr("Голосовой ввод", "Voice input")) },
                    leadingContent = { Icon(Icons.Outlined.Mic, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        menu = false
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                        else microphone.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }
    LaunchedEffect(text) {
        if (text == "/") sheet = "commands"
        if (text == "@") sheet = "context"
    }
    if (sheet != null) {
        var availableSkills by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        LaunchedEffect(sheet) { runCatching { brain.loadAgents(); availableSkills = brain.skills() }.onFailure { brain.notice = it.message } }
        ModalBottomSheet({ sheet = null }) {
            Text(if (sheet == "commands") tr("Команды", "Commands") else tr("Добавить контекст", "Add context"),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            LazyColumn(Modifier.heightIn(max = 450.dp).navigationBarsPadding()) {
                if (sheet == "commands") items(brain.commands, key = { it.optString("name") }) { command ->
                    ListItem(headlineContent = { Text("/" + command.optString("name")) },
                        supportingContent = { Text(command.str("description").orEmpty(), maxLines = 2) },
                        modifier = Modifier.clickable { onText("/${command.optString("name")} "); sheet = null })
                } else {
                    item { ListItem(headlineContent = { Text(tr("Файл проекта", "Project file")) }, leadingContent = { Icon(Icons.Outlined.Folder, null) },
                        modifier = Modifier.clickable { sheet = null; if (text == "@") onText(""); brain.screens.add("files:attach") }) }
                    items(availableSkills, key = { "skill:" + it.optString("id") }) { skill ->
                        ListItem(headlineContent = { Text(skill.str("name") ?: skill.optString("id")) },
                            supportingContent = { Text(tr("Скилл", "Skill"), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.clickable { brain.draft().skills.add(skill.getString("id")); brain.saveDraft(); if (text == "@") onText(""); sheet = null })
                    }
                    items(brain.agents.filter { !it.optBoolean("hidden") }, key = { "agent:" + it.optString("id") }) { agent ->
                        ListItem(headlineContent = { Text("@" + agent.optString("id")) },
                            modifier = Modifier.clickable { brain.draft().agents.add(agent.getString("id")); brain.saveDraft(); if (text == "@") onText(""); sheet = null })
                    }
                }
            }
        }
    }
}
