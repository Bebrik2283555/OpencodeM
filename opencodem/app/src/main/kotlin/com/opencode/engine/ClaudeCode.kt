package com.opencode.engine

import android.content.Context
import android.net.ConnectivityManager
import com.opencode.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Claude Code for opencode's "Claude Agent" provider. Anthropic publishes no Android
 * build, so the app downloads the linux-arm64-musl one from npm, as `npm i` would,
 * and runs it through musl's loader (lib/libldmusl.so, via lib/libclaude.so).
 * Signing in is Claude Code's own `claude auth login`; opencode never sees the token.
 */
class ClaudeCode(private val context: Context, private val engine: Engine) {
    private val dir get() = File(context.filesDir, "claude")
    val bin get() = File(dir, "claude")
    private val versionFile get() = File(dir, "version")
    val resolv get() = File(engine.home, "resolv.conf")
    private val libs get() = context.applicationInfo.nativeLibraryDir

    val installed get() = bin.exists() && versionFile.exists()
    val version get() = versionFile.takeIf { it.exists() }?.readText()?.trim()

    /** What every process that may run `claude` needs to find it and the network. */
    fun env(): Map<String, String> = mapOf(
        "OPENCODE_CLAUDE_PATH" to "$libs/libclaude.so",
        "MARGY_LDMUSL" to "$libs/libldmusl.so",
        "MARGY_CLAUDE_BIN" to bin.absolutePath,
        "MARGY_RESOLV" to resolv.absolutePath,
        // there is no browser to open from inside; the app opens the link itself
        "BROWSER" to "/system/bin/true",
        "DISABLE_AUTOUPDATER" to "1",
    )

    /** The DNS servers the phone uses right now (VPN included), for musl, which looks for them in a file. */
    fun writeResolv() {
        engine.home.mkdirs()
        resolv.writeText(dns())
    }

    /** The phone's DNS servers in resolv.conf form. */
    fun dns(): String {
        val servers = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
                .sortedBy { if (it is Inet4Address) 0 else 1 }
                .mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrDefault(emptyList())
        return (servers + listOf("1.1.1.1", "8.8.8.8")).distinct().take(3).joinToString("") { "nameserver $it\n" }
    }

    suspend fun latest(): Triple<String, String, String> = withContext(Dispatchers.IO) {
        val o = JSONObject(fetch("$REGISTRY/latest").use { it.bufferedReader().readText() })
        val dist = o.optJSONObject("dist") ?: error("npm: no dist")
        Triple(o.getString("version"), dist.str("tarball") ?: error("npm: no tarball"),
            dist.str("integrity") ?: error("npm: no integrity"))
    }

    /** Downloads and unpacks the binary; [progress] gets bytes done and the total. */
    suspend fun install(progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val (version, tarball, integrity) = latest()
        dir.mkdirs()
        val part = File(dir, "claude.part")
        val c = URL(tarball).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "OpencodeM")
        try {
            if (c.responseCode != 200) error("npm: HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            val digest = java.security.MessageDigest.getInstance("SHA-512")
            val counted = object : InputStream() {
                val inner = c.inputStream
                override fun read(): Int = inner.read().also { if (it >= 0) { digest.update(it.toByte()); progress(++done, total) } }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    inner.read(b, off, len).also { if (it > 0) { done += it; digest.update(b, off, it); progress(done, total) } }
            }
            if (!untar(GZIPInputStream(counted.buffered(1 shl 16)), "package/claude", part)) error("npm: no claude in the package")
            // The tar reader stops at the wanted entry; drain the rest so the hash covers the whole tarball.
            val sink = ByteArray(1 shl 16)
            while (counted.read(sink) >= 0) Unit
            val actual = "sha512-" + java.util.Base64.getEncoder().encodeToString(digest.digest())
            if (actual != integrity) {
                part.delete()
                error("npm: checksum mismatch")
            }
        } finally {
            c.disconnect()
        }
        part.setExecutable(true)
        if (!part.renameTo(bin)) error("can't place $bin")
        versionFile.writeText(version)
    }

    fun remove() {
        dir.deleteRecursively()
    }

    /** `claude auth login`, started; the caller reads its output for the link and writes the code in. */
    fun login(): Process {
        writeResolv()
        val pb = ProcessBuilder("$libs/libclaude.so", "auth", "login", "--claudeai")
            .directory(engine.workspace)
            .redirectErrorStream(true)
        pb.environment().putAll(engine.baseEnv() + env())
        return pb.start()
    }

    /** Runs a short claude command and returns what it printed. */
    suspend fun run(vararg args: String): String = withContext(Dispatchers.IO) {
        writeResolv()
        val pb = ProcessBuilder(listOf("$libs/libclaude.so") + args).directory(engine.workspace).redirectErrorStream(true)
        pb.environment().putAll(engine.baseEnv() + env())
        val p = pb.start()
        val watchdog = Thread { runCatching { Thread.sleep(60_000); p.destroyForcibly() } }.apply { isDaemon = true; start() }
        try {
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.trim()
        } finally {
            watchdog.interrupt()
            p.destroy()
        }
    }

    /**
     * The data behind Claude Code's /usage: plan windows and their resets. Asked the way the
     * Agent SDK asks (a get_usage control request over stream-json), so no model is called.
     */
    suspend fun usage(): JSONObject? = withContext(Dispatchers.IO) {
        if (!installed) return@withContext null
        writeResolv()
        val pb = ProcessBuilder("$libs/libclaude.so", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json")
            .directory(engine.workspace)
            .redirectError(File(engine.home, "claude-usage.log"))
        pb.environment().putAll(engine.baseEnv() + env())
        val p = pb.start()
        val watchdog = Thread { runCatching { Thread.sleep(45_000); p.destroyForcibly() } }.apply { isDaemon = true; start() }
        try {
            p.outputStream.bufferedWriter().apply {
                write("""{"type":"control_request","request_id":"init","request":{"subtype":"initialize"}}""" + "\n")
                write("""{"type":"control_request","request_id":"usage","request":{"subtype":"get_usage","skip_behaviors":true}}""" + "\n")
                flush()
            }
            val deadline = System.currentTimeMillis() + 40_000
            val reader = p.inputStream.bufferedReader()
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                if (!line.contains("\"usage\"")) continue
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val r = o.optJSONObject("response") ?: continue
                if (r.str("request_id") != "usage") continue
                if (r.str("subtype") != "success") error(r.str("error") ?: "get_usage failed")
                return@withContext r.optJSONObject("response")
            }
            null
        } finally {
            watchdog.interrupt()
            p.destroy()
        }
    }

    private fun fetch(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "OpencodeM")
        if (c.responseCode != 200) error("npm: HTTP ${c.responseCode}")
        return c.inputStream
    }

    /** Finds one file in a tar stream and copies it out. */
    private fun untar(input: InputStream, name: String, out: File): Boolean {
        val header = ByteArray(512)
        while (true) {
            if (!readFully(input, header)) return false
            if (header.all { it.toInt() == 0 }) return false
            val entry = String(header, 0, 100).trimEnd('\u0000')
            val prefix = String(header, 345, 155).trimEnd('\u0000')
            val path = if (prefix.isNotEmpty()) "$prefix/$entry" else entry
            val size = String(header, 124, 12).trim('\u0000', ' ').toLong(8)
            val padded = (size + 511) / 512 * 512
            if (path == name) {
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var left = size
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) error("tar cut short")
                        o.write(buf, 0, n)
                        left -= n
                    }
                }
                return true
            }
            var skip = padded
            while (skip > 0) {
                val n = input.skip(skip)
                if (n <= 0) { if (input.read() < 0) return false else skip-- } else skip -= n
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var at = 0
        while (at < buf.size) {
            val n = input.read(buf, at, buf.size - at)
            if (n < 0) return false
            at += n
        }
        return true
    }

    companion object {
        const val ID = "claude-agent"
        const val METHOD = "claude-login"
        private const val REGISTRY = "https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-musl"
    }
}
