package com.opencode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.data.Model
import com.opencode.tr

fun contextLabel(n: Long) = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1e6).removeSuffix(".0").removeSuffix(",0")}M"
    n >= 1000 -> "${n / 1000}k"
    n > 0 -> n.toString()
    else -> null
}

fun price(v: Double) = "$" + if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.', ',')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(brain: Brain, close: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { brain.loadProviders(allModels = true) }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) brain.notice = it.message }
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val current = brain.currentModel()
    val providers = brain.models.filter { brain.isModelAvailable(it) }.groupBy { it.provider }
    ModalBottomSheet(close, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text(tr("Поиск моделей", "Search models")) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val q = query.trim().lowercase()
            fun matches(m: Model) = q.isEmpty() || m.name.lowercase().contains(q) || m.id.lowercase().contains(q) ||
                brain.providerName(m.provider).lowercase().contains(q)
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                if (providers.isEmpty()) item {
                    Text(tr("opencode пока не нашёл ни одной модели.", "opencode has found no models yet."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
                if (q.isEmpty()) {
                    val favourites = brain.prefs.favourites.mapNotNull { brain.model(it)?.takeIf { m -> brain.isModelAvailable(m) } }
                    if (favourites.isNotEmpty()) {
                        item { Header(tr("Избранное", "Favourites")) }
                        items(favourites, key = { "f:" + it.ref }) { ModelRow(brain, it, current, close) }
                    }
                    val recent = brain.prefs.recent.mapNotNull { brain.model(it)?.takeIf { m -> brain.isModelAvailable(m) } }
                        .filter { m -> m.ref !in brain.prefs.favourites }.take(4)
                    if (recent.isNotEmpty()) {
                        item { Header(tr("Недавние", "Recent")) }
                        items(recent, key = { "r:" + it.ref }) { ModelRow(brain, it, current, close) }
                    }
                }
                for ((p, all) in providers) {
                    val models = all.filter(::matches)
                    if (models.isEmpty()) continue
                    item(key = "p:$p") { Header(brain.providerName(p)) }
                    items(models, key = { it.ref }) { ModelRow(brain, it, current, close) }
                }
            }
            TextButton({ close(); brain.screens.add("providers") }, Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                Icon(Icons.Outlined.Hub, null, Modifier.size(18.dp))
                Text(tr("Провайдеры", "Providers"), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ModelRow(brain: Brain, m: Model, current: Model?, close: () -> Unit) {
    val chosen = current?.ref == m.ref
    Row(
        Modifier.fillMaxWidth().clickable { brain.pick(m); close() }.padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f, fill = false))
                if (chosen) Icon(Icons.Outlined.Check, null, Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            val bits = listOfNotNull(
                contextLabel(m.context),
                if (m.costIn != null && m.costOut != null) {
                    if (m.costIn == 0.0 && m.costOut == 0.0) tr("бесплатно", "free") else "${price(m.costIn)} / ${price(m.costOut)}"
                } else null,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.variants.isNotEmpty()) Icon(Icons.Outlined.Psychology, null, Modifier.size(14.dp).padding(end = 2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (m.images) Icon(Icons.Outlined.Image, null, Modifier.size(14.dp).padding(end = 2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(bits.joinToString(" · ").ifEmpty { m.id }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
            }
        }
        val fav = m.ref in brain.prefs.favourites
        IconButton({ brain.favourite(m) }) {
            Icon(if (fav) Icons.Outlined.Star else Icons.Outlined.StarOutline, tr("В избранное", "Favourite"),
                tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
    }
}
