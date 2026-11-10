package com.opencode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ForkRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.Brain
import com.opencode.tr
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(brain: Brain, onDismiss: () -> Unit) {
    val id = brain.open
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var compacting by remember { mutableStateOf(false) }
    var forking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showSummary by remember { mutableStateOf(false) }

    val model = brain.currentModel()
    val lastAssistant = brain.messages.lastOrNull { it.role == "assistant" && it.tokensIn > 0 }
    val compactionMsg = brain.messages.lastOrNull { it.role == "compaction" }
    val compactionActive = compactionMsg != null &&
        brain.messages.indexOfLast { it.role == "compaction" } > brain.messages.indexOfLast { it.role == "assistant" && it.tokensIn > 0 }

    val currentTokens = brain.currentContextTokens()
    val freshIn = if (compactionActive) currentTokens else (lastAssistant?.tokensIn ?: 0L)
    val cacheRead = if (compactionActive) 0L else (lastAssistant?.tokensCacheRead ?: 0L)
    val cacheWrite = if (compactionActive) 0L else (lastAssistant?.tokensCacheWrite ?: 0L)
    val inputTotal = freshIn + cacheRead + cacheWrite
    val maxContext = model?.context?.takeIf { it > 0 } ?: 128_000L
    val ratio = if (maxContext > 0) (currentTokens.toFloat() / maxContext).coerceIn(0f, 1f) else 0f

    val totalCache = inputTotal
    val hitRate = if (totalCache > 0) ((cacheRead.toDouble() / totalCache) * 100).toInt() else 0
    val sessionCost = brain.messages.sumOf { it.cost }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Memory,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        tr("Контекст и память модели", "Context & Model Memory"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, tr("Закрыть", "Close"))
                }
            }

            // Card 1: Context Window Fill
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${(ratio * 100).toInt()}% " + tr("окна занято", "window used"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${currentTokens / 1000}k / ${maxContext / 1000}k",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val barColor = when {
                        ratio > 0.85f -> MaterialTheme.colorScheme.error
                        ratio > 0.65f -> Color(0xFFE69B00)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = barColor,
                    )
                    if (compactionActive) {
                        Text(
                            tr("⚡ Контекст сжат. Выжимка будет отправлена со следующим сообщением.",
                                "⚡ Context compressed. Summary will be sent with next prompt."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // Token breakdown chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TokenChip(tr("Входные", "Input"), "$inputTotal", MaterialTheme.colorScheme.primary)
                        TokenChip(tr("Выходные", "Output"), "${lastAssistant?.tokensOut ?: 0}", Color(0xFF6A1B9A))
                        TokenChip(tr("Кэш чтения", "Cache read"), "$cacheRead", Color(0xFF2E7D32))
                        TokenChip(tr("Кэш записи", "Cache write"), "$cacheWrite", Color(0xFF0277BD))
                    }
                    Text(
                        tr("«Входные» включают кэш. Провайдер может менять разбивку между шагами.",
                            "Input includes cache. The provider may change the split between steps."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // Card 2: Prompt Cache & Cost Efficiency
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        tr("Эффективность кэша и стоимость", "Cache Efficiency & Cost"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    tr("Попадания в кэш", "Cache Hit"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (cacheRead > 0) "$hitRate%" else tr("Не активен", "Inactive"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hitRate > 50) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    tr("Стоимость (200 сообщ.)", "Cost (last 200)"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (sessionCost > 0.0) String.format(Locale.US, "$%.4f", sessionCost) else "—",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            // Card 3: Active Context & Project Rules
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        tr("Активные слои контекста", "Active Context Layers"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (brain.rulesFile.exists()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Rule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "AGENTS.md (${brain.rulesFile.length() / 1024} ${tr("КБ", "KB")})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    val draftFiles = brain.draft().files
                    if (draftFiles.isNotEmpty()) {
                        Text(
                            tr("Прикреплено файлов: ${draftFiles.size}", "Attached files: ${draftFiles.size}"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val directory = brain.session()?.directory ?: brain.folder().absolutePath
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            brain.folderLabel(directory),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            // Card 4: Actions (Compact & Fork)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        if (id != null && !compacting) {
                            compacting = true
                            scope.launch {
                                runCatching { brain.compactSession(id) }
                                    .onFailure { problem = it.message }
                                compacting = false
                            }
                        }
                    },
                    enabled = id != null && !compacting,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Compress, null, Modifier.size(18.dp))
                    Text(
                        if (compacting) tr("Сжатие…", "Compacting…") else tr("Сжать", "Compact"),
                        Modifier.padding(start = 6.dp),
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (id != null && !forking) {
                            forking = true
                            scope.launch {
                                runCatching { brain.forkSession(id); onDismiss() }
                                    .onFailure { problem = it.message }
                                forking = false
                            }
                        }
                    },
                    enabled = id != null && !forking,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.ForkRight, null, Modifier.size(18.dp))
                    Text(tr("Ветка (Fork)", "Fork"), Modifier.padding(start = 6.dp))
                }
            }

            // Compaction summary if available
            if (compactionMsg != null && compactionMsg.text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSummary = !showSummary },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.History, null, Modifier.size(18.dp))
                                Text(
                                    tr("Последняя выжимка сжатия", "Latest Compaction Summary"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            Icon(
                                if (showSummary) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                null,
                            )
                        }
                        AnimatedVisibility(visible = showSummary) {
                            Text(
                                compactionMsg.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            problem?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TokenChip(label: String, count: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(count, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}
