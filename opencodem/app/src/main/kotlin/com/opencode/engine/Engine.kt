package com.opencode.engine

import android.content.Context
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

/**
 * opencode itself, running inside the app as `opencode serve` on localhost.
 *
 * The binary is upstream opencode compiled onto the official Bun for Android
 * (engine/build-engine.sh). It ships as lib/arm64-v8a/libopencode.so because
 * the library folder is the one place Android lets an app execute files from.
 */
class Engine(private val context: Context) {
    private var process: Process? = null
    var port = 0
        private set
    val password: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    val home get() = File(context.filesDir, "home")
    /** The folder opencode treats as the project: its tools read and write here. */
    val workspace get() = File(context.filesDir, "workspace")
    val config get() = File(home, ".config/opencode")
    val log get() = File(context.filesDir, "opencode.log")

    val running get() = process?.isAlive == true
    private val tmp get() = File(context.cacheDir, "tmp")
    private val runtimes get() = File(home, ".local/margy-bin")

    /** More environment for opencode and what it runs (Claude Code's paths). */
    var extraEnv: () -> Map<String, String> = { emptyMap() }
    var beforeStart: () -> Unit = {}

    /** A Linux-like home for opencode and anything it starts. */
    fun baseEnv(): Map<String, String> = mapOf(
        "HOME" to home.absolutePath,
        "XDG_CONFIG_HOME" to File(home, ".config").absolutePath,
        "XDG_DATA_HOME" to File(home, ".local/share").absolutePath,
        "XDG_STATE_HOME" to File(home, ".local/state").absolutePath,
        "XDG_CACHE_HOME" to File(home, ".cache").absolutePath,
        "TMPDIR" to tmp.absolutePath,
        "SHELL" to "/system/bin/sh",
        "PATH" to "${runtimes.absolutePath}:${context.applicationInfo.nativeLibraryDir}:${System.getenv("PATH") ?: "/system/bin"}",
        "MARGY_RUNTIME" to "${context.applicationInfo.nativeLibraryDir}/libopencode.so",
        "MARGY_SHELL" to "${context.applicationInfo.nativeLibraryDir}/libshell-runtime.so",
        "MARGY_ALPINE" to "${context.applicationInfo.nativeLibraryDir}/liblinux.so",
        "MARGY_NPX" to File(home, ".local/margy-npx.mjs").absolutePath,
        "MARGY_CHROOT" to "${context.applicationInfo.nativeLibraryDir}/liblinuxchroot.so",
        "OPENCODE_FLAGS" to "todo",
        // Android has no working inotify backend; the watcher only logs EACCES errors.
        "OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER" to "1",
    )

    suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (running) return@withContext
        val bin = File(context.applicationInfo.nativeLibraryDir, "libopencode.so")
        if (!bin.exists()) error(tr("opencode отсутствует в приложении: $bin", "opencode is missing from the app: $bin"))
        port = ServerSocket(0).use { it.localPort }
        for (d in listOf(home, workspace, tmp, config)) d.mkdirs()
        runtimes.deleteRecursively()
        runtimes.mkdirs()
        context.assets.open("npx.mjs").use { input ->
            File(home, ".local/margy-npx.mjs").outputStream().use { input.copyTo(it) }
        }
        for (name in listOf("node", "bun", "npx", "bunx", "python", "python3", "uv", "uvx", "npm", "git")) {
            // Symlinks can fail after a package/data migration; MCP then falls back, the engine must still boot.
            runCatching {
                java.nio.file.Files.createSymbolicLink(File(runtimes, name).toPath(),
                    File(context.applicationInfo.nativeLibraryDir, "libmcp-runtime.so").toPath())
            }
        }
        beforeStart()
        if (log.length() > 5L * 1024 * 1024) log.writeText("")
        val pb = ProcessBuilder(bin.absolutePath, "serve", "--hostname", "127.0.0.1", "--port", port.toString())
            .directory(workspace)
            .redirectErrorStream(true)
            .redirectOutput(log)
        pb.environment().putAll(baseEnv() + extraEnv() + ("OPENCODE_PASSWORD" to password))
        process = pb.start()
        try {
            // Ready once it answers; a first start sets up its database and takes a few seconds.
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                if (!running) error(tr("opencode остановился: ", "opencode stopped: ") + tailOf(log, 1500))
                if (ping()) return@withContext
                delay(250)
            }
            error(tr("opencode не запустился за минуту: ", "opencode didn't start in a minute: ") + tailOf(log, 1500))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // the caller gave up (restart/close): do not leave a server nobody owns
            process?.destroy()
            throw e
        }
    }

    private fun ping(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$port/api/info").openConnection() as HttpURLConnection
        c.connectTimeout = 500
        c.readTimeout = 2000
        c.setRequestProperty("Authorization", auth())
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    fun auth() = "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray())

    fun stop() {
        val p = process ?: return
        p.destroy()
        val exited = runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited && p.isAlive) {
            runCatching { p.destroyForcibly()?.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
        }
        process = null
    }
}
