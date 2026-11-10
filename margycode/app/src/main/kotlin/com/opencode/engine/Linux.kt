package com.opencode.engine

import android.content.Context
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Alpine Linux for the agent: its minirootfs, downloaded from Alpine, run through proot
 * (lib/libproot.so). Once it is in, opencode's SHELL is lib/liblinux.so and every command
 * the model runs happens there, with apk to install whatever it needs.
 */
class Linux(private val context: Context, private val engine: Engine) {
    private val dir get() = File(context.filesDir, "linux")
    val rootfs get() = File(dir, "rootfs")
    private val libs get() = context.applicationInfo.nativeLibraryDir
    private val installLock = Mutex()
    /** Use a real chroot via root instead of proot (proot hangs on pipes on some kernels). */
    var rootChroot = false
    var installing by mutableStateOf(false)
        private set

    val installed get() = File(rootfs, "etc/alpine-release").exists()
    val version get() = File(rootfs, "etc/alpine-release").takeIf { it.exists() }?.readText()?.trim()

    /** opencode runs its commands through SHELL, so pointing it here moves them all into Alpine. */
    fun env(enabled: Boolean): Map<String, String> {
        if (!enabled || !installed) return mapOf("SHELL" to "$libs/libshell-runtime.so")
        val tmp = File(context.cacheDir, "proot").apply { mkdirs() }
        return mapOf(
            "SHELL" to "$libs/libshell-runtime.so",
            "MARGY_PROOT" to "$libs/libproot.so",
            "MARGY_PROOT_LOADER" to "$libs/libprootloader.so",
            "MARGY_PROOT_TMP" to tmp.absolutePath,
            "MARGY_ROOTFS" to rootfs.absolutePath,
            "MARGY_FILES" to context.filesDir.absolutePath,
            "MARGY_CACHE" to context.cacheDir.absolutePath,
            "MARGY_ALPINE_ROOT" to if (rootChroot) "1" else "0",
        )
    }

    /** Alpine has no idea of Android's DNS; it gets the phone's servers, as Claude Code does. */
    fun writeResolv(servers: String) {
        if (!installed) return
        runCatching { File(rootfs, "etc/resolv.conf").apply { delete() }.writeText(servers) }
    }

    /** Downloads the newest minirootfs of the pinned Alpine branch and unpacks it with Android's own tar. */
    suspend fun install(progress: (Long, Long) -> Unit) = installLock.withLock { withContext(Dispatchers.IO) {
        installing = true
        try {
        val manifest = URL("$BASE/latest-releases.yaml").openConnection() as HttpURLConnection
        manifest.connectTimeout = 20_000
        manifest.readTimeout = 20_000
        val yaml = try { manifest.inputStream.bufferedReader().use { it.readText() } } finally { manifest.disconnect() }
        val block = yaml.split("\n-").firstOrNull { it.contains("flavor: alpine-minirootfs") }
            ?: error(tr("Alpine: нет minirootfs в latest-releases.yaml", "Alpine: no minirootfs in latest-releases.yaml"))
        fun field(name: String) = Regex("""(?m)^\s*$name:\s*(\S+)""").find(block)?.groupValues?.get(1)
            ?: error(tr("Alpine: нет $name", "Alpine: no $name"))
        val file = field("file")
        val sha = field("sha256")
        dir.mkdirs()
        val archive = File(dir, "rootfs.tar.gz")
        val c = URL("$BASE/$file").openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (c.responseCode != 200) error("Alpine: HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                archive.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != sha) {
            archive.delete()
            error(tr("Alpine: скачанный файл повреждён (sha256)", "Alpine: the download is damaged (sha256)"))
        }
        val fresh = File(dir, "rootfs.new").apply { wipe(this); mkdirs() }
        val out = processOutput(ProcessBuilder("/system/bin/tar", "xzf", archive.absolutePath, "-C", fresh.absolutePath),
            File.createTempFile("alpine-", ".log", context.cacheDir), 60_000)
        if (!File(fresh, "etc/alpine-release").exists() || "Exit code:" in out) error("tar: $out")
        archive.delete()
        val previous = File(dir, "rootfs.previous")
        if (previous.exists()) wipe(previous)
        if (rootfs.exists()) check(rootfs.renameTo(previous)) { "Could not back up Alpine before replacement" }
        if (!fresh.renameTo(rootfs)) {
            if (previous.exists()) check(previous.renameTo(rootfs)) { "Restore Alpine from $previous" }
            error(tr("Не удалось установить Alpine", "Could not install Alpine"))
        }
        wipe(previous)
        File(rootfs, "root").mkdirs()
        } finally { installing = false }
    } }

    /** Runs one command inside, the way opencode will, and returns what it printed. */
    suspend fun run(command: String, servers: String, directory: File = engine.workspace, runtime: String = "alpine"): String = withTimeout(60_000) { installLock.withLock { withContext(Dispatchers.IO) {
        check(runtime in listOf("auto", "native", "alpine")) { "Invalid runtime" }
        check(runtime != "alpine" || installed) { "Install Alpine first" }
        writeResolv(servers)
        val pb = ProcessBuilder("$libs/libshell-runtime.so", "-c", command).directory(directory)
        pb.environment().putAll(engine.baseEnv() + env(true) + ("MARGY_CALL_RUNTIME" to runtime))
        processOutput(pb, File.createTempFile("console-", ".log", context.cacheDir), 30_000)
    } } }

    fun remove() {
        wipe(dir)
    }

    /**
     * Deletes a tree without following symlinks or bind-mounts into personal storage.
     */
    private fun wipe(root: File) {
        val path = root.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        val rootfsDir = File(root, "rootfs").takeIf { it.exists() } ?: root
        if (rootChroot) {
            runCatching {
                val r1 = root.absolutePath.replace("\"", "\\\"")
                val r2 = rootfsDir.absolutePath.replace("\"", "\\\"")
                val targets = listOf(
                    "\"$r1/storage/emulated/0\"", "\"$r1/storage/*\"", "\"$r1/data/user/0/*\"", "\"$r1/data/data/*\"", "\"$r1/dev\"", "\"$r1/proc\"", "\"$r1/sys\"",
                    "\"$r2/storage/emulated/0\"", "\"$r2/storage/*\"", "\"$r2/data/user/0/*\"", "\"$r2/data/data/*\"", "\"$r2/dev\"", "\"$r2/proc\"", "\"$r2/sys\""
                ).joinToString(" ")
                val p = ProcessBuilder("/system/bin/su", "-c", "umount -f -l $targets 2>/dev/null").start()
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            }
            val activeMounts = runCatching {
                val p = ProcessBuilder("/system/bin/su", "-c", "grep \" ${root.absolutePath}\" /proc/mounts").start()
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); "" }
                else p.inputStream.bufferedReader().readText()
            }.getOrDefault("")
            if (activeMounts.isNotBlank()) {
                android.util.Log.e("OpencodeM", "Aborting wipe: active bind mounts detected in $activeMounts")
                return
            }
        }
        java.nio.file.Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (dir == path) return java.nio.file.FileVisitResult.CONTINUE
                val rel = path.relativize(dir).toString().replace('\\', '/')
                if (rel.startsWith("storage") || rel.startsWith("rootfs/storage") || rel.contains("/storage/") ||
                    rel.startsWith("data") || rel.startsWith("rootfs/data") || rel.contains("/data/user") || rel.contains("/data/data") ||
                    rel.startsWith("dev") || rel.startsWith("rootfs/dev") ||
                    rel.startsWith("proc") || rel.startsWith("rootfs/proc") ||
                    rel.startsWith("sys") || rel.startsWith("rootfs/sys")) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(file) }
            override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException) =
                java.nio.file.FileVisitResult.CONTINUE.also { runCatching { java.nio.file.Files.deleteIfExists(file) } }
            override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?) =
                java.nio.file.FileVisitResult.CONTINUE.also { runCatching { java.nio.file.Files.deleteIfExists(dir) } }
        })
    }

    companion object {
        private const val BASE = "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64"
    }
}
