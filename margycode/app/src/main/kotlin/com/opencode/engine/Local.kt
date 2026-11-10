package com.opencode.engine

import android.app.ActivityManager
import android.content.Context
import com.opencode.data.jsonOf
import com.opencode.tr
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Models that run on the phone itself: GGUF files from Hugging Face, served by llama.cpp's
 * llama-server (lib/libllamaserver.so) in router mode, which loads whichever is asked for.
 * opencode sees them as one more OpenAI-compatible provider.
 */
class Local(private val context: Context) {
    /** A model the app offers, by its files on Hugging Face. */
    class Offer(val id: String, val name: String, val repo: String, val file: String, val gb: Double,
                val note: String, val vision: Boolean = true, val mmproj: String = "mmproj-F16.gguf")

    private val dir get() = File(context.filesDir, "models")
    private val libs get() = context.applicationInfo.nativeLibraryDir
    private var process: Process? = null
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()

    fun cancelDownload(id: String) { connections.remove(id)?.disconnect() }
    var port = 0
        private set
    val log get() = File(context.filesDir, "llama.log")
    var error by mutableStateOf<String?>(null)
        private set
    var logTail by mutableStateOf("")
        private set

    suspend fun checkError() {
        val tail = withContext(Dispatchers.IO) {
            runCatching {
                RandomAccessFile(log, "r").use { file ->
                    val size = minOf(file.length(), 16_384L).toInt()
                    file.seek(file.length() - size)
                    val bytes = ByteArray(size)
                    file.readFully(bytes)
                    bytes.toString(Charsets.UTF_8).lines().takeLast(15).joinToString("\n")
                }
            }.getOrDefault("")
        }
        logTail = tail
        localFailure(tail)?.let { error = it }
        if (process != null && !running) error = localFailure(tail) ?: tr("llama-server остановился", "llama-server stopped")
    }

    /** Every model on the phone: a folder each, named by its id, with the model and maybe its picture reader. */
    fun installed(): List<String> = dir.listFiles().orEmpty()
        .filter { d -> d.isDirectory && d.listFiles().orEmpty().any { it.name.endsWith(".gguf") && !it.name.startsWith("mmproj") } }
        .map { it.name }.sorted()

    fun sees(id: String) = File(dir, id).listFiles().orEmpty().any { it.name.startsWith("mmproj") && it.name.endsWith(".gguf") }

    fun size(id: String) = File(dir, id).listFiles().orEmpty().filter { it.name.endsWith(".gguf") }.sumOf { it.length() }

    fun remove(id: String) {
        File(dir, id).deleteRecursively()
    }

    /** How much memory the phone has, to say which models fit. */
    fun ramGb(): Double {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem / 1e9
    }

    /**
     * Downloads a model (and its picture reader, if wanted) into its folder, resuming where a
     * broken download stopped. [progress] gets bytes done and the total of everything.
     */
    suspend fun download(id: String, urls: List<String>, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        require(id.isNotBlank() && id !in listOf(".", "..") && id.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid model name" }
        val folder = File(dir, id).apply { mkdirs() }
        val sizes = urls.map { coroutineContext.ensureActive(); head(id, it) }
        val total = sizes.sum()
        var before = 0L
        for ((i, url) in urls.withIndex()) {
            coroutineContext.ensureActive()
            val name = url.substringAfterLast('/').substringBefore('?')
            require(name.endsWith(".gguf", true) && name != ".gguf") { "Expected a GGUF file" }
            val target = File(folder, name)
            if (target.exists() && target.length() == sizes[i]) { before += sizes[i]; progress(before, total); continue }
            val part = File(folder, "$name.part")
            var have = if (part.exists()) part.length() else 0L
            if (sizes[i] > 0 && have == sizes[i]) {
                check(part.renameTo(target)) { "Could not save completed model" }
                before += sizes[i]
                progress(before, total)
                continue
            }
            val c = URL(url).openConnection() as HttpURLConnection
            connections[id] = c
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "OpencodeM")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = c.responseCode
                have = resumeOffset(code, c.getHeaderField("Content-Range"), have)
                progress(before + have, total)
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(have)
                    out.setLength(have)
                    val buf = ByteArray(1 shl 16)
                    c.inputStream.use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            progress(before + have, total)
                        }
                    }
                }
            } finally {
                connections.remove(id, c)
                c.disconnect()
            }
            if (part.length() != sizes[i] && sizes[i] > 0) error("Hugging Face: the file came short")
            coroutineContext.ensureActive()
            check(part.renameTo(target)) { "Could not save downloaded model" }
            before += sizes[i]
            progress(before, total)
        }
    }

    private fun head(id: String, url: String): Long {
        val c = URL(url).openConnection() as HttpURLConnection
        connections[id] = c
        c.requestMethod = "HEAD"
        c.instanceFollowRedirects = true
        c.connectTimeout = 20_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "OpencodeM")
        return try {
            if (c.responseCode !in 200..299) error("Hugging Face: HTTP ${c.responseCode} for ${url.substringAfterLast('/')}")
            c.contentLengthLong
        } finally {
            connections.remove(id, c)
            c.disconnect()
        }
    }

    val running get() = process?.isAlive == true

    /**
     * Threads for the fast cores only: the little ones hold everybody back. On a Snapdragon 8 Gen 2
     * (3 little, 5 big) four threads gave 17 tokens a second against 4 with all eight.
     */
    private fun bigCores(): Int {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        if (freqs.isEmpty()) return 4
        val big = freqs.count { it > freqs.min() }
        return when {
            big <= 0 -> (freqs.size / 2).coerceAtLeast(1)
            big >= 4 -> big - 1
            else -> big
        }
    }

    /** The build with i8mm kernels where the chip has them (Snapdragon 8 Gen 1 and newer and the like). */
    private fun binary(): String {
        val i8mm = runCatching { File("/proc/cpuinfo").readText().lineSequence().any { it.startsWith("Features") && " i8mm" in it } }
            .getOrDefault(false)
        val fast = File(libs, "libllamaserveri8mm.so")
        return if (i8mm && fast.exists()) fast.absolutePath else "$libs/libllamaserver.so"
    }

    /** Starts llama-server over the models folder, once there is something in it. */
    suspend fun start(context: Int, threads: Int) = withContext(Dispatchers.IO) {
        if (running || installed().isEmpty()) return@withContext
        error = null
        logTail = ""
        port = ServerSocket(0).use { it.localPort }
        val tmp = File(this@Local.context.cacheDir, "llama").apply { mkdirs() }
        val args = mutableListOf(binary(), "--models-dir", dir.absolutePath, "--models-max", "1",
            "--host", "127.0.0.1", "--port", port.toString(), "--no-webui", "--jinja", "-c", context.toString(),
            "-np", "1", "-b", "64", "-ub", "32")
        args += listOf("-t", (if (threads > 0) threads else bigCores()).toString())
        val pb = ProcessBuilder(args).directory(dir).redirectErrorStream(true).redirectOutput(log)
        pb.environment()["TMPDIR"] = tmp.absolutePath
        pb.environment()["HOME"] = tmp.absolutePath
        try {
            process = pb.start()
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                if (!running) error(tr("llama-server остановился", "llama-server stopped"))
                val healthy = runCatching {
                    val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
                    c.connectTimeout = 500
                    c.readTimeout = 500
                    try { c.responseCode == 200 } finally { c.disconnect() }
                }.getOrDefault(false)
                if (healthy) return@withContext
                delay(200)
            }
            error(tr("llama-server не запустился за 20 секунд", "llama-server did not start in 20 seconds"))
        } catch (e: CancellationException) {
            stop()
            throw e
        } catch (e: Exception) {
            checkError()
            val message = error ?: e.message ?: e.toString()
            stop()
            error(message)
        }
    }

    fun stop() {
        val p = process ?: return
        p.destroy()
        val exited = runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited && p.isAlive) {
            runCatching { p.destroyForcibly()?.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
        }
        process = null
    }

    suspend fun progress(id: String): String = withContext(Dispatchers.IO) {
        if (!running) return@withContext tr("Локальный движок остановлен", "Local engine stopped")
        val c = URL("http://127.0.0.1:$port/slots?model=${Api.enc(id)}").openConnection() as HttpURLConnection
        c.connectTimeout = 500
        c.readTimeout = 1000
        try {
            if (c.responseCode != 200) return@withContext tr("Загружается модель…", "Loading model…")
            val slots = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            val slot = (0 until slots.length()).map { slots.getJSONObject(it) }.firstOrNull { it.optBoolean("is_processing") }
                ?: return@withContext tr("Ожидает запрос…", "Waiting for request…")
            localProgress(slot)
        } finally { c.disconnect() }
    }

    /** The provider entry for opencode's config: every installed model, at the server's address. */
    fun provider(contextSize: Int): JSONObject? {
        if (!running) return null
        val models = JSONObject()
        for (id in installed()) {
            val name = OFFERS.firstOrNull { it.id == id }?.name ?: id
            models.put(id, jsonOf(
                "name" to name,
                "limit" to jsonOf("context" to contextSize, "output" to 4096),
                "capabilities" to jsonOf("tools" to true,
                    "input" to JSONArray().put("text").apply { if (sees(id)) put("image") }, "output" to JSONArray().put("text")),
                "cost" to jsonOf("input" to 0, "output" to 0),
            ))
        }
        return jsonOf(
            "name" to tr("На телефоне", "On the phone"),
            "package" to "@opencode/ai/providers/openai-compatible",
            "settings" to jsonOf("baseURL" to "http://127.0.0.1:$port/v1", "apiKey" to "local"),
            "models" to models,
        )
    }

    companion object {
        const val PROVIDER = "phone"
        private const val HF = "https://huggingface.co"
        fun url(repo: String, file: String) = "$HF/$repo/resolve/main/$file"

        val OFFERS = listOf(
            Offer("gemma-4-e2b-mobile", tr("Gemma 4 E2B (мобильная)", "Gemma 4 E2B (mobile)"), "unsloth/gemma-4-E2B-it-qat-mobile-GGUF",
                "gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf", 2.2, tr("Google, сжата для телефонов: быстрая и лёгкая", "Google, squeezed for phones: fast and light")),
            Offer("gemma-4-e2b", "Gemma 4 E2B", "unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_K_M.gguf", 3.1,
                tr("Google, точнее мобильной", "Google, more exact than the mobile one")),
            Offer("gemma-4-e4b-mobile", tr("Gemma 4 E4B (мобильная)", "Gemma 4 E4B (mobile)"), "unsloth/gemma-4-E4B-it-qat-mobile-GGUF",
                "gemma-4-E4B-it-qat-UD-Q2_K_XL.gguf", 3.2, tr("Google, умнее E2B, для телефонов с 8 ГБ памяти", "Google, smarter than E2B, for phones with 8 GB of memory")),
            Offer("gemma-4-e4b", "Gemma 4 E4B", "unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf", 5.0,
                tr("Google, лучшая из маленьких, для 12 ГБ памяти и больше", "Google, the best of the small ones, for 12 GB of memory or more")),
            Offer("qwen3.5-2b", "Qwen 3.5 2B", "unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_K_M.gguf", 1.3,
                tr("Alibaba, самая лёгкая", "Alibaba, the lightest")),
            Offer("qwen3.5-4b", "Qwen 3.5 4B", "unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf", 2.7,
                tr("Alibaba, хорошо работает с инструментами", "Alibaba, good with tools")),
        )
    }
}
