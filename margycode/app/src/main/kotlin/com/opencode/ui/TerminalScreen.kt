package com.opencode.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.opencode.Brain
import com.opencode.engine.TerminalSession
import com.opencode.tr
import org.json.JSONObject

@Composable
fun TerminalScreen(brain: Brain, back: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(brain.terminals.lastOrNull()?.id) }
    var mode by rememberSaveable { mutableStateOf("native") }
    var error by remember { mutableStateOf<String?>(null) }
    fun create() { runCatching { brain.newTerminal(mode) }.onSuccess { selected = it.id }.onFailure { error = it.message } }
    Page(tr("Терминал", "Terminal"), back, actions = {
        IconButton(::create) { Icon(Icons.Outlined.Add, tr("Новая вкладка", "New tab")) }
    }) { mod ->
        Column(mod.navigationBarsPadding().imePadding()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (value in listOf("native", "alpine")) FilterChip(mode == value, { mode = value }, label = { Text(value) })
                for (tab in brain.terminals) InputChip(selected == tab.id, { selected = tab.id }, label = { Text(tab.title) },
                    trailingIcon = { IconButton({ brain.closeTerminal(tab); selected = brain.terminals.lastOrNull()?.id }, Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, tr("Закрыть терминал", "Close terminal"), Modifier.size(16.dp)) } })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            val terminal = brain.terminals.firstOrNull { it.id == selected }
            if (terminal == null) TextButton(::create, Modifier.fillMaxWidth()) { Text(tr("Открыть терминал", "Open terminal")) }
            else {
                key(terminal.id) {
                    Surface(color = androidx.compose.ui.graphics.Color(0xFF101512), modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TerminalView(terminal, Modifier.fillMaxSize())
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for ((label, key) in listOf("Ctrl+C" to "\u0003", "Tab" to "\t", "Esc" to "\u001b", "^" to "\u001b[A", "v" to "\u001b[B", "Enter" to "\r"))
                        TextButton({ terminal.write(key) }) { Text(label) }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalView(session: TerminalSession, modifier: Modifier) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    AndroidView(modifier = modifier, factory = {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val file = request?.url?.lastPathSegment ?: return null
                    if (file !in setOf("xterm.js", "addon-fit.js", "xterm.css")) return null
                    return android.webkit.WebResourceResponse(if (file.endsWith("css")) "text/css" else "application/javascript", "utf-8", context.assets.open("terminal/$file"))
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun input(text: String) { session.write(text) }
                @JavascriptInterface fun resize(cols: Int, rows: Int) { session.resize(cols, rows) }
                @JavascriptInterface fun ready() { post { ready = true } }
            }, "Android")
            setBackgroundColor(0xFF101512.toInt())
            loadDataWithBaseURL("https://opencode-terminal.invalid/", context.assets.open("terminal/index.html").bufferedReader().use { it.readText() }, "text/html", "UTF-8", null)
            web = this
        }
    })
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        web?.evaluateJavascript("receive(${JSONObject.quote(session.output)},true)", null)
        session.stream.collect { chunk ->
            web?.evaluateJavascript("receive(${JSONObject.quote(chunk)},false)", null)
        }
    }
    LaunchedEffect(ready, scheme) { if (ready) web?.evaluateJavascript("colours('${"#%06X".format(scheme.surface.toArgb() and 0xffffff)}','${"#%06X".format(scheme.onSurface.toArgb() and 0xffffff)}')", null) }
    DisposableEffect(Unit) { onDispose { web?.removeJavascriptInterface("Android"); web?.destroy() } }
}

