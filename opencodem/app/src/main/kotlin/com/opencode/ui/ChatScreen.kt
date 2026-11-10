package com.opencode.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.Brain
import com.opencode.data.Message
import com.opencode.data.Part
import com.opencode.data.objects
import com.opencode.data.str
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

fun thinkingLabel(v: String) = when (v) {
    "" -> "Auto"
    "none" -> "Off"
    "minimal" -> "Minimal"
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "xhigh" -> "Extra high"
    "max" -> "Max"
    else -> v
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(brain: Brain, openDrawer: () -> Unit) {
    val id = brain.open
    val session = brain.session()
    val parent = session?.parent
    val branch = parent != null
    val model = brain.currentModel()
    val running = id != null && brain.busy[id] == true
    var picker by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    var contextSheet by remember { mutableStateOf(false) }
    val draft = brain.draft(id)
    var text by draft::text
    val pictures = draft.files
    var editing by remember(id) { mutableStateOf<Message?>(null) }
    val scope = rememberCoroutineScope()
    val list = remember(id) { LazyListState() }
    var follow by remember(id) { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, canScroll) ->
            if (!scrolling && !canScroll) follow = true
        }
    }

    LaunchedEffect(brain.shared) {
        val s = brain.shared ?: return@LaunchedEffect
        text = s.first
        pictures.addAll(s.second)
        brain.shared = null
        brain.saveDraft(id)
    }

    val isCompacted = brain.messages.any { it.role == "compaction" }
    val totalIn = brain.currentContextTokens()
    val maxContext = model?.context?.takeIf { it > 0 } ?: 128_000L
    val ctxPercent = if (maxContext > 0 && totalIn > 0) ((totalIn.toFloat() / maxContext) * 100).toInt().coerceAtLeast(1) else 0

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            navigationIcon = { IconButton(openDrawer) { Icon(Icons.Outlined.Menu, tr("Меню", "Menu")) } },
            title = {
                if (branch) {
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AccountTree, null, Modifier.size(16.dp))
                            Text(" " + (session?.agent ?: tr("Субагент", "Subagent")),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                        }
                        Text(brain.session(parent)?.title ?: tr("Основной чат", "Parent chat"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                    }
                } else {
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = brain.ready) { picker = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(model?.name ?: if (brain.ready) tr("Выберите модель", "Pick a model") else "OpencodeM",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                            if (brain.ready) Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(20.dp))
                        }
                        model?.let { m ->
                            Text(brain.providerName(m.provider), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            actions = {
                if (totalIn > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { contextSheet = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(
                                    if (ctxPercent > 85) MaterialTheme.colorScheme.error
                                    else if (ctxPercent > 65) Color(0xFFE69B00)
                                    else MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                if (isCompacted) "$ctxPercent% ⚡" else "$ctxPercent%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                }
                if (!branch) IconButton({ brain.openSession(null) }, enabled = id != null) { Icon(Icons.Outlined.EditNote, tr("Новый чат", "New chat")) }
                Box {
                    IconButton({ actions = true }) { Icon(Icons.Outlined.MoreVert, tr("Действия чата", "Chat actions")) }
                    DropdownMenu(actions, { actions = false }) {
                        DropdownMenuItem(
                            text = { Text(tr("Контекст и память", "Context & memory")) },
                            leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                            onClick = { actions = false; contextSheet = true },
                        )
                        for ((route, label, icon) in listOf(
                            Triple("project", tr("Проект", "Project"), Icons.Outlined.Folder),
                            Triple("tasks", tr("Задачи", "Tasks"), Icons.Outlined.AccountTree),
                            Triple("terminal", tr("Терминал", "Terminal"), Icons.Outlined.Terminal),
                        )) {
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = { Icon(icon, null) },
                                onClick = { actions = false; brain.screens.add(route) }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )

        if (branch) BranchBar { brain.openSession(parent) }

        val context = LocalContext.current
        val defaultUriHandler = LocalUriHandler.current
        val chatUriHandler = remember(context, defaultUriHandler) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    if (uri.startsWith("file://") || uri.startsWith("/")) {
                        val cleanPath = uri.removePrefix("file://").substringBefore('#').substringBefore('?')
                        val filePath = runCatching { java.net.URLDecoder.decode(cleanPath, "UTF-8") }.getOrDefault(cleanPath)
                        val file = File(filePath)
                        if (file.exists()) {
                            val roots = listOfNotNull(
                                brain.session()?.directory,
                                brain.engine.workspace.absolutePath,
                                File(android.os.Environment.getExternalStorageDirectory(), "Download").absolutePath,
                                File(android.os.Environment.getExternalStorageDirectory(), "Documents").absolutePath,
                                File(android.os.Environment.getExternalStorageDirectory(), "DCIM").absolutePath,
                                File(android.os.Environment.getExternalStorageDirectory(), "Pictures").absolutePath,
                            )
                            val target = runCatching { file.canonicalPath }.getOrDefault(filePath)
                            val allowed = roots.any {
                                val root = runCatching { File(it).canonicalPath }.getOrDefault(it)
                                target == root || target.startsWith(root + File.separator)
                            }
                            if (!allowed) {
                                brain.notice = tr("Файл вне рабочей папки", "File is outside the project folder")
                                return
                            }
                            val opened = runCatching {
                                val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                val mime = context.contentResolver.getType(contentUri) ?: "*/*"
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(contentUri, mime)
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(Intent.createChooser(intent, file.name).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                                true
                            }.getOrDefault(false)
                            if (opened) return
                        }
                    }
                    runCatching { defaultUriHandler.openUri(uri) }
                }
            }
        }

        CompositionLocalProvider(LocalUriHandler provides chatUriHandler) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val shown = brain.shown()
                when {
                    !brain.ready -> Starting(brain)
                    id == null || shown.isEmpty() -> Welcome(brain)
                    else -> {
                        val lastAnswer = shown.lastOrNull { it.role == "assistant" }?.id
                        // a failed turn ends with the error twice: on the answer and on the turn's end
                        val repeated = shown.indices.filter { i ->
                            shown[i].role == "idle" && shown[i].error != null && shown.getOrNull(i - 1)?.error != null
                        }.map { shown[it].id }.toSet()
                        val timeline = shown.filter { it.id !in repeated }
                        LaunchedEffect(timeline.size, running, follow) {
                            if (follow && !list.isScrollInProgress) list.scrollToEnd()
                        }
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(timeline, key = { it.id }) { m ->
                                when (m.role) {
                                    "user" -> UserBubble(m, canEdit = !branch,
                                        onEdit = { editing = m; text = m.text; pictures.clear(); pictures.addAll(m.files) },
                                        onContext = { contextSheet = true })
                                    "assistant" -> Answer(m, live = running && !m.done,
                                        last = !running && m.id == lastAnswer, again = { brain.again() }, canRetry = !branch)
                                    "idle" -> if (m.error != null) ErrorCard(m.error) else if (m.outcome == "interrupted")
                                        Text(tr("Остановлено", "Stopped"), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.outline)
                                    "compaction" -> CompactionCard(m)
                                }
                            }
                            if (running && shown.last().role != "assistant") item(key = "dots") { Dots() }
                        }
                        if (!follow) FilledIconButton({ follow = true; scope.launch { list.scrollToEnd() } },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                            Icon(Icons.Outlined.KeyboardArrowDown, tr("К последнему сообщению", "Latest message"))
                        }
                    }
                }
            }
        }

        if (brain.downloads.isNotEmpty()) TextButton({ brain.screens.add("local") }, Modifier.fillMaxWidth()) {
            Text(tr("Скачиваются модели: ${brain.downloads.size}", "Downloading models: ${brain.downloads.size}"))
        }
        if (branch) {
            BranchFooter()
        } else {
            Composer(
                brain = brain,
                text = text,
                onText = { text = it; brain.saveDraft(id) },
                pictures = pictures,
                editing = editing,
                cancelEdit = { editing = null; text = "" },
                running = running,
                onPick = { uris -> scope.launch { try { for (u in uris) pictures += brain.importAttachment(u); brain.saveDraft(id) }
                    catch (e: Exception) { brain.notice = e.message ?: e.toString() } } },
                send = {
                    follow = true
                    val e = editing
                    if (e != null) {
                        brain.resend(e, text, pictures.toList())
                        text = ""
                        pictures.clear()
                        editing = null
                    } else {
                        val sentText = text
                        val sentPictures = pictures.toList()
                        brain.send(sentText, sentPictures) {
                            if (text == sentText) text = ""
                            pictures.removeAll(sentPictures.toSet())
                        }
                    }
                },
                stop = { brain.stop() },
            )
        }
    }

    if (picker) ModelSheet(brain) { picker = false }
    if (brain.notice == null) Asks(brain)
    brain.notice?.let { n ->
        AlertDialog(
            onDismissRequest = { brain.notice = null },
            title = { Text(tr("Не получилось", "That didn't work")) },
            text = { Text(n) },
            confirmButton = { TextButton({ brain.notice = null }) { Text("OK") } },
        )
    }
    if (contextSheet) ContextSheet(brain) { contextSheet = false }
}

@Composable
private fun BranchBar(back: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        TextButton(back, Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("Вернуться в основной чат", "Back to the main chat"))
        }
    }
}

@Composable
private fun BranchFooter() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.AccountTree, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("В сессиях субагентов запрещён ввод", "Input is disabled in subagent sessions"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private suspend fun LazyListState.scrollToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    layoutInfo.visibleItemsInfo.lastOrNull()?.let { item ->
        scrollBy((item.offset + item.size - layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat())
    }
}

@Composable
private fun Starting(brain: Brain) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(72)
        Spacer(Modifier.height(24.dp))
        val err = brain.engineError
        if (err == null) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(tr("Запускаю opencode…", "Starting opencode…"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp))
        } else {
            Text(tr("opencode не запустился", "opencode didn't start"), style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(err, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
            Button({ brain.restart() }) { Text(tr("Ещё раз", "Try again")) }
        }
    }
}

@Composable
private fun Welcome(brain: Brain) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(72)
        Spacer(Modifier.height(20.dp))
        Text(tr("Чем помочь?", "How can I help?"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (brain.integrations.none { it.connected }) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Бесплатные модели opencode работают сразу. Для остальных подключите OpenCode Go, Zen или другого провайдера.",
                "opencode's free models work right away. For the rest, connect OpenCode Go, Zen or another provider."),
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedButton({ brain.screens.add("providers") }) { Text(tr("Провайдеры", "Providers")) }
        }
    }
}

@Composable
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue") // This lint version misses the value assignment with Kotlin 2.
fun Thumb(path: String, size: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size * 3) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.size(size.dp).clip(RoundedCornerShape(14.dp))) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxSize()) {}
        bitmap?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(m: Message, canEdit: Boolean, onEdit: () -> Unit, onContext: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val pictures = m.files
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (pictures.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            for (p in pictures) AttachmentPreview(p, 120)
        }
        if (m.text.isNotEmpty()) Box {
            val shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer, shape = shape,
                modifier = Modifier.widthIn(max = 320.dp).clip(shape).combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(tr("Копировать", "Copy")) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                    onClick = { clipboard.setText(AnnotatedString(m.text)); menu = false })
                if (canEdit) DropdownMenuItem(text = { Text(tr("Изменить", "Edit")) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(tr("Контекст и память", "Context & memory")) },
                    leadingIcon = { Icon(Icons.Outlined.Memory, null) }, onClick = { menu = false; onContext() })
            }
        }
    }
}

@Composable
private fun Dots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 10.dp)) {
        for (i in 0 until 3) {
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(8.dp).alpha(a)) {}
        }
    }
}

private fun seconds(ms: Long) = ((ms + 500) / 1000).coerceAtLeast(1)

private fun tokens(n: Long) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1000 -> "%.1fk".format(n / 1e3)
    else -> n.toString()
}

@Composable
private fun ErrorCard(e: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
private fun CompactionCard(m: Message) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Compress, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(tr("Контекст сжат", "Context compacted"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(tr("Предыдущие сообщения сохранены в памяти", "Previous messages saved to memory"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded && m.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Text(m.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun Thoughts(p: Part.Reasoning, live: Boolean) {
    var open by remember { mutableStateOf(false) }
    if (p.text.isBlank()) return
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                if (live) tr("Думает…", "Thinking…")
                else if (p.ms > 0) tr("Думал ${seconds(p.ms)} с", "Thought for ${seconds(p.ms)} s")
                else tr("Размышления", "Thoughts"),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open || live) {
            Row(Modifier.height(IntrinsicSize.Min).padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                Box(Modifier.padding(end = 12.dp).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Text(
                    if (live && !open) p.text.takeLast(600) else p.text.trim(),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (live && !open) 6 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What a tool did, in one line; the details open on tap. */
private fun summary(t: Part.Tool): String {
    val i = t.input
    val arg = i.str("command") ?: i.str("filePath")?.substringAfterLast('/') ?: i.str("path")?.substringAfterLast('/')
        ?: i.str("pattern") ?: i.str("url") ?: i.str("query") ?: i.str("description")
    return when (t.name) {
        "shell" -> if (arg != null) "$ $arg" else "$ (shell)"
        "subagent" -> "↳ " + (i.str("role") ?: i.str("agent") ?: "subagent")
        else -> if (arg != null) "${t.name}: $arg" else t.name
    }
}

@Composable
private fun ToolCard(t: Part.Tool) {
    var open by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { open = !open }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (t.status) {
                    "completed" -> Icon(Icons.Outlined.TaskAlt, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    "error" -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    else -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                Text(summary(t), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(open) {
                val body = listOfNotNull(t.input.toString(2).takeIf { t.input.length() > 0 }, t.error, t.output.ifBlank { null })
                    .joinToString("\n\n")
                Text(body.take(6000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun Answer(m: Message, live: Boolean, last: Boolean, again: () -> Unit, canRetry: Boolean) {
    val clipboard = LocalClipboardManager.current
    val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n\n") { it.text }.trim()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.parts.forEachIndexed { i, p ->
            when (p) {
                is Part.Reasoning -> Thoughts(p, live && i == m.parts.lastIndex)
                is Part.Text -> {
                    // some models write their thinking into the answer itself, between <think> tags
                    val t = p.text.trimStart()
                    if (t.startsWith("<think>")) {
                        val end = t.indexOf("</think>")
                        val thought = if (end < 0) t.removePrefix("<think>") else t.substring(7, end)
                        Thoughts(Part.Reasoning(thought, 0), live && end < 0)
                        val rest = if (end < 0) "" else t.substring(end + 8).trim()
                        if (rest.isNotEmpty()) SelectionContainer { Markdown(rest) }
                    } else if (p.text.isNotBlank()) SelectionContainer { Markdown(p.text.trim()) }
                }
                is Part.Tool -> ToolCard(p)
            }
        }
        if (live && m.parts.isEmpty()) Dots()
        m.error?.let { ErrorCard(it) }
        if (!live && (text.isNotEmpty() || last)) Row(verticalAlignment = Alignment.CenterVertically) {
            val small = Modifier.size(36.dp)
            val tint = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            if (text.isNotEmpty()) IconButton({ clipboard.setText(AnnotatedString(text)) }, small, colors = tint) {
                Icon(Icons.Outlined.ContentCopy, tr("Копировать", "Copy"), Modifier.size(18.dp))
            }
            if (last && canRetry) IconButton(again, small, colors = tint) {
                Icon(Icons.Outlined.Refresh, tr("Ответить заново", "Answer again"), Modifier.size(19.dp))
            }
            val tokenInfo = if (m.tokensOut > 0) "${tokens(m.tokensIn)} → ${tokens(m.tokensOut)}" else null
            val cacheInfo = if (m.tokensCacheRead > 0) "⚡${tokens(m.tokensCacheRead)}" else null
            val costInfo = if (m.cost > 0.0) String.format(Locale.US, "$%.3f", m.cost) else null
            val info = listOfNotNull(m.model, tokenInfo, cacheInfo, costInfo).joinToString(" · ")
            Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun Composer(
    brain: Brain,
    text: String,
    onText: (String) -> Unit,
    pictures: MutableList<String>,
    editing: Message?,
    cancelEdit: () -> Unit,
    running: Boolean,
    onPick: (List<android.net.Uri>) -> Unit,
    send: () -> Unit,
    stop: () -> Unit,
) {
    val model = brain.currentModel()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { onPick(it) }
    val canSend = brain.ready && (model != null || text.trimStart().startsWith("!")) && !brain.creating && brain.sending[brain.open] != true &&
        (editing == null || !running) && (text.isNotBlank() || pictures.isNotEmpty() || brain.draft().skills.isNotEmpty())
    val id = brain.open
    val queued = brain.queue[id].orEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
    ) {
        if (id != null && queued.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        tr("В очереди (${queued.size}): ", "Queued (${queued.size}): ") +
                            queued.first().optJSONObject("payload")?.str("text").orEmpty().ifBlank { tr("Вложение", "Attachment") },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    IconButton({
                        brain.takeQueued(id, queued.first()) { item ->
                            val payload = item.optJSONObject("payload")
                            cancelEdit()
                            if (payload != null) {
                                onText(payload.str("text").orEmpty())
                                pictures.clear()
                                payload.optJSONArray("files").objects().forEach { f ->
                                    f.str("uri")?.removePrefix("file://")?.takeIf { it.startsWith("/") }?.let { pictures.add(it) }
                                }
                                val draft = brain.draft()
                                draft.skills.clear()
                                payload.optJSONArray("skills").objects().forEach { s -> s.str("id")?.let { draft.skills.add(it) } }
                                draft.agents.clear()
                                payload.optJSONArray("agents").objects().forEach { a -> a.str("name")?.let { draft.agents.add(it) } }
                                brain.saveDraft()
                            }
                        }
                    }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, tr("Убрать", "Remove"), Modifier.size(14.dp))
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(6.dp)) {
                val draft = brain.draft()
                if (draft.skills.isNotEmpty() || draft.agents.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (skill in draft.skills.toList()) androidx.compose.material3.InputChip(true, { draft.skills.remove(skill); brain.saveDraft() },
                        label = { Text("@$skill") }, trailingIcon = { Icon(Icons.Outlined.Close, tr("Убрать контекст", "Remove context"), Modifier.size(14.dp)) })
                    for (agent in draft.agents.toList()) androidx.compose.material3.InputChip(true, { draft.agents.remove(agent); brain.saveDraft() },
                        label = { Text("@$agent") }, trailingIcon = { Icon(Icons.Outlined.Close, tr("Убрать контекст", "Remove context"), Modifier.size(14.dp)) })
                }
                brain.localStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
                editing?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(tr("Изменение сообщения", "Editing a message"), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        IconButton(cancelEdit, Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, tr("Отмена", "Cancel"), Modifier.size(18.dp)) }
                    }
                }
                if (pictures.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in pictures.toList()) Box {
                        AttachmentPreview(p, 64)
                        Surface(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape,
                            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).clickable { pictures.remove(p) }) {
                            Icon(Icons.Outlined.Close, null, tint = Color.White, modifier = Modifier.padding(3.dp))
                        }
                    }
                }
                TextField(
                    value = text, onValueChange = onText,
                    placeholder = { Text(tr("Сообщение", "Message")) },
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InputActions(brain, text, onText, onPick)
                    if (model != null && model.variants.isNotEmpty()) ThinkingChip(brain)
                    AgentChip(brain)
                    if (editing == null) Box(Modifier.weight(1f)) { FolderChip(brain) } else Spacer(Modifier.weight(1f))
                    if (running) FilledIconButton(stop) { Icon(Icons.Outlined.Stop, tr("Остановить", "Stop")) }
                    FilledIconButton(send, enabled = canSend) {
                        Icon(Icons.AutoMirrored.Outlined.Send, tr("Отправить", "Send"), Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingChip(brain: Brain) {
    val model = brain.currentModel() ?: return
    var menu by remember { mutableStateOf(false) }
    val now = brain.variantOf(model)
    Box {
        Surface(
            color = if (now.isEmpty()) Color.Transparent else MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { menu = true },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp))
                Text(thinkingLabel(now), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
            }
        }
        DropdownMenu(menu, { menu = false }) {
            Text(tr("Размышления", "Thinking"), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            for (v in listOf("") + model.variants) DropdownMenuItem(
                text = { Text(thinkingLabel(v), fontWeight = if (v == now) FontWeight.Bold else FontWeight.Normal) },
                onClick = { brain.setVariant(model, v); menu = false },
            )
        }
    }
}

/** opencode asking before a tool runs, or a question the model put as a form. */
@Composable
private fun Asks(brain: Brain) {
    val ask = brain.asks.firstOrNull { brain.rootSession(it.session) == brain.rootSession(brain.open) }
    if (ask != null) {
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Outlined.Build, null) },
            title = { Text(tr("Разрешить: ${ask.action}?", "Allow ${ask.action}?")) },
            text = {
                Column {
                    if (ask.session != brain.open) Text(brain.session(ask.session)?.title ?: ask.session,
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    ask.message?.let { Text(it) }
                    for (r in ask.resources.take(6)) Text(r, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                Row {
                    TextButton({ brain.reply(ask, "always") }) { Text(tr("Всегда", "Always")) }
                    TextButton({ brain.reply(ask, "once") }) { Text(tr("Разрешить", "Allow")) }
                }
            },
            dismissButton = { TextButton({ brain.reply(ask, "reject") }) { Text(tr("Запретить", "Deny")) } },
        )
        return
    }
    val form = brain.forms.firstOrNull { brain.rootSession(it.str("sessionID")) == brain.rootSession(brain.open) && brain.hiddenForms[it.str("id")] != true } ?: return
    val id = form.getString("id")
    FormDialog(form, error = brain.formErrors[id], draft = brain.formAnswers[id],
        onAnswer = { brain.answer(form, it) }, onCancel = { brain.dismiss(form) })
}
