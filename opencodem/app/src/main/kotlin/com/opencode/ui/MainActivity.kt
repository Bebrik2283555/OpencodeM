package com.opencode.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import com.opencode.engine.EngineService
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.opencode.Brain
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    private var brain by mutableStateOf<Brain?>(null)
    private var pending: Intent? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            brain = (binder as EngineService.LocalBinder).service.brain
            brain?.checkFiles()
            pending?.let { take(it) }
            pending = null
        }
        override fun onServiceDisconnected(name: ComponentName?) { brain = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        pending = if (savedInstanceState == null) intent else null
        EngineService.start(this)
        bindService(Intent(this, EngineService::class.java), connection, BIND_AUTO_CREATE)
        setContent {
            val state = brain
            if (state == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else MargyTheme(state.prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Root(state) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        brain?.checkFiles()
        // Bring the foreground notification back after "Close engine" and start a stopped engine.
        runCatching { EngineService.start(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** Something shared from another app: a new chat with it in the composer. */
    private fun take(intent: Intent?) {
        if (intent == null) return
        val brain = brain ?: run { pending = intent; return }
        intent?.getStringExtra("sessionID")?.let { brain.openSession(it); brain.screens.clear() }
        if (intent?.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val multiple = intent.action == Intent.ACTION_SEND_MULTIPLE
        val uri = if (multiple) null else if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        lifecycleScope.launch {
            try {
                @Suppress("DEPRECATION")
                val uris = ((if (multiple) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty() else emptyList()) + listOfNotNull(uri) +
                    (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData?.getItemAt(it)?.uri }).distinct()
                val files = uris.map { brain.importAttachment(it) }
                brain.openSession(null)
                brain.screens.clear()
                brain.shared = text to files
            } catch (e: Exception) { brain.notice = e.message ?: e.toString() }
        }
    }

    override fun onDestroy() { unbindService(connection); super.onDestroy() }
}

@Composable
private fun Root(brain: Brain) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val top = brain.screens.lastOrNull()
    BackHandler(top != null) { brain.screens.removeAt(brain.screens.lastIndex) }
    BackHandler(top == null && drawer.isOpen) { scope.launch { drawer.close() } }
    AnimatedContent(top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { screen ->
        val back = { if (brain.screens.isNotEmpty()) brain.screens.removeAt(brain.screens.lastIndex) }
        when {
            screen == null -> ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = { Drawer(brain) { scope.launch { drawer.close() } } },
            ) {
                ChatScreen(brain, openDrawer = { scope.launch { drawer.open() } })
            }
            screen == "providers" -> ProvidersScreen(brain, back)
            screen.startsWith("provider:") -> ProviderScreen(brain, screen.removePrefix("provider:"), back)
            screen == "config" -> ConfigScreen(brain, back)
            screen == "settings" -> SettingsScreen(brain, back)
            screen == "limits" -> LimitsScreen(brain, back)
            screen == "tools" -> ToolsScreen(brain, back)
            screen == "linux" -> LinuxScreen(brain, back)
            screen == "local" -> LocalScreen(brain, back)
            screen == "agents" -> AgentsScreen(brain, back)
            screen == "permissions" -> PermissionsScreen(brain, back)
            screen == "projects" -> ProjectsScreen(brain, back)
            screen == "project" -> ProjectScreen(brain, back)
            screen == "tasks" -> TasksScreen(brain, back)
            screen == "context" -> ContextScreen(brain, back)
            screen == "files" || screen == "files:attach" -> FilesScreen(brain, screen.endsWith(":attach"), back)
            screen == "changes" -> ChangesScreen(brain, back)
            screen == "terminal" -> TerminalScreen(brain, back)
            screen == "extensions" -> ExtensionsScreen(brain, back)
            screen == "backup" -> BackupScreen(brain, back)
        }
    }
}
