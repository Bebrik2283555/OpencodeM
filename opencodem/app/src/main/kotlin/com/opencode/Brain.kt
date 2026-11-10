package com.opencode

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import com.opencode.data.Ask
import com.opencode.data.Integration
import com.opencode.data.Message
import com.opencode.data.Model
import com.opencode.data.Part
import com.opencode.data.Prefs
import com.opencode.data.Session
import com.opencode.data.canReconcileActivity
import com.opencode.data.staleActivity
import com.opencode.data.jsonOf
import com.opencode.data.objects
import com.opencode.data.str
import com.opencode.engine.Api
import com.opencode.engine.tailOf
import com.opencode.engine.ClaudeCode
import com.opencode.engine.Engine
import com.opencode.engine.Linux
import com.opencode.engine.EngineService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Everything the screens show and do. The work itself is opencode's, running inside the app. */
class Brain(private val ctx: Application, private val viewModelScope: CoroutineScope,
            private val onState: (Brain) -> Unit = {}) {
    private val app get() = ctx
    val engine = Engine(app)
    val api = Api(engine)
    val claude = ClaudeCode(app, engine)
    val linux = Linux(app, engine)
    val local = com.opencode.engine.Local(app)
    val updater = com.opencode.data.Updater(app)
    val terminals = mutableStateListOf<com.opencode.engine.TerminalSession>()
    fun newTerminal(mode: String): com.opencode.engine.TerminalSession {
        require(mode in listOf("native", "alpine"))
        check(mode != "alpine" || linux.installed) { "Install Alpine first" }
        val env = System.getenv().toMap() + engine.baseEnv() + linux.env(true) + mapOf("TERM" to "xterm-256color", "MARGY_CALL_RUNTIME" to mode)
        val terminal = com.opencode.engine.TerminalSession("$mode ${terminals.size + 1}",
            env.getValue("MARGY_SHELL"), projectDir().absolutePath, env, viewModelScope)
        terminals += terminal
        updateService()
        return terminal
    }
    fun closeTerminal(terminal: com.opencode.engine.TerminalSession) { terminal.close(); terminals.remove(terminal); updateService() }
    /** What went wrong starting the models on the phone, if anything. */
    var localError by mutableStateOf<String?>(null)
        private set

    init {
        engine.extraEnv = { linux.rootChroot = prefs.alpineRoot; claude.env() + linux.env(prefs.linux) + mapOf("OPENCODE_CONFIG_CONTENT" to builtIn(),
            "MARGY_DEFAULT_RUNTIME" to prefs.runtime, "MARGY_PHONE_CONTROL" to prefs.phoneControl.toString(),
            "MARGY_PERMISSION_MODE" to prefs.permissionMode) }
        engine.beforeStart = {
            claude.writeResolv()
            linux.writeResolv(claude.dns())
            writePrompt()
        }
    }

    val linuxActive get() = prefs.linux && linux.installed
    /** null while starting, "" when ready, else what went wrong. */
    var engineError by mutableStateOf<String?>(null)
        private set
    val ready get() = engineError == ""

    var prefs by mutableStateOf(Prefs.load(app).also { Lang.mode = it.language })
        private set
    var integrations by mutableStateOf<List<Integration>>(emptyList())
        private set
    var models by mutableStateOf<List<Model>>(emptyList())
        private set
    var defaultModel by mutableStateOf<String?>(null)
        private set
    var sessions by mutableStateOf<List<Session>>(emptyList())
        private set
    var agents by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var commands by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var disabledProviders by mutableStateOf<Set<String>>(emptySet())
        private set
    var customProviders by mutableStateOf<Set<String>>(emptySet())
        private set
    private var providerConfig: JSONObject? = null
    private val modelOrder = mutableMapOf<String, List<String>>()
    private val knownModels = mutableMapOf<String, Model>()
    fun configModels(provider: String): List<Model> {
        val models = providerConfig?.optJSONObject(provider)?.optJSONObject("models") ?: return emptyList()
        return models.keys().asSequence().map { key ->
            val ref = "$provider/$key"
            val existing = knownModels[ref]
            val m = models.optJSONObject(key) ?: JSONObject()
            existing?.copy(enabled = !m.optBoolean("disabled")) ?: Model(
                key, provider, m.str("name") ?: key, emptyList(), false, 0, null, null, 0, !m.optBoolean("disabled")
            )
        }.toList()
    }
    var open by mutableStateOf<String?>(null)
        private set
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    val busy = mutableStateMapOf<String, Boolean>()
    val sending = mutableStateMapOf<String, Boolean>()
    var creating by mutableStateOf(false)
        private set
    val queue = mutableStateMapOf<String, List<JSONObject>>()
    private val lastActivity = mutableMapOf<String, Long>()
    private val sessionsLock = Mutex()
    private val activityLock = Mutex()
    private val asksLock = Mutex()
    private val providersLock = Mutex()
    private val queueLock = Mutex()
    private val prefsLock = Mutex()
    private var catalogLoaded = false
    private var listener: Job? = null
    private var bootJob: Job? = null
    val downloads = mutableStateMapOf<String, Float>()
    private val downloadJobs = mutableMapOf<String, Job>()
    private var localRefresh: Job? = null
    var localRevision by mutableStateOf(0)
        private set
    var downloadError by mutableStateOf<String?>(null)
        private set
    var localStatus by mutableStateOf<String?>(null)
        private set
    var engineStopped by mutableStateOf(false)
        private set
    var transferBusy by mutableStateOf(false)
        private set
    var transferStatus by mutableStateOf<String?>(null)
        private set
    fun backup(uri: Uri, restore: Boolean) {
        if (transferBusy) return
        transferBusy = true
        viewModelScope.launch {
            try {
                val backup = com.opencode.data.Backup(ctx, this@Brain)
                transferStatus = if (restore) tr("Восстановлено чатов: ${backup.restore(uri)}", "Chats restored")
                    else { backup.export(uri); tr("Копия сохранена", "Backup saved") }
            } catch (e: Exception) { transferStatus = e.message ?: e.toString() }
            finally { transferBusy = false }
        }
    }
    private val sessionsCache get() = File(ctx.cacheDir, "sessions.json")
    private val modelsCache get() = File(ctx.cacheDir, "models.json")
    var asks by mutableStateOf<List<Ask>>(emptyList())
        private set
    var forms by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    val hiddenForms = mutableStateMapOf<String, Boolean>()
    val formErrors = mutableStateMapOf<String, String>()
    val formAnswers = mutableMapOf<String, JSONObject>()
    /** Errors from actions (sending, connecting), shown once. */
    var notice by mutableStateOf<String?>(null)

    val screens = mutableStateListOf<String>()
    var shared by mutableStateOf<Pair<String, List<String>>?>(null)
    private val drafts = mutableMapOf<String, com.opencode.data.Draft>()
    val editors = mutableMapOf<String, com.opencode.data.Editor>()
    fun draft(id: String? = open) = drafts.getOrPut(id ?: "new") {
        val raw = ctx.getSharedPreferences("drafts", 0).getString(id ?: "new", null)
        com.opencode.data.Draft(runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject()))
    }
    fun saveDraft(id: String? = open) {
        ctx.getSharedPreferences("drafts", 0).edit().putString(id ?: "new", draft(id).json().toString()).apply()
    }

    /**
     * Text streamed for the answer being written, in the order it began. Text and reasoning
     * are counted apart in opencode (each starts at ordinal 0), so a piece is known by all three.
     */
    private class Live(val message: String, val reasoning: Boolean, val ordinal: Int, val text: String)
    private data class LiveKey(val message: String, val reasoning: Boolean, val ordinal: Int)
    private val live = mutableStateListOf<Live>()
    private val livePending = LinkedHashMap<LiveKey, String>()
    private var liveFlush: Job? = null
    private var refetchMessages: Job? = null
    private var refetchSessions: Job? = null

    init {
        bootJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { JSONArray(sessionsCache.readText()).objects().map { Session.from(it) } }.getOrNull()
            }?.let { sessions = it }
            withContext(Dispatchers.IO) {
                runCatching {
                    val cached = JSONArray(modelsCache.readText()).objects().map { Model.from(it) }
                    for (m in cached) knownModels[m.ref] = m
                    val config = readConfig()
                    val disabledPol = config.optJSONArray("policies")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                        .orEmpty().filter { it.str("action") == "provider.use" && it.str("effect") == "deny" }
                        .mapNotNull { it.str("resource") }.toSet()
                    disabledProviders = disabledPol
                    val provs = config.optJSONObject("providers")
                    cached.map { m ->
                        val pDis = m.provider in disabledPol
                        val mDis = provs?.optJSONObject(m.provider)?.optJSONObject("models")?.optJSONObject(m.id)?.optBoolean("disabled") == true
                        m.copy(enabled = !pDis && !mDis)
                    }
                }.getOrNull()
            }?.let { models = it }
            boot()
        }
        viewModelScope.launch {
            delay(6000)
            updater.check(manual = false)
        }
        viewModelScope.launch {
            while (true) {
                delay(5000)
                val now = SystemClock.elapsedRealtime()
                if (ready && busy.any { (id, running) -> running && staleActivity(now, lastActivity[id], sending[id] == true) }) {
                    // Silence is normal during long tools/model loading: ask the server before clearing busy.
                    guard { loadActivity(); open?.let { loadQueue(it) }; loadAsks() }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val model = currentModel()
                localStatus = if (open != null && busy[open] == true && model != null && isLocal(model)) {
                    runCatching { local.progress(model.id) }.getOrDefault(tr("Ожидает локальную модель…", "Waiting for local model…"))
                } else null
            }
        }
    }


    private suspend fun boot() {
        engineStopped = false
        engineError = null
        withContext(Dispatchers.IO) {
            // leftovers from the removed Margy account/sync feature
            File(ctx.filesDir, "margy.token").delete()
            File(ctx.filesDir, "sync.json").delete()
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            for (dir in listOf(File(ctx.cacheDir, "attachments"), File(ctx.cacheDir, "camera"), File(engine.home, ".cache/phone-screens"))) {
                dir.listFiles().orEmpty().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
            }
        }
        // the local server goes first: opencode is told its address when it starts
        localError = try { local.start(prefs.localContext, 0); null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { e.message ?: e.toString() }
        try {
            engine.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The previous opencode may still be releasing its database; one clean retry.
            engine.stop()
            delay(1500)
            try {
                engine.start()
            } catch (retry: CancellationException) {
                throw retry
            } catch (retry: Exception) {
                engineError = retry.message ?: retry.toString()
                return
            }
        }
        engineError = ""
        updateService()
        listener = viewModelScope.launch { listen() }
        refreshAll()
        prefs.lastSession?.takeIf { session(it) != null }?.let { if (open == null) openSession(it) }
    }

    fun restart() {
        bootJob?.cancel()
        engineStopped = false
        listener?.cancel()
        engineError = null
        updateService()
        bootJob = viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.stop(); local.stop() }
            boot()
        }
    }

    fun stopEngine() {
        bootJob?.cancel()
        bootJob = null
        listener?.cancel()
        listener = null
        terminals.toList().forEach { closeTerminal(it) }
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        downloads.clear()
        busy.clear()
        engineError = tr("Движок остановлен. Нажмите «Ещё раз», чтобы запустить снова.", "Engine stopped. Tap 'Retry' to start again.")
        engineStopped = true
        updateService()
        viewModelScope.launch { withContext(Dispatchers.IO) { engine.stop(); local.stop() } }
    }

    fun engineTimedOut() {
        busy.clear()
        engineError = tr("Android остановил фоновый режим. Откройте приложение.", "Android stopped the background service. Open the app.")
        engineStopped = true
        updateService()
    }

    fun close() {
        terminals.toList().forEach { it.close() }
        viewModelScope.cancel()
        engine.stop()
        local.stop()
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = e.message ?: e.toString()
        }
    }

    suspend fun refreshAll() = coroutineScope {
        launch { guard { loadSessions(); loadActivity() } }
        launch { guard { loadProviders() } }
        launch { guard { loadAsks() } }
        launch { guard { loadAgents() } }
    }

    fun locationQuery(directory: String = session()?.directory ?: folder().absolutePath) = "?location[directory]=${Api.enc(directory)}"

    suspend fun loadAgents() {
        agents = api.get("/api/agent${locationQuery()}").getJSONArray("data").objects()
        commands = api.get("/api/command${locationQuery()}").getJSONArray("data").objects()
    }

    suspend fun loadProviders(allModels: Boolean = catalogLoaded) = providersLock.withLock {
        val location = open
        val query = "?location[directory]=${Api.enc(session()?.directory ?: folder().absolutePath)}"
        val connections = api.get("/api/integration$query").optJSONArray("data").objects().map { Integration.from(it) }
            .sortedBy { it.name.lowercase() }
        if (location != open) return@withLock
        integrations = connections
        if (allModels) {
            val raw = api.get("/api/model$query").getJSONArray("data")
            if (location != open) return@withLock
            val fetched = withContext(Dispatchers.IO) {
                raw.objects().map { Model.from(it) }
            }
            for (m in fetched) knownModels[m.ref] = m
            catalogLoaded = true
            withContext(Dispatchers.IO) { runCatching { modelsCache.writeText(raw.toString()) } }
        }
        val selected = api.get("/api/model/default$query").optJSONObject("data")?.let { Model.from(it) }
        if (location != open) return@withLock
        val config = withContext(Dispatchers.IO) { runCatching { readConfig() }.getOrNull() }
        disabledProviders = config?.optJSONArray("policies")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ?.filter { it.str("action") == "provider.use" && it.str("effect") == "deny" }
            ?.mapNotNull { it.str("resource") }?.toSet() ?: emptySet()
        val configured = config?.optJSONObject("providers")
        // Entries created only to disable a built-in model hold nothing but "models"; providers with
        // a package/npm are the ones the user added and are the only ones with a switch.
        customProviders = configured?.keys()?.asSequence()?.filter { id ->
            configured.optJSONObject(id)?.let { it.has("package") || it.has("npm") } == true
        }?.toSet() ?: emptySet()
        providerConfig = configured
        integrations = integrations + customProviders.filter { id -> integrations.none { it.id == id } }.map { id ->
            Integration(id, configured?.optJSONObject(id)?.str("name") ?: id, emptyList(), emptyList())
        }

        configured?.keys()?.forEach { pId ->
            val pModels = configured.optJSONObject(pId)?.optJSONObject("models") ?: return@forEach
            for (mId in pModels.keys().asSequence()) {
                val ref = "$pId/$mId"
                if (ref !in knownModels) {
                    val mObj = pModels.optJSONObject(mId) ?: JSONObject()
                    knownModels[ref] = Model(
                        id = mId,
                        provider = pId,
                        name = mObj.str("name") ?: mId,
                        variants = emptyList(),
                        images = false,
                        context = mObj.optJSONObject("limit")?.optLong("context") ?: 131072,
                        costIn = null,
                        costOut = null,
                        released = 0,
                        enabled = !mObj.optBoolean("disabled")
                    )
                }
            }
        }

        val installedLocal = local.installed().toSet()
        val localPrefix = "${com.opencode.engine.Local.PROVIDER}/"
        knownModels.keys.filter { it.startsWith(localPrefix) }
            .filter { it.removePrefix(localPrefix) !in installedLocal }
            .forEach { knownModels.remove(it) }

        val order = modelOrder.ifEmpty { prefs.modelOrder }
        val pool = (knownModels.values + models).distinctBy { it.ref }
            .filterNot { it.provider == com.opencode.engine.Local.PROVIDER && it.id !in installedLocal }
        models = pool.map { m ->
            val isExplicitlyDisabled = configured?.optJSONObject(m.provider)?.optJSONObject("models")?.optJSONObject(m.id)?.optBoolean("disabled") == true
            val isProviderDisabled = m.provider in disabledProviders
            m.copy(enabled = !isExplicitlyDisabled && !isProviderDisabled)
        }.sortedWith(compareBy<Model> { it.provider }
            .thenBy { m -> order[m.provider]?.indexOf(m.id)?.takeIf { it >= 0 } ?: Int.MAX_VALUE }
            .thenByDescending { it.released }
            .thenBy { it.name.lowercase() })
        modelOrder.clear()
        modelOrder.putAll(models.groupBy { it.provider }.mapValues { entry -> entry.value.map { it.id } })
        if (prefs.modelOrder != modelOrder) update { it.copy(modelOrder = modelOrder.toMap()) }
        defaultModel = selected?.ref
        if (selected != null && models.none { it.ref == selected.ref }) models = models + selected
    }

    private suspend fun loadSessions() = sessionsLock.withLock {
        val raw = api.get("/api/session").getJSONArray("data")
        val list = raw.objects()
            .map { Session.from(it) }
        sessions = list.sortedByDescending { it.updated }
        val ids = list.mapTo(HashSet()) { it.id }
        busy.keys.retainAll(ids)
        sending.keys.retainAll(ids)
        lastActivity.keys.retainAll(ids)
        queue.keys.retainAll(ids)
        for (s in list) if (s.id !in busy) busy[s.id] = s.busy
        updateService()
        withContext(Dispatchers.IO) { runCatching { sessionsCache.writeText(raw.toString()) } }
    }

    private suspend fun loadActivity() = activityLock.withLock {
        val before = lastActivity.toMap()
        val active = api.get("/api/session/active").getJSONObject("data")
        for (id in busy.keys.toSet() + active.keys().asSequence().toSet()) {
            if (!canReconcileActivity(sending[id] == true, before[id], lastActivity[id])) continue
            busy[id] = active.has(id)
            lastActivity[id] = SystemClock.elapsedRealtime()
        }
        updateService()
        soonMessages(settled = true)
    }

    private suspend fun loadQueue(id: String) = queueLock.withLock {
        queue[id] = api.get("/api/session/$id/inbox").getJSONArray("data").objects().filter { it.str("type") == "user" }
    }

    /** Removes a queued message from the server and hands it back so the composer can restore it. */
    fun takeQueued(id: String, item: JSONObject, onTaken: (JSONObject) -> Unit) = viewModelScope.launch {
        guard {
            api.delete("/api/session/$id/inbox/${item.getString("id")}")
            loadQueue(id)
            onTaken(item)
        }
    }

    private suspend fun loadMessages(id: String) {
        val raw = api.get("/api/session/$id/message?limit=200").optJSONArray("data").objects()
        val list = withContext(Dispatchers.IO) { raw.mapNotNull { Message.from(it, ::attachment) }.reversed() }
        if (open == id) messages = list
    }

    /** The picture itself when it is still on the phone, else its bytes from opencode written to the cache once. */
    private fun attachment(message: String, index: Int, f: JSONObject): String? {
        f.optJSONObject("source")?.str("uri")?.takeIf { it.startsWith("file://") }?.removePrefix("file://")
            ?.let { if (File(it).exists()) return it }
        val data = f.str("data") ?: return null
        val name = f.str("name")?.substringAfterLast('/')?.substringAfterLast('\\')?.take(100)
            ?: "attachment." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(f.str("mime").orEmpty()) ?: "bin")
        val out = File(ctx.cacheDir, "attachments/${message.replace(Regex("[^a-zA-Z0-9_-]"), "_")}_${index}_${name.replace(Regex("[\\p{Cntrl}]"), "_")}")
        if (!out.exists()) runCatching {
            out.parentFile?.mkdirs()
            out.writeBytes(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
        }
        if (out.exists()) out.setLastModified(System.currentTimeMillis())
        return out.absolutePath.takeIf { out.exists() }
    }

    private suspend fun loadAsks() = asksLock.withLock {
        val directories = (sessions.filter { busy[it.id] == true || it.id == open }.mapNotNull { it.directory } + folder().absolutePath).distinct()
        val permissions = mutableListOf<Ask>()
        val pending = mutableListOf<JSONObject>()
        for (directory in directories) {
            permissions += api.get("/api/permission/request${locationQuery(directory)}").optJSONArray("data").objects().map { Ask.from(it) }
            pending += api.get("/api/form${locationQuery(directory)}").optJSONArray("data").objects()
        }
        asks = permissions.distinctBy { it.id }
        forms = pending.distinctBy { it.str("id") }
        val formIds = forms.mapNotNull { it.str("id") }.toSet()
        hiddenForms.keys.retainAll(formIds)
        formAnswers.keys.retainAll(formIds)
        updateService()
    }

    private fun soonMessages(settled: Boolean = false) {
        val id = open ?: return
        refetchMessages?.cancel()
        refetchMessages = viewModelScope.launch {
            delay(120)
            guard { loadMessages(id) }
            // once a turn is over the stored messages hold everything that streamed
            if (settled && busy[id] != true) live.clear()
        }
    }

    private fun soonSessions() {
        refetchSessions?.cancel()
        refetchSessions = viewModelScope.launch {
            delay(300)
            guard { loadSessions() }
        }
    }

    private val eventChannel = Channel<JSONObject>(Channel.UNLIMITED)

    /** opencode's event stream: deltas go straight to the screen, anything lasting triggers a re-read. */
    private suspend fun listen() {
        val worker = viewModelScope.launch {
            for (e in eventChannel) runCatching { onEvent(e) }
        }
        try {
            while (true) {
                try {
                    api.events { e -> eventChannel.trySend(e) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                delay(1000)
                if (!engine.running) {
                    engineError = tr("opencode остановился", "opencode stopped") + "\n" + tailOf(engine.log, 1200)
                    return
                }
                updateService()
                guard { loadSessions(); loadActivity(); loadAsks(); open?.let { loadQueue(it) } }
                soonMessages()
            }
        } finally {
            worker.cancel()
        }
    }

    private fun onEvent(e: JSONObject) {
        val type = e.str("type") ?: return
        val data = e.optJSONObject("data") ?: JSONObject()
        val sid = data.str("sessionID")
        if (sid != null && type.startsWith("session.")) lastActivity[sid] = SystemClock.elapsedRealtime()
        when {
            type == "session.text.delta" || type == "session.reasoning.delta" -> {
                if (sid != open) return
                val message = data.str("assistantMessageID") ?: return
                val reasoning = type == "session.reasoning.delta"
                val ordinal = data.optInt("ordinal")
                val delta = data.str("delta") ?: ""
                queueLive(message, reasoning, ordinal, delta)
                return
            }
            type == "session.execution.started" && sid != null -> { busy[sid] = true; updateService() }
            type.startsWith("session.execution.") && sid != null -> {
                busy[sid] = false
                updateService()
                soonSessions()
                viewModelScope.launch { guard { loadQueue(sid) } }
                if (sid == open) { soonMessages(settled = true); return }
            }
            type.startsWith("session.inbox.") && sid != null -> {
                viewModelScope.launch { guard { loadQueue(sid) } }
            }
            type == "session.created" || type == "session.renamed" || type == "session.deleted" ||
                type == "session.model.selected" || type == "session.moved" -> soonSessions()
            type.startsWith("permission.") || type.startsWith("form.") || type.startsWith("session.form") -> {
                viewModelScope.launch { guard { loadAsks() } }
            }
            type.startsWith("integration.") || type.startsWith("credential.") || type.startsWith("config.") ||
                type.startsWith("provider.") || type.startsWith("model.") || type.startsWith("catalog.") -> {
                viewModelScope.launch { guard { loadProviders() } }
            }
        }
        if (sid != null && sid == open && type.startsWith("session.")) soonMessages()
    }

    private fun updateService() {
        onState(this)
    }

    fun downloadModel(id: String, urls: List<String>) {
        if (id in downloadJobs) return
        downloadError = null
        downloads[id] = 0f
        updateService()
        downloadJobs[id] = viewModelScope.launch {
            try {
                var updated = 0L
                local.download(id, urls) { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - updated >= 200 || done == total) {
                        updated = now
                        downloads[id] = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    }
                }
                localRevision++
                localRefresh?.cancel()
                localRefresh = viewModelScope.launch {
                    // Completing a download must not interrupt an answer in another chat.
                    while (busy.values.any { it } || sending.values.any { it } || creating) delay(1000)
                    restart()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { kotlin.coroutines.coroutineContext.ensureActive(); downloadError = e.message ?: e.toString() }
            finally { downloads.remove(id); downloadJobs.remove(id); updateService() }
        }
    }

    fun cancelDownload(id: String) {
        downloadJobs[id]?.cancel()
        viewModelScope.launch(Dispatchers.IO) { local.cancelDownload(id) }
    }

    fun removeLocalModel(id: String) {
        val ref = "${com.opencode.engine.Local.PROVIDER}/$id"
        val used = currentModel()?.ref == ref || defaultModel == ref || sessions.any { it.model == ref }
        local.remove(id)
        localRevision++
        knownModels.remove(ref)
        models = models.filterNot { it.ref == ref }
        if (defaultModel == ref) defaultModel = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    // keep the raw API objects: Model.json() would drop variants, images and cost
                    val cached = org.json.JSONArray(modelsCache.readText())
                    val kept = org.json.JSONArray()
                    for (i in 0 until cached.length()) {
                        val o = cached.optJSONObject(i) ?: continue
                        if (o.str("providerID") == com.opencode.engine.Local.PROVIDER && o.str("id") == id) continue
                        kept.put(o)
                    }
                    modelsCache.writeText(kept.toString())
                }
            }
            // Deleting a file must not interrupt an answer in another chat.
            while (busy.values.any { it } || sending.values.any { it } || creating) delay(1000)
            if (used) restart()
        }
    }

    fun currentContextTokens(): Long {
        val compactionIndex = messages.indexOfLast { it.role == "compaction" }
        if (compactionIndex >= 0) {
            val postAssistant = messages.subList(compactionIndex + 1, messages.size)
                .lastOrNull { it.role == "assistant" && it.tokensIn > 0 }
            if (postAssistant != null) {
                return postAssistant.tokensIn + postAssistant.tokensCacheRead + postAssistant.tokensCacheWrite
            }
            val comp = messages[compactionIndex]
            val summaryTokens = (comp.text.length / 3.5).toLong().coerceAtLeast(300L)
            val postUserTokens = messages.subList(compactionIndex + 1, messages.size)
                .filter { it.user }
                .sumOf { (it.text.length / 3.5).toLong().coerceAtLeast(10L) }
            return summaryTokens + postUserTokens + 600L
        }
        val lastAssistant = messages.lastOrNull { it.role == "assistant" && it.tokensIn > 0 } ?: return 0L
        return lastAssistant.tokensIn + lastAssistant.tokensCacheRead + lastAssistant.tokensCacheWrite
    }

    private fun clearLive() {
        liveFlush?.cancel()
        liveFlush = null
        livePending.clear()
        live.clear()
    }

    private fun queueLive(message: String, reasoning: Boolean, ordinal: Int, delta: String) {
        val key = LiveKey(message, reasoning, ordinal)
        livePending[key] = (livePending[key] ?: "") + delta
        if (liveFlush?.isActive == true) return
        liveFlush = viewModelScope.launch {
            delay(70)
            for ((k, text) in livePending) {
                val at = live.indexOfFirst { it.message == k.message && it.reasoning == k.reasoning && it.ordinal == k.ordinal }
                if (at < 0) live += Live(k.message, k.reasoning, k.ordinal, text)
                else live[at] = Live(k.message, k.reasoning, k.ordinal, live[at].text + text)
            }
            livePending.clear()
        }
    }

    /** The open chat's messages with whatever is still streaming laid over them. */
    fun shown(): List<Message> {
        if (live.isEmpty()) return messages
        val byMessage = live.toList().groupBy { it.message }
        fun part(l: Live): Part = if (l.reasoning) Part.Reasoning(l.text, 0) else Part.Text(l.text)
        val out = messages.map { m ->
            val pieces = byMessage[m.id] ?: return@map m
            val parts = m.parts.toMutableList()
            for (l in pieces) {
                // the ordinal-th stored part of the same kind, if opencode has kept it already
                val same = parts.withIndex().filter { (_, p) -> if (l.reasoning) p is Part.Reasoning else p is Part.Text }
                val stored = same.getOrNull(l.ordinal)
                if (stored == null) parts += part(l)
                else {
                    val old = when (val p = stored.value) { is Part.Text -> p.text; is Part.Reasoning -> p.text; else -> "" }
                    if (old.length < l.text.length) parts[stored.index] = part(l)
                }
            }
            Message(m.id, m.role, parts = parts, model = m.model, error = m.error, tokensIn = m.tokensIn,
                tokensOut = m.tokensOut, done = m.done)
        }.toMutableList()
        for ((id, pieces) in byMessage) if (out.none { it.id == id }) {
            out += Message(id, "assistant", parts = pieces.map { part(it) }, done = false)
        }
        return out
    }

    // ---- models ----

    fun model(ref: String?) = models.firstOrNull { it.ref == ref }

    fun providerName(id: String) = if (id == com.opencode.engine.Local.PROVIDER) tr("На телефоне", "On the phone")
        else integrations.firstOrNull { it.id == id }?.name ?: id

    fun session(id: String? = open) = sessions.firstOrNull { it.id == id }

    fun isModelAvailable(m: Model?): Boolean = m != null && m.enabled && m.provider !in disabledProviders

    fun currentModel(): Model? = session()?.model?.let { model(it)?.takeIf { m -> isModelAvailable(m) } }
        ?: model(prefs.model)?.takeIf { isModelAvailable(it) }
        ?: model(defaultModel)?.takeIf { isModelAvailable(it) }
        ?: models.firstOrNull { isModelAvailable(it) }

    fun variantOf(m: Model) = prefs.variants[m.ref]?.takeIf { it in m.variants } ?: ""

    fun update(change: (Prefs) -> Prefs) {
        prefs = change(prefs)
        Lang.mode = prefs.language
        val now = prefs
        viewModelScope.launch { prefsLock.withLock { withContext(Dispatchers.IO) { Prefs.save(ctx, now) } } }
    }

    private fun ref(m: Model) = jsonOf("id" to m.id, "providerID" to m.provider, "variant" to variantOf(m).ifEmpty { null })

    fun pick(m: Model) {
        update { it.copy(model = m.ref, recent = (listOf(m.ref) + it.recent.filter { r -> r != m.ref }).take(8)) }
        val id = open ?: return
        viewModelScope.launch { guard { api.post("/api/session/$id/model", jsonOf("model" to ref(m))); loadSessions() } }
    }

    fun setVariant(m: Model, v: String) {
        update { it.copy(variants = if (v.isEmpty()) it.variants - m.ref else it.variants + (m.ref to v)) }
        val id = open ?: return
        if (session()?.model == m.ref) viewModelScope.launch { guard { api.post("/api/session/$id/model", jsonOf("model" to ref(m))) } }
    }

    fun isLocal(m: Model) = m.provider == com.opencode.engine.Local.PROVIDER

    /** opencode's own agent with tools, or the plain chat for models on the phone unless asked otherwise. */
    fun agentFor(m: Model) = session()?.agent ?: if (isLocal(m) && !prefs.localTools) "chat" else prefs.agent

    fun optimisticProvider(id: String, on: Boolean) {
        disabledProviders = if (on) disabledProviders - id else disabledProviders + id
        models = models.map { if (it.provider == id) it.copy(enabled = on) else it }
    }

    fun optimisticModel(provider: String, model: String, on: Boolean) {
        val ref = "$provider/$model"
        knownModels[ref]?.let { knownModels[ref] = it.copy(enabled = on) }
        models = models.map { if (it.provider == provider && it.id == model) it.copy(enabled = on) else it }
    }

    /** Saved from the app's scope: leaving the screen must not cancel the write or show a cancellation as an error. */
    fun toggleProvider(id: String, on: Boolean) {
        optimisticProvider(id, on)
        viewModelScope.launch {
            try { setProviderEnabled(id, on) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { optimisticProvider(id, !on); notice = e.message }
        }
    }

    fun toggleModel(provider: String, model: String, on: Boolean) {
        optimisticModel(provider, model, on)
        viewModelScope.launch {
            try { setModelEnabled(provider, model, on) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { optimisticModel(provider, model, !on); notice = e.message }
        }
    }

    fun pickAgent(id: String) {
        update { it.copy(agent = id, localTools = id != "chat") }
        val sid = open ?: return
        viewModelScope.launch { guard { api.post("/api/session/$sid/agent", jsonOf("agent" to id)); loadSessions() } }
    }

    fun rootSession(id: String?): String? {
        var current = id ?: return null
        val seen = mutableSetOf<String>()
        while (seen.add(current)) current = session(current)?.parent ?: return current
        return id
    }

    fun setLocalTools(on: Boolean) {
        update { it.copy(localTools = on) }
        val id = open ?: return
        val m = currentModel() ?: return
        viewModelScope.launch { guard { api.post("/api/session/$id/agent", jsonOf("agent" to agentFor(m))); loadSessions() } }
    }

    fun favourite(m: Model) = update {
        it.copy(favourites = if (m.ref in it.favourites) it.favourites - m.ref else it.favourites + m.ref)
    }

    // ---- chats ----

    suspend fun moveSession(id: String, directory: String) {
        check(busy[id] != true) { tr("Сначала остановите ответ", "Stop the answer first") }
        val folder = if (directory.isBlank()) engine.workspace else File(directory)
        check(folder.isAbsolute) { tr("Укажите полный путь к папке", "Use an absolute directory path") }
        check(directory.isBlank() || folder.path.startsWith(ctx.filesDir.path) || filesGranted) {
            tr("Нужен доступ ко всем файлам", "All-files access is required")
        }
        withContext(Dispatchers.IO) {
            check(folder.isDirectory || folder.mkdirs()) { tr("Папка недоступна", "Directory is unavailable") }
        }
        api.post("/api/session/$id/move", jsonOf("directory" to folder.absolutePath))
        loadSessions()
        if (open == id) { loadAsks(); loadProviders(allModels = true) }
    }

    fun openSession(id: String?) {
        open = id
        update { it.copy(lastSession = id) }
        messages = emptyList()
        clearLive()
        forms = emptyList()
        if (id != null) {
            session(id)?.directory?.let { activeProject = it }
            viewModelScope.launch { guard { loadMessages(id); loadAsks(); loadQueue(id) } }
        }
        viewModelScope.launch { guard { loadProviders(allModels = id != null && model(session(id)?.model) == null) } }
        viewModelScope.launch { guard { loadAgents() } }
    }

    fun rename(s: Session, title: String) = viewModelScope.launch {
        guard { api.call("PATCH", "/api/session/${s.id}", jsonOf("title" to title.trim())); loadSessions() }
    }

    fun delete(s: Session) = viewModelScope.launch {
        guard {
            api.delete("/api/session/${s.id}")
            queue.remove(s.id)
            busy.remove(s.id)
            if (open == s.id) openSession(null)
            loadSessions()
        }
    }

    fun send(text: String, pictures: List<String>, onSent: () -> Unit = {}) {
        if (session()?.parent != null) return
        val m = currentModel()
        val value = text.trim()
        val shell = value.startsWith("!")
        if (m == null && !shell) return
        val target = open
        val selectedSkills = draft(target).skills.toList()
        val selectedAgents = draft(target).agents.toList()
        if (creating || (target != null && sending[target] == true)) return
        if (target == null) creating = true else sending[target] = true
        viewModelScope.launch {
            var id = target
            try { guard {
                if (id == null) {
                    val chosen = draft(target).folder.takeIf { it.isNotBlank() }?.let { File(it) }
                        ?: activeProject?.takeIf { it.isNotBlank() }?.let { File(it) }
                        ?: folder()
                    if (!chosen.isDirectory) check(withContext(Dispatchers.IO) { chosen.mkdirs() }) {
                        tr("Не удалось создать папку ${chosen.absolutePath}. Проверьте разрешение «Доступ ко всем файлам».",
                            "Could not create ${chosen.absolutePath}. Check the all-files permission.")
                    }
                    val dir = chosen
                    id = api.post("/api/session", jsonOf("model" to m?.let { ref(it) }, "agent" to (m?.let { agentFor(it) } ?: prefs.agent),
                        "location" to jsonOf("directory" to dir.absolutePath))).getJSONObject("data").getString("id")
                } else if (m != null && (session(id)?.model != m.ref || session(id)?.variant.orEmpty() != variantOf(m))) {
                    api.post("/api/session/$id/model", jsonOf("model" to ref(m)))
                }
                sending[id] = true
                val body = promptBody(value, pictures, selectedSkills, selectedAgents)
                when {
                    shell -> api.post("/api/session/$id/shell", jsonOf("command" to value.drop(1).trim()))
                    value.startsWith("/") && commands.any { it.str("name") == value.drop(1).substringBefore(' ').substringBefore('\n') } -> {
                        val name = value.drop(1).substringBefore(' ').substringBefore('\n')
                        body.put("name", name).put("text", value.drop(name.length + 1).trimStart())
                        api.post("/api/session/$id/command", body)
                    }
                    else -> prompt(id, value, pictures, selectedSkills, selectedAgents)
                }
                draft(target).skills.clear(); draft(target).agents.clear()
                onSent()
                saveDraft(target)
                if (target == null && open == target) {
                    open = id
                    update { it.copy(lastSession = id) }
                    messages = emptyList()
                    clearLive()
                }
                loadQueue(id)
                loadSessions()
                loadMessages(id)
            } } finally {
                id?.let { sending.remove(it) }
                creating = false
                guard { loadActivity() }
            }
        }
    }

    private fun promptBody(text: String, pictures: List<String>, skills: List<String>, agents: List<String>): JSONObject {
        val files = JSONArray()
        for (p in pictures) files.put(jsonOf("uri" to "file://$p", "name" to File(p).name))
        return jsonOf("text" to text.trim(), "files" to files.takeIf { it.length() > 0 }, "delivery" to "queue",
            "skills" to JSONArray(skills.map { jsonOf("id" to it) }), "agents" to JSONArray(agents.map { jsonOf("name" to it) }))
    }

    private suspend fun prompt(id: String, text: String, pictures: List<String>, skills: List<String> = emptyList(), agents: List<String> = emptyList()) {
        busy[id] = true
        lastActivity[id] = SystemClock.elapsedRealtime()
        updateService()
        api.post("/api/session/$id/prompt", promptBody(text, pictures, skills, agents))
    }

    fun stop() {
        val id = open ?: return
        stopSession(id)
    }

    fun stopSession(id: String) {
        viewModelScope.launch { guard {
            api.post("/api/session/$id/interrupt")
            busy[id] = false
            updateService()
            loadActivity()
            loadAsks()
        } }
    }

    /** Rolls the chat back to before a message of the person's, then sends it again, changed or not. */
    fun resumeSession(id: String) = viewModelScope.launch {
        if (session(id)?.parent != null) return@launch
        guard {
            val next = api.get("/api/session/$id/inbox").optJSONArray("data")?.optJSONObject(0)?.str("id")
            if (next != null) api.call("PATCH", "/api/session/$id/inbox/$next", jsonOf("delivery" to "steer"))
            else api.post("/api/session/$id/prompt", jsonOf("text" to tr("Продолжи", "Continue"), "delivery" to "queue"))
            loadActivity()
        }
    }

    suspend fun forkSession(id: String) {
        val made = api.post("/api/session/$id/fork").getJSONObject("data").getString("id")
        loadSessions(); openSession(made)
    }

    suspend fun compactSession(id: String) { api.post("/api/session/$id/compact"); loadMessages(id); loadActivity() }
    suspend fun stageRevert(id: String, message: String) { api.post("/api/session/$id/revert/stage", jsonOf("messageID" to message)); loadMessages(id) }
    suspend fun clearRevert(id: String) { api.delete("/api/session/$id/revert"); loadMessages(id) }
    suspend fun commitRevert(id: String) { api.post("/api/session/$id/revert/commit"); loadMessages(id) }

    fun resend(user: Message, text: String = user.text, pictures: List<String> = user.files) {
        val id = open ?: return
        if (session(id)?.parent != null) return
        if (busy[id] == true || sending[id] == true) return
        sending[id] = true
        viewModelScope.launch {
            try { guard {
                api.post("/api/session/$id/revert/stage", jsonOf("messageID" to user.id))
                api.post("/api/session/$id/revert/commit")
                clearLive()
                prompt(id, text, pictures.filter { File(it).exists() })
                loadMessages(id)
            } } finally { sending.remove(id); guard { loadActivity() } }
        }
    }

    fun again() {
        val last = messages.lastOrNull { it.user } ?: return
        resend(last)
    }

    fun reply(ask: Ask, decision: String) = viewModelScope.launch {
        guard {
            api.post("/api/session/${ask.session}/permission/${ask.id}/reply", jsonOf("decision" to decision))
            loadAsks()
        }
    }

    fun answer(form: JSONObject, answer: JSONObject) = settleForm(form, answer)

    fun dismiss(form: JSONObject) = settleForm(form, null)

    private fun settleForm(form: JSONObject, answer: JSONObject?) {
        val id = form.getString("id")
        if (hiddenForms[id] == true) return
        if (answer != null) formAnswers[id] = answer
        formErrors.remove(id)
        hiddenForms[id] = true
        viewModelScope.launch {
            try {
                val path = "/api/session/${form.getString("sessionID")}/form/$id"
                if (answer == null) api.delete(path) else api.post("$path/reply", jsonOf("answer" to answer))
                formAnswers.remove(id)
                guard { loadAsks() }
            } catch (e: CancellationException) {
                hiddenForms.remove(id)
                throw e
            } catch (e: Exception) {
                formErrors[id] = e.message ?: e.toString()
                hiddenForms.remove(id)
                if (open == form.str("sessionID") && forms.none { it.str("id") == id }) forms = listOf(form) + forms
            }
        }
    }

    // ---- export and import, in opencode's own transfer format ----

    suspend fun exportSession(id: String, to: Uri) {
        val data = api.get("/api/experimental/session/$id/export").getJSONObject("data")
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(to, "wt")!!.use { it.write(data.toString(1).toByteArray()) }
        }
    }

    fun importSession(from: Uri) = viewModelScope.launch {
        guard {
            val text = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(from)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val o = JSONObject(text).let { it.optJSONObject("data") ?: it }
            if (!o.has("info") || !o.has("messages")) error(tr("Это не выгрузка диалога opencode", "This isn't an opencode chat export"))
            val body = jsonOf("info" to o.getJSONObject("info"), "messages" to o.getJSONArray("messages"),
                "location" to jsonOf("directory" to folder().absolutePath))
            val made = try {
                api.post("/api/experimental/session/import", body)
            } catch (e: com.opencode.engine.ApiError) {
                if (e.code == 409) error(tr("Этот диалог уже есть в приложении", "This chat is already in the app")) else throw e
            }
            loadSessions()
            made.optJSONObject("data")?.str("id")?.let { openSession(it) }
        }
    }

    // ---- providers ----

    suspend fun connectKey(integration: String, key: String) {
        api.post("/api/integration/${Api.enc(integration)}/connect/key", jsonOf("key" to key.trim()))
        loadProviders()
    }

    suspend fun disconnect(integration: String) {
        val creds = api.get("/api/credential").optJSONArray("data").objects().filter { it.str("integrationID") == integration }
        for (c in creds) api.delete("/api/credential/${Api.enc(c.str("id") ?: continue)}")
        loadProviders()
    }

    /** Starts a sign-in through the browser; returns the attempt (url, instructions, mode). */
    suspend fun oauth(integration: String, method: String?): JSONObject =
        api.post("/api/integration/${Api.enc(integration)}/connect/oauth", jsonOf("methodID" to method)).getJSONObject("data")

    suspend fun oauthStatus(integration: String, attempt: String): String =
        api.get("/api/integration/${Api.enc(integration)}/connect/oauth/${Api.enc(attempt)}").optJSONObject("data")?.str("status") ?: "pending"

    suspend fun oauthComplete(integration: String, attempt: String, code: String) {
        api.post("/api/integration/${Api.enc(integration)}/connect/oauth/${Api.enc(attempt)}/complete", jsonOf("code" to code.trim()))
    }

    /** Tells opencode the person chose Claude Code, once `claude auth login` has gone through. */
    suspend fun connectClaude() {
        api.post("/api/integration/${ClaudeCode.ID}/connect/external", jsonOf("methodID" to ClaudeCode.METHOD))
        // opencode asks Claude Code for its models in the background; they come a few seconds later
        repeat(20) {
            loadProviders()
            if (models.any { it.provider == ClaudeCode.ID }) return
            delay(1500)
        }
    }

    /** opencode's own limits: the Go allowance and Console budgets, when signed in to an opencode account. */
    suspend fun opencodeLimits(): JSONObject? = api.get("/api/experimental/usage/limits").optJSONObject("data")

    // ---- skills and MCP servers ----

    val skillsDir get() = File(engine.config, "skills")

    suspend fun skills(): List<JSONObject> = api.get("/api/skill${locationQuery()}").optJSONArray("data").objects()

    suspend fun mcpServers(): List<JSONObject> = api.get("/api/mcp${locationQuery()}").optJSONArray("data").objects()

    /** A skill from a file: a SKILL.md alone, or a zip with one (and its scripts and references). */
    suspend fun installSkill(uri: Uri, name: String) {
        withContext(Dispatchers.IO) {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (name.endsWith(".zip", true) || (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())) {
                val staging = File.createTempFile("skill-", "", ctx.cacheDir).apply { delete(); mkdirs() }
                try {
                    val files = com.opencode.data.unpack(bytes.inputStream(), staging)
                    val skillFile = files.filter { it.name.equals("SKILL.md", true) }
                        .minByOrNull { it.toPath().nameCount }
                        ?: error(tr("В архиве нет SKILL.md", "There's no SKILL.md in the archive"))
                    val root = skillFile.parentFile ?: staging
                    val folderName = if (root != staging) root.name else name.substringBeforeLast('.').ifBlank { "skill" }
                    val safeName = folderName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-")
                    val folder = File(skillsDir, safeName)
                    folder.deleteRecursively()
                    root.copyRecursively(folder, overwrite = true)
                } finally {
                    staging.deleteRecursively()
                }
            } else {
                val text = bytes.toString(Charsets.UTF_8)
                val title = Regex("(?m)^name:\\s*[\"']?([^\"'\\n]+)").find(text)?.groupValues?.get(1)?.trim()
                    ?: name.substringBeforeLast('.').takeIf { it != "SKILL" } ?: "skill"
                val folder = File(skillsDir, title.lowercase().replace(Regex("[^a-z0-9._-]+"), "-"))
                folder.mkdirs()
                File(folder, "SKILL.md").writeText(text)
            }
        }
        reload()
    }

    /** Only the skills that live in the app's own skills folder can be removed from here. */
    fun removable(skillPath: String) = File(skillPath).absolutePath.startsWith(skillsDir.absolutePath + "/")

    suspend fun removeSkill(skillPath: String) {
        if (!removable(skillPath)) return
        withContext(Dispatchers.IO) { File(skillPath).parentFile?.deleteRecursively() }
        reload()
    }

    suspend fun readConfig(): JSONObject = withContext(Dispatchers.IO) {
        val text = configFile.takeIf { it.exists() }?.readText().orEmpty()
        if (text.isBlank()) JSONObject().put("\$schema", "https://opencode.ai/config.json")
        else JSONObject(text.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n"))
    }

    suspend fun addMcp(name: String, server: JSONObject) {
        val c = readConfig()
        val mcp = c.optJSONObject("mcp") ?: JSONObject().also { c.put("mcp", it) }
        val servers = mcp.optJSONObject("servers") ?: JSONObject().also { mcp.put("servers", it) }
        servers.put(name, server)
        saveConfig(c.toString(2))
    }

    suspend fun saveAgent(id: String, value: JSONObject?) {
        require(id.matches(Regex("[a-zA-Z0-9._/-]+")) && !id.contains("..")) { "Invalid agent ID" }
        val config = readConfig()
        val agents = config.optJSONObject("agents") ?: JSONObject().also { config.put("agents", it) }
        val current = agents.optJSONObject(id) ?: JSONObject()
        if (value == null) current.put("disabled", true) else {
            value.keys().forEach { key -> current.put(key, value.get(key)) }
            if (!value.has("model")) current.remove("model")
            current.remove("disabled")
        }
        agents.put(id, current)
        saveConfig(config.toString(2))
    }

    suspend fun savePermissions(rules: JSONArray) {
        val config = readConfig()
        config.put("permissions", rules)
        saveConfig(config.toString(2))
    }

    /** Provider switches live in top-level policies: action provider.use, effect deny. */
    suspend fun setProviderEnabled(id: String, on: Boolean) {
        val config = readConfig()
        val all = config.optJSONArray("policies")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val kept = all.filterNot { it.str("action") == "provider.use" && it.str("resource") == id }
        val next = if (on) kept else kept + jsonOf("action" to "provider.use", "resource" to id, "effect" to "deny")
        config.put("policies", JSONArray(next))
        saveConfig(config.toString(2))
    }

    suspend fun setModelEnabled(provider: String, model: String, on: Boolean) {
        val config = readConfig()
        val providers = config.optJSONObject("providers") ?: JSONObject().also { config.put("providers", it) }
        val entry = providers.optJSONObject(provider) ?: JSONObject().also { providers.put(provider, it) }
        val models = entry.optJSONObject("models") ?: JSONObject().also { entry.put("models", it) }
        val modelEntry = models.optJSONObject(model) ?: JSONObject().also { models.put(model, it) }
        if (on) modelEntry.remove("disabled") else modelEntry.put("disabled", true)
        saveConfig(config.toString(2))
    }

    /** Adds an OpenAI-compatible provider with one chat model. */
    suspend fun addProvider(id: String, name: String, baseURL: String, key: String, models: List<Pair<String, String>>) {
        require(id.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid provider ID" }
        require(baseURL.startsWith("http") && models.isNotEmpty() && models.all { it.first.isNotBlank() }) { "Invalid base URL or model" }
        val config = readConfig()
        val providers = config.optJSONObject("providers") ?: JSONObject().also { config.put("providers", it) }
        val entries = JSONObject()
        for ((modelId, modelName) in models) entries.put(modelId, jsonOf("name" to modelName.ifBlank { modelId },
            "limit" to jsonOf("context" to 131072, "output" to 8192),
            "capabilities" to jsonOf("tools" to true, "input" to JSONArray().put("text"), "output" to JSONArray().put("text"))))
        providers.put(id, jsonOf(
            "name" to name.ifBlank { id }, "package" to "@opencode/ai/providers/openai-compatible",
            "settings" to jsonOf("baseURL" to baseURL.trimEnd('/'), "apiKey" to key),
            "models" to entries
        ))
        saveConfig(config.toString(2))
    }

    suspend fun toggleMcp(name: String, enabled: Boolean) {
        val config = readConfig()
        val server = config.optJSONObject("mcp")?.optJSONObject("servers")?.optJSONObject(name)
            ?: error(tr("Этот MCP настроен плагином или проектом; измените его конфигурацию", "This MCP is configured by a plugin or project; edit its source configuration"))
        server.put("disabled", !enabled)
        saveConfig(config.toString(2))
    }

    suspend fun removeMcp(name: String) {
        val c = readConfig()
        c.optJSONObject("mcp")?.let { mcp ->
            mcp.optJSONObject("servers")?.remove(name)
            // Older OpencodeM wrote the V1 flat layout; let users remove those entries too.
            if (mcp.optJSONObject(name)?.has("type") == true) mcp.remove(name)
        }
        saveConfig(c.toString(2))
    }

    // ---- the app's own instructions ----

    private val promptFile get() = File(engine.home, ".config/opencode/app.md")

    /** What the model is told about OpencodeM, kept apart from the person's own AGENTS.md. */
    private fun writePrompt() {
        promptFile.parentFile?.mkdirs()
        val base = ctx.assets.open("app.md").use { it.readBytes().toString(Charsets.UTF_8) }
        val extra = ctx.assets.open(if (linuxActive) "linux.md" else "android.md").use { it.readBytes().toString(Charsets.UTF_8) }
        promptFile.writeText(base + "\n" + extra)
        runtimePlugin.parentFile?.mkdirs()
        ctx.assets.open("margy-runtime.mjs").use { input -> runtimePlugin.outputStream().use { input.copyTo(it) } }
        ctx.assets.open("phone-tools.mjs").use { input -> File(runtimePlugin.parentFile, "phone-tools.mjs").outputStream().use { input.copyTo(it) } }
    }

    private val runtimePlugin get() = File(engine.home, ".config/opencode/runtime-plugin/index.js")

    private fun builtIn(): String {
        val o = jsonOf("instructions" to JSONArray().put(promptFile.absolutePath))
        o.put("plugins", JSONArray().put(jsonOf("package" to runtimePlugin.parentFile!!.absolutePath,
            "options" to jsonOf("appPrompt" to promptFile.absolutePath))))
        local.provider(prefs.localContext)?.let { o.put("providers", jsonOf(com.opencode.engine.Local.PROVIDER to it)) }
        // A plain conversation without tools: opencode then sends ~600 tokens instead of ~6000, which on a
        // phone's processor is the difference between seconds and minutes before the first word.
        o.put("agents", jsonOf("chat" to jsonOf(
            "description" to "Conversation without tools",
            "system" to "You are a helpful chat assistant in OpencodeM on Android. Answer in the user's language. You have no tools in this mode.",
            "mode" to "primary",
            "permissions" to JSONArray().put(jsonOf("action" to "*", "resource" to "*", "effect" to "deny")),
        )))
        return o.toString()
    }

    // ---- root ----

    /** Whether su is present on the device. */
    val hasSu: Boolean get() = File("/system/bin/su").exists() || File("/system/xbin/su").exists()

    /** Proactively requests or tests root access (prompting Magisk overlay or checking KernelSU/APatch). */
    suspend fun requestRootAccess(): Boolean = withContext(Dispatchers.IO) {
        if (!hasSu) return@withContext false
        runCatching {
            val p = ProcessBuilder("/system/bin/su", "-c", "id").start()
            val ok = p.waitFor(25, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) runCatching { p.destroy() }
            ok
        }.getOrDefault(false)
    }

    // ---- folders ----

    /** The phone's shared storage, where the person's own files are. */
    val phone: File get() = android.os.Environment.getExternalStorageDirectory()

    /** Whether the app may reach every file; re-read when the person comes back from the system settings. */
    var filesGranted by mutableStateOf(allFiles())
        private set

    fun checkFiles() { filesGranted = allFiles() }

    private fun allFiles() = Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    /** Where a new chat works: the chosen folder when the app may reach it, else its own workspace. */
    fun folder(): File {
        val f = prefs.folder.takeIf { it.isNotBlank() }?.let { File(it) }
        return if (f != null && filesGranted && (f.isDirectory || f.mkdirs())) f else engine.workspace
    }

    /** Maps a SAF tree URI to a real path; opencode needs a filesystem path, not a content URI. */
    fun pathOfTree(uri: Uri): String? = try {
        ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val doc = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val volume = doc.substringBefore(':', "")
        val relative = doc.substringAfter(':', "")
        val root: File? = if (volume.equals("primary", ignoreCase = true)) {
            android.os.Environment.getExternalStorageDirectory()
        } else if (Build.VERSION.SDK_INT >= 30) {
            ctx.getSystemService(android.os.storage.StorageManager::class.java)
                .storageVolumes.firstOrNull { v -> v.uuid?.equals(volume, ignoreCase = true) == true }
                ?.directory
        } else null
        root?.let { File(it, relative).absolutePath }
    } catch (_: Exception) { null }

    fun folderLabel(path: String?): String = when {
        path == null || path == engine.workspace.absolutePath -> tr("Папка приложения", "App folder")
        path == phone.absolutePath -> tr("Память телефона", "Phone storage")
        path.startsWith(phone.absolutePath + "/") -> path.removePrefix(phone.absolutePath + "/")
        else -> path
    }

    fun projectLabel(path: String?): String {
        val p = path?.takeIf { it.isNotBlank() } ?: activeProject ?: prefs.folder.takeIf { it.isNotBlank() }
        if (p == null || p == engine.workspace.absolutePath) return tr("Основной проект", "Default project")
        val f = File(p)
        return if (f.name.isNotBlank()) f.name else folderLabel(p)
    }

    var activeProject by mutableStateOf<String?>(null)

    fun projectDir(): File = activeProject?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: session()?.directory?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: folder()

    fun allProjects(): List<File> {
        val list = LinkedHashMap<String, File>()
        fun add(f: File) { list.putIfAbsent(f.absolutePath, f) }
        add(engine.workspace)
        if (prefs.folder.isNotBlank()) add(File(prefs.folder))
        prefs.projects.forEach { add(File(it)) }
        for (s in sessions) s.directory?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        return list.values.toList()
    }

    fun addProject(dir: File) {
        val path = dir.absolutePath
        if (path !in prefs.projects && path != engine.workspace.absolutePath) {
            update { it.copy(projects = it.projects + path) }
        }
    }

    fun removeProject(path: String) {
        update { it.copy(projects = it.projects - path) }
        if (activeProject == path) activeProject = null
    }

    fun newSessionIn(directory: String) {
        openSession(null)
        activeProject = directory
        draft(null).folder = directory
    }

    // ---- opencode's own files ----

    val configFile get() = File(engine.config, "opencode.json")
    val rulesFile get() = File(engine.config, "AGENTS.md")

    suspend fun saveConfig(text: String) {
        if (text.isNotBlank()) JSONObject(text.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n"))
        withContext(Dispatchers.IO) { engine.config.mkdirs(); configFile.writeText(text) }
        reload()
    }

    suspend fun saveRules(text: String) {
        withContext(Dispatchers.IO) { engine.config.mkdirs(); rulesFile.writeText(text) }
        reload()
    }

    suspend fun reload() {
        api.post("/api/location/reload")
        refreshAll()
    }

    // ---- pictures ----

    suspend fun importAttachment(uri: Uri): String = withContext(Dispatchers.IO) {
        if (ctx.contentResolver.getType(uri)?.startsWith("image/") == true) return@withContext importPicture(uri) ?: error("Could not import image")
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.substringAfterLast('/')?.substringAfterLast('\\')?.take(150)?.takeIf { it !in listOf("", ".", "..") }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.take(150)?.takeIf { it !in listOf("", ".", "..") }
            ?: "attachment"
        val folder = File(ctx.filesDir, "media/${UUID.randomUUID()}").apply { mkdirs() }
        val out = File(folder, name)
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { output ->
                val bytes = ByteArray(65536)
                var size = 0L
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val count = input.read(bytes); if (count < 0) break
                    size += count; check(size <= 50L * 1024 * 1024) { "Attachment exceeds 50 MB" }
                    output.write(bytes, 0, count)
                }
            } }
            out.absolutePath
        } catch (e: Exception) { out.delete(); folder.delete(); throw e }
    }

    fun cameraFile(): File = File(ctx.cacheDir, "camera/${UUID.randomUUID()}.jpg").also { it.parentFile!!.mkdirs() }
    fun cameraUri(file: File): Uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)

    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) {
        (File(ctx.cacheDir, "attachments").walkTopDown().filter { it.isFile }.sumOf { it.length() } +
            File(ctx.filesDir, "media").walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }
    suspend fun clearAttachmentCache() {
        withContext(Dispatchers.IO) {
            File(ctx.cacheDir, "attachments").deleteRecursively()
            File(ctx.filesDir, "media").deleteRecursively()
        }
        open?.let { loadMessages(it) }
    }

    /** Copies a picture into the app, no bigger than models look at anyway. */
    suspend fun importPicture(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > 1568) {
                    val k = 1568f / side
                    d.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
                }
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val dir = File(ctx.filesDir, "media").apply { mkdirs() }
            val out = File(dir, UUID.randomUUID().toString() + ".jpg")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (uri.path?.startsWith("/camera/") == true) File(ctx.cacheDir, "camera/${uri.lastPathSegment}").delete()
            out.absolutePath
        }.getOrNull()
    }
}
