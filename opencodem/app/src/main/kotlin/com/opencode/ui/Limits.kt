package com.opencode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.Brain
import com.opencode.data.objects
import com.opencode.data.str
import com.opencode.tr
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/** When a window resets, said the way a person would: in 3 h 20 min, or a date when it is days off. */
private fun resets(at: Long?): String? {
    if (at == null || at <= 0) return null
    val left = at - System.currentTimeMillis()
    if (left <= 0) return tr("сбросился", "has reset")
    val min = left / 60_000
    return when {
        min < 60 -> tr("сброс через $min мин", "resets in $min min")
        min < 24 * 60 -> tr("сброс через ${min / 60} ч ${min % 60} мин", "resets in ${min / 60} h ${min % 60} min")
        else -> tr("сброс ", "resets ") + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
    }
}

private fun iso(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

@Composable
private fun Meter(title: String, percent: Double?, reset: Long?, limited: Boolean = false) {
    val p = (percent ?: 0.0).coerceIn(0.0, 100.0)
    val colour = when {
        limited || p >= 95 -> MaterialTheme.colorScheme.error
        p >= 75 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (percent == null) "—" else "${p.toInt()}%", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = colour)
        }
        LinearProgressIndicator({ (p / 100).toFloat() }, Modifier.fillMaxWidth().height(8.dp), color = colour,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
        val note = listOfNotNull(if (limited) tr("лимит исчерпан", "limit reached") else null, resets(reset)).joinToString(" · ")
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp))
            content()
        }
    }
}

private fun money(v: Double) = "$" + "%.2f".format(v)

@Composable
fun LimitsScreen(brain: Brain, back: () -> Unit) {
    var round by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var claude by remember { mutableStateOf<Result<JSONObject?>?>(null) }
    var opencode by remember { mutableStateOf<Result<JSONObject?>?>(null) }
    val uri = LocalUriHandler.current
    val hasClaude = brain.integrations.firstOrNull { it.id == CLAUDE }?.connected == true && brain.claude.installed
    val hasOpencode = brain.integrations.any { (it.id == GO || it.id == ZEN) && it.connected }

    LaunchedEffect(round) {
        loading = true
        coroutineScope {
            val c = async { if (hasClaude) runCatching { brain.claude.usage() } else null }
            val o = async { if (hasOpencode) runCatching { brain.opencodeLimits() } else null }
            claude = c.await()
            opencode = o.await()
        }
        loading = false
    }

    Page(tr("Лимиты", "Limits"), back, actions = {
        if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
        else IconButton({ round++ }) { Icon(Icons.Outlined.Refresh, tr("Обновить", "Refresh")) }
    }) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (!hasClaude && !hasOpencode) Text(
                tr("Лимиты показываются для подписки Claude и для OpenCode Go или Zen. Подключите их в провайдерах.",
                    "Limits are shown for a Claude plan and for OpenCode Go or Zen. Connect them in Providers."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))

            if (hasClaude) Card("Claude") {
                val r = claude
                when {
                    r == null -> Text(tr("Спрашиваю Claude Code…", "Asking Claude Code…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    r.isFailure -> Text(r.exceptionOrNull()?.message ?: "?", color = MaterialTheme.colorScheme.error)
                    else -> ClaudeLimits(r.getOrNull())
                }
            }

            if (hasOpencode) Card("OpenCode") {
                val r = opencode
                when {
                    r == null -> Text(tr("Спрашиваю opencode…", "Asking opencode…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    r.isFailure -> Text(r.exceptionOrNull()?.message ?: "?", color = MaterialTheme.colorScheme.error)
                    r.getOrNull() == null -> {
                        Text(tr("opencode показывает лимиты Go и бюджеты Zen только при входе через аккаунт OpenCode, по одному ключу их не видно.",
                            "opencode shows the Go allowance and Zen budgets only when signed in with an OpenCode account; a key alone doesn't show them."),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ brain.screens.add("provider:$ZEN") }) { Text(tr("Войти в аккаунт OpenCode", "Sign in to OpenCode")) }
                    }
                    else -> OpencodeLimits(r.getOrNull()!!) { uri.openUri(it) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ClaudeLimits(u: JSONObject?) {
    val limits = u?.optJSONObject("rate_limits")
    if (u == null || limits == null) {
        Text(tr("Claude Code не вернул лимиты. Они есть только у подписки Claude (Pro, Max, Team), не у ключа API.",
            "Claude Code returned no limits. Only a Claude plan (Pro, Max, Team) has them, not an API key."),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    u.str("subscription_type")?.let {
        Text(tr("Подписка: ", "Plan: ") + it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val rows = buildList {
        fun window(key: String, title: String) {
            val w = limits.optJSONObject(key) ?: return
            add(Triple(title, if (w.isNull("utilization")) null else w.optDouble("utilization"), iso(w.str("resets_at"))))
        }
        window("five_hour", tr("Сессия (5 часов)", "Session (5 hours)"))
        window("seven_day", tr("Неделя, все модели", "Week, all models"))
        window("seven_day_opus", tr("Неделя, Opus", "Week, Opus"))
        window("seven_day_sonnet", tr("Неделя, Sonnet", "Week, Sonnet"))
        for (m in limits.optJSONArray("model_scoped").objects()) add(Triple(
            tr("Неделя, ", "Week, ") + (m.str("display_name") ?: "?"),
            if (m.isNull("utilization")) null else m.optDouble("utilization"), iso(m.str("resets_at"))))
    }
    for ((t, p, r) in rows) Meter(t, p, r)
    limits.optJSONObject("extra_usage")?.takeIf { it.optBoolean("is_enabled") }?.let { e ->
        val used = if (e.isNull("used_credits")) null else e.optDouble("used_credits")
        val limit = if (e.isNull("monthly_limit")) null else e.optDouble("monthly_limit")
        Meter(tr("Доп. использование", "Extra usage") + if (used != null && limit != null) " (${money(used / 100)} / ${money(limit / 100)})" else "",
            if (e.isNull("utilization")) null else e.optDouble("utilization"), null)
    }
}

@Composable
private fun OpencodeLimits(info: JSONObject, open: (String) -> Unit) {
    info.optJSONObject("workspace")?.str("name")?.let {
        Text(tr("Рабочее пространство: ", "Workspace: ") + it, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    info.optJSONObject("go")?.let { go ->
        Text("Go", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp))
        for ((key, title) in listOf("rolling" to tr("Скользящее окно", "Rolling window"), "weekly" to tr("Неделя", "Week"),
            "monthly" to tr("Месяц", "Month"))) {
            val m = go.optJSONObject(key) ?: continue
            Meter(title, m.optDouble("percent"), m.optLong("resetsAt"), m.optBoolean("limited"))
        }
    }
    val budgets = info.optJSONArray("budgets").objects()
    if (budgets.isNotEmpty()) {
        Text(tr("Бюджеты", "Budgets"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp))
        for (b in budgets) {
            val limit = b.optDouble("limit")
            val spent = b.optDouble("spent")
            val what = if (b.str("coverage") == "all") tr("все модели", "every model")
                else b.optJSONArray("models").objects().mapNotNull { it.str("name") }.take(3).joinToString(", ")
            Meter("${money(spent)} / ${money(limit)} · $what", if (limit > 0) spent / limit * 100 else null, b.optLong("resetsAt"))
        }
    }
    info.optJSONObject("workspace")?.str("usageURL")?.let { u ->
        TextButton({ open(u) }) { Text(tr("Подробно в консоли OpenCode", "Details in the OpenCode console")) }
    }
}
