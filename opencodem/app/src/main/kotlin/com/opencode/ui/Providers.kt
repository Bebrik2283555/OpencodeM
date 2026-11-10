package com.opencode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.Brain
import com.opencode.data.Integration
import com.opencode.data.multiple
import com.opencode.data.fieldVisible
import com.opencode.data.formDefaults
import com.opencode.data.formAnswer
import com.opencode.data.objects
import com.opencode.data.str
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, back: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (Modifier) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Назад", "Back")) } },
            actions = { actions() },
        )
        content(Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp))
}

const val GO = "opencode-go"
const val ZEN = "opencode"
const val CLAUDE = "claude-agent"

private fun blurb(id: String) = when (id) {
    GO -> tr("Подписка opencode: Kimi, GLM, DeepSeek, Qwen, MiniMax и другие открытые модели за фиксированную цену.",
        "opencode's subscription: Kimi, GLM, DeepSeek, Qwen, MiniMax and other open models for a flat price.")
    CLAUDE -> tr("Подписка Claude Pro или Max через официальный Claude Code.",
        "A Claude Pro or Max plan through the official Claude Code.")
    ZEN -> tr("Аккаунт opencode: бесплатные модели, а с балансом все модели Zen, включая Claude, GPT и Gemini.",
        "Your opencode account: the free models, and with credit every Zen model, Claude, GPT and Gemini among them.")
    else -> null
}

private fun keyPage(id: String) = when (id) {
    ZEN -> "https://opencode.ai/console"
    GO -> "https://opencode.ai/go"
    "openrouter" -> "https://openrouter.ai/settings/keys"
    "anthropic" -> "https://console.anthropic.com/settings/keys"
    "openai" -> "https://platform.openai.com/api-keys"
    "google" -> "https://aistudio.google.com/apikey"
    "deepseek" -> "https://platform.deepseek.com/api_keys"
    "xai" -> "https://console.x.ai"
    "mistral" -> "https://console.mistral.ai/api-keys"
    "groq" -> "https://console.groq.com/keys"
    else -> null
}

fun Modifier.clickableRounded(onClick: () -> Unit) = clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)

@Composable
fun ProvidersScreen(brain: Brain, back: () -> Unit) {
    LaunchedEffect(Unit) {
        runCatching { brain.loadProviders(allModels = true) }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) brain.notice = it.message }
    }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    Page(tr("Провайдеры", "Providers"), back, actions = {
        IconButton({ adding = true }) { Icon(Icons.Outlined.Add, tr("Свой провайдер", "Custom provider")) }
        IconButton({ brain.screens.add("config") }) { Icon(Icons.Outlined.Settings, "opencode.json") }
    }) { mod ->
        LazyColumn(mod.navigationBarsPadding().imePadding()) {
            val own = brain.integrations.filter { it.id in brain.customProviders }
            if (own.isNotEmpty()) {
                item { Section(tr("Мои провайдеры", "My providers")) }
                items(own, key = { "own:" + it.id }) { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { brain.screens.add("provider:${p.id}") }) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(p.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(p.id !in brain.disabledProviders, { on -> brain.toggleProvider(p.id, on) })
                    }
                }
            }
            item { Section("opencode") }
            for (id in listOf(GO, ZEN, CLAUDE)) {
                val p = brain.integrations.firstOrNull { it.id == id } ?: continue
                item(key = "top:$id") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                            .clickableRounded { brain.screens.add("provider:$id") },
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Logo(30)
                                Text(when (id) { ZEN -> "OpenCode Zen"; CLAUDE -> "Claude"; else -> p.name }, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).weight(1f))
                                if (p.connected) Icon(Icons.Outlined.CheckCircle, tr("Подключён", "Connected"),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            Text(blurb(id).orEmpty(), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }

            item(key = "phone") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                        .clickableRounded { brain.screens.add("local") },
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(tr("Модели на телефоне", "Models on the phone"), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        val n = brain.local.installed().size
                        Text(tr("Gemma 4, Qwen и другие, без интернета и бесплатно", "Gemma 4, Qwen and others, offline and free") +
                            if (n > 0) tr(" · скачано: $n", " · downloaded: $n") else "",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            val others = brain.integrations.filter { it.id != GO && it.id != ZEN && it.id != CLAUDE }
            val mine = others.filter { it.connected }
            if (mine.isNotEmpty()) {
                item { Section(tr("Подключённые", "Connected")) }
                items(mine, key = { "m:" + it.id }) { p -> Line(brain, p) }
            }
            item { Section(tr("Все провайдеры", "Every provider")) }
            item {
                OutlinedTextField(query, { query = it }, singleLine = true, shape = RoundedCornerShape(28.dp),
                    placeholder = { Text(tr("Поиск", "Search")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val q = query.trim().lowercase()
            val rest = others.filter { !it.connected && (it.id in brain.customProviders || it.key || it.oauth.isNotEmpty()) &&
                (q.isEmpty() || it.name.lowercase().contains(q) || it.id.contains(q)) }
            items(rest, key = { "a:" + it.id }) { p -> Line(brain, p) }
            item {
                Text(tr("Свои провайдеры (LM Studio, Ollama, прокси) можно добавить кнопкой + сверху или в opencode.json.",
                    "Your own providers (LM Studio, Ollama, proxies) can be added with + at the top or in opencode.json."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp))
            }
        }
    }
    if (adding) AddProviderDialog(brain) { adding = false }
}

@Composable
private fun AddProviderDialog(brain: Brain, close: () -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    val extraModels = remember { mutableStateListOf<Pair<String, String>>() }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!working) close() }, title = { Text(tr("Свой провайдер", "Custom provider")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Любой сервис с OpenAI-совместимым API. Например LM Studio, Ollama, OpenRouter или прокси.",
                "Any service with an OpenAI-compatible API, such as LM Studio, Ollama, OpenRouter or a proxy."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(id, { id = it.trim() }, label = { Text("ID") }, singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text(tr("Название", "Name")) }, singleLine = true)
            OutlinedTextField(url, { url = it.trim() }, label = { Text(tr("Базовый URL", "Base URL")) }, singleLine = true,
                placeholder = { Text("https://host/v1") })
            KeyField(key, { key = it }, tr("API-ключ", "API key"))
            OutlinedTextField(model, { model = it.trim() }, label = { Text(tr("ID модели", "Model ID")) }, singleLine = true)
            OutlinedTextField(modelName, { modelName = it }, label = { Text(tr("Имя модели", "Model name")) }, singleLine = true)
            for ((index, pair) in extraModels.toList().withIndex()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(pair.first, { extraModels[index] = it.trim() to pair.second },
                            label = { Text(tr("ID модели", "Model ID")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pair.second, { extraModels[index] = pair.first to it },
                            label = { Text(tr("Имя модели", "Model name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton({ extraModels.removeAt(index) }) { Icon(Icons.Outlined.Close, tr("Убрать модель", "Remove model")) }
                }
            }
            TextButton({ extraModels.add("" to "") }) { Icon(Icons.Outlined.Add, null); Text(tr("Ещё модель", "Another model"), Modifier.padding(start = 6.dp)) }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ working = true; scope.launch {
            try {
                brain.addProvider(id, name, url, key,
                    (listOf(model to modelName) + extraModels.filter { it.first.isNotBlank() }).filter { it.first.isNotBlank() })
                brain.loadProviders(allModels = true)
                close()
            } catch (e: Exception) { problem = e.message } finally { working = false }
        } }, enabled = !working && id.isNotBlank() && model.isNotBlank()) { Text(tr("Добавить", "Add")) } },
        dismissButton = { TextButton(close, enabled = !working) { Text(tr("Отмена", "Cancel")) } })
}

@Composable
private fun Line(brain: Brain, p: Integration) {
    Row(Modifier.fillMaxWidth().clickable { brain.screens.add("provider:${p.id}") }.padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            val ways = listOfNotNull(if (p.key) tr("ключ", "key") else null, if (p.oauth.isNotEmpty()) tr("вход", "sign-in") else null)
            Text(ways.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (p.connected) {
            Switch(p.id !in brain.disabledProviders, { on -> brain.toggleProvider(p.id, on) })
        }
    }
}

@Composable
fun KeyField(value: String, onValue: (String) -> Unit, label: String) {
    var shown by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value, onValue,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Row {
                IconButton({ clipboard.getText()?.text?.trim()?.let(onValue) }) { Icon(Icons.Outlined.ContentPaste, tr("Вставить", "Paste")) }
                IconButton({ shown = !shown }) { Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ProviderScreen(brain: Brain, id: String, back: () -> Unit) {
    val p = brain.integrations.firstOrNull { it.id == id } ?: return Page(id, back) {}
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    /** A sign-in under way: its attempt from opencode. */
    var attempt by remember { mutableStateOf<JSONObject?>(null) }
    var code by remember { mutableStateOf("") }

    fun run(block: suspend () -> Unit) {
        working = true
        problem = null
        scope.launch {
            try { block() } catch (e: Exception) { problem = e.message ?: e.toString() }
            working = false
        }
    }

    // The browser finishes the sign-in; opencode reports when it has the account.
    LaunchedEffect(attempt) {
        val a = attempt ?: return@LaunchedEffect
        if (a.str("mode") == "code") return@LaunchedEffect
        while (true) {
            delay(2000)
            when (runCatching { brain.oauthStatus(id, a.getString("attemptID")) }.getOrDefault("pending")) {
                "complete" -> { attempt = null; brain.loadProviders(); return@LaunchedEffect }
                "failed", "expired" -> { attempt = null; problem = tr("Вход не удался", "Sign-in failed"); return@LaunchedEffect }
            }
        }
    }

    Page(when (id) { ZEN -> "OpenCode Zen"; CLAUDE -> "Claude"; else -> p.name }, back) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            blurb(id)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Включён", "Enabled"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(id !in brain.disabledProviders, { on -> brain.toggleProvider(id, on) })
            }

            if (p.connected) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Text(tr("Подключён", "Connected"), modifier = Modifier.padding(start = 10.dp).weight(1f))
                    TextButton({ run { brain.disconnect(id) } }, enabled = !working) { Text(tr("Отключить", "Disconnect")) }
                }
            }

            if (id == CLAUDE) ClaudeSection(brain)

            if (!p.connected && id !in brain.customProviders) {
                for (m in p.oauth) OutlinedButton({
                    run {
                        val a = brain.oauth(id, m.id)
                        a.str("url")?.let { runCatching { uri.openUri(it) } }
                        attempt = a
                        code = ""
                    }
                }, enabled = !working && attempt == null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Login, null, Modifier.size(18.dp))
                    Text(m.label ?: tr("Войти", "Sign in"), Modifier.padding(start = 8.dp))
                }

                if (p.key) {
                    if (p.oauth.isNotEmpty()) Text(tr("или ключом:", "or with a key:"), style = MaterialTheme.typography.labelLarge)
                    keyPage(id)?.let { link ->
                        TextButton({ uri.openUri(link) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                            Text(tr("Получить ключ", "Get a key"), Modifier.padding(start = 8.dp))
                        }
                    }
                    KeyField(key, { key = it }, tr("API-ключ", "API key"))
                    Button({ run { brain.connectKey(id, key); key = "" } }, enabled = key.isNotBlank() && !working) {
                        Text(tr("Подключить", "Connect"))
                    }
                }
            } else if (p.connected && p.key) {
                var changeKey by remember { mutableStateOf(false) }
                TextButton({ changeKey = !changeKey }) {
                    Text(if (changeKey) tr("Скрыть ввод ключа", "Hide key input") else tr("Сменить API-ключ", "Change API key"))
                }
                if (changeKey) {
                    KeyField(key, { key = it }, tr("Новый API-ключ", "New API key"))
                    Button({ run { brain.connectKey(id, key); key = ""; changeKey = false } }, enabled = key.isNotBlank() && !working) {
                        Text(tr("Сохранить ключ", "Save key"))
                    }
                }
            }
            if (working) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            val models = brain.models.filter { it.provider == id }
            if (models.isNotEmpty()) {
                Section(tr("Модели", "Models"))
                for (m in models) {
                    androidx.compose.runtime.key(m.ref) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                                Text(m.name, style = MaterialTheme.typography.bodyLarge)
                                Text(listOfNotNull(m.id, contextLabel(m.context)).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(m.enabled, { on -> brain.toggleModel(id, m.id, on) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    attempt?.let { a ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Вход в браузере", "Sign in in the browser")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.str("instructions")?.let { SelectionContainer { Text(it) } }
                    a.str("url")?.let { u -> TextButton({ runCatching { uri.openUri(u) } }) { Text(tr("Открыть страницу", "Open the page")) } }
                    if (a.str("mode") == "code") OutlinedTextField(code, { code = it }, singleLine = true,
                        label = { Text(tr("Код со страницы", "Code from the page")) })
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(tr("Жду, пока вход завершится…", "Waiting for the sign-in…"), Modifier.padding(start = 10.dp))
                    }
                }
            },
            confirmButton = {
                if (a.str("mode") == "code") TextButton({
                    run { brain.oauthComplete(id, a.getString("attemptID"), code); attempt = null; brain.loadProviders() }
                }, enabled = code.isNotBlank()) { Text(tr("Готово", "Done")) }
            },
            dismissButton = { TextButton({ attempt = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** opencode.json as it is: providers, MCP servers, agents and the rest of opencode's settings. */
@Composable
fun ConfigScreen(brain: Brain, back: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) { brain.configFile.takeIf { it.exists() }?.readText() }
            ?: "{\n  \"\$schema\": \"https://opencode.ai/config.json\"\n}\n"
    }
    Page("opencode.json", back) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Настройки opencode как на компьютере: свои провайдеры, MCP-серверы, агенты. Можно вставить свой opencode.json целиком.",
                "opencode's settings, as on a computer: your own providers, MCP servers, agents. You can paste your whole opencode.json."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            val t = text
            if (t != null) OutlinedTextField(t, { text = it; result = null }, minLines = 14,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ clipboard.getText()?.text?.let { text = it; result = null } }) {
                    Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                    Text(tr("Вставить", "Paste"), Modifier.padding(start = 6.dp))
                }
                Button({
                    scope.launch {
                        result = runCatching { brain.saveConfig(text.orEmpty()); tr("Сохранено", "Saved") }
                            .getOrElse { tr("Ошибка: ${it.message}", "Error: ${it.message}") }
                    }
                }) { Text(tr("Сохранить", "Save")) }
            }
            result?.let { Text(it) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A question from the model or a provider, drawn from opencode's form fields. */
@Composable
fun FormDialog(form: JSONObject, error: String? = null, draft: JSONObject? = null,
               onAnswer: (JSONObject) -> Unit, onCancel: () -> Unit) {
    val fields = form.optJSONArray("fields").objects()
    val values = remember(form.str("id")) { mutableStateMapOf<String, Any>().apply { putAll(formDefaults(fields, draft)) } }
    var problem by remember(form.str("id")) { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(form.str("title") ?: tr("Вопрос", "A question")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (f in fields) {
                    val k = f.str("key") ?: continue
                    if (!fieldVisible(f, values)) continue
                    f.str("title")?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    f.str("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val options = f.optJSONArray("options").objects()
                    val many = multiple(f)
                    if (f.str("type") == "external") {
                        TextButton({ f.str("url")?.let { uri.openUri(it) } }) { Text(tr("Открыть", "Open")) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(values[k] == true, { values[k] = it })
                            Text(tr("Готово", "Done"))
                        }
                        continue
                    }
                    if (f.str("type") == "boolean") {
                        Checkbox(values[k] == true, { values[k] = it })
                        continue
                    }
                    if (options.isNotEmpty()) for (o in options) {
                        val v = o.str("value") ?: continue
                        val chosen = if (many) (values[k] as? List<*>)?.contains(v) == true else values[k] == v
                        Row(Modifier.fillMaxWidth().clickable {
                            values[k] = if (many) {
                                val now = (values[k] as? List<*>)?.filterIsInstance<String>().orEmpty()
                                if (v in now) now - v else now + v
                            } else v
                        }, verticalAlignment = Alignment.CenterVertically) {
                            if (many) Checkbox(chosen, null) else RadioButton(chosen, null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(o.str("label") ?: v)
                                o.str("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    if (options.isEmpty() || f.optBoolean("custom")) OutlinedTextField(
                        if (many) (values[k] as? List<*>)?.filter { v -> options.none { it.str("value") == v } }?.joinToString("\n").orEmpty()
                        else values[k]?.toString().orEmpty(),
                        { text -> values[k] = if (many) (values[k] as? List<*>)?.filter { v -> options.any { it.str("value") == v } }.orEmpty() +
                            text.lines().filter { it.isNotBlank() } else text },
                        label = { if (options.isNotEmpty()) Text(tr("Свой ответ", "Your own answer")) },
                        placeholder = { f.str("placeholder")?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
                }
                (problem ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                runCatching { formAnswer(fields, values) }.onSuccess(onAnswer).onFailure { problem = it.message }
            }) { Text(tr("Ответить", "Answer")) }
        },
        dismissButton = { TextButton(onCancel) { Text(tr("Пропустить", "Skip")) } },
    )
}
