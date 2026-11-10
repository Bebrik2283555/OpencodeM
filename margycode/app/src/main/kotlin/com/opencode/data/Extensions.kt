package com.opencode.data

import android.content.Context
import com.opencode.Brain
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Extensions(private val context: Context, private val brain: Brain) {
    suspend fun plugins(): List<JSONObject> = brain.api.get("/api/plugin${brain.locationQuery()}").getJSONArray("data").objects()

    suspend fun addPlugin(value: String) {
        val ref = if (value.startsWith("https://github.com/")) {
            val dir = repository(value)
            try { JSONObject(File(dir, "package.json").readText()).getString("name") }
            finally { dir.parentFile!!.deleteRecursively() }
        } else value.trim().removePrefix("npm:")
        require(ref.isNotBlank() && !ref.contains('\n')) { "Invalid plugin package" }
        val config = brain.readConfig()
        val plugins = config.optJSONArray("plugins") ?: JSONArray()
        if ((0 until plugins.length()).none { plugins.optString(it) == ref }) plugins.put(ref)
        config.put("plugins", plugins)
        brain.saveConfig(config.toString(2))
    }

    suspend fun removePlugin(ref: String) {
        val config = brain.readConfig()
        val list = config.optJSONArray("plugins") ?: JSONArray()
        config.put("plugins", JSONArray((0 until list.length()).map { list.get(it) }.filter {
            if (it is JSONObject) it.str("package") != ref else it != ref
        }))
        brain.saveConfig(config.toString(2))
    }

    suspend fun skills(url: String): Int {
        val root = repository(url)
        try {
            val source = File(root, "skills").takeIf { it.isDirectory } ?: root
            val entries = source.walkTopDown().onEnter { it == source || (!it.name.startsWith('.') && it.name != "node_modules") }
                .filter { it.isFile && it.name == "SKILL.md" }.toList()
            check(entries.isNotEmpty()) { "No SKILL.md found" }
            withContext(Dispatchers.IO) {
                val targets = entries.map { entry ->
                    val name = entry.parentFile!!.name.replace(Regex("[^a-zA-Z0-9._-]"), "-")
                    require(name !in listOf("", ".", ".."))
                    val target = File(brain.skillsDir, name)
                    check(!target.exists()) { "Skill already exists: $name" }
                    entry.parentFile!! to target
                }
                check(targets.map { it.second }.distinct().size == targets.size) { "Archive has duplicate skill names" }
                try { for ((from, to) in targets) from.copyRecursively(to) }
                catch (e: Exception) { targets.forEach { it.second.deleteRecursively() }; throw e }
            }
            brain.reload()
            return entries.size
        } finally { withContext(Dispatchers.IO) { root.parentFile!!.deleteRecursively() } }
    }

    suspend fun mcp(name: String, url: String) {
        require(name.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid MCP name" }
        val spec = if (url.startsWith("https://github.com/")) {
            val root = repository(url)
            try {
                val packages = root.walkTopDown().filter { it.name == "package.json" }.mapNotNull { file ->
                    runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { it.has("bin") }?.str("name")
                }.toList()
                check(packages.size == 1) { "Repository has ${packages.size} CLI packages; specify npm:package or add its command manually" }
                packages.single()
            } finally { root.parentFile!!.deleteRecursively() }
        } else url.removePrefix("npm:")
        val server = if (url.startsWith("https://") && !url.startsWith("https://github.com/")) jsonOf("type" to "remote", "url" to url)
        else jsonOf("type" to "local", "command" to JSONArray().put("npx").put("-y").put(spec))
        brain.addMcp(name, server)
    }

    private suspend fun repository(url: String): File = withContext(Dispatchers.IO) {
        val parsed = java.net.URI(url)
        require(parsed.scheme == "https" && parsed.host == "github.com") { "Use a public GitHub repository URL" }
        val parts = parsed.path.trim('/').split('/')
        require(parts.size >= 2 && parts.take(2).all { it.matches(Regex("[a-zA-Z0-9._-]+")) }) { "Invalid repository URL" }
        val ref = if (parts.getOrNull(2) == "tree") parts.getOrNull(3) else null
        val target = "https://api.github.com/repos/${parts[0]}/${parts[1].removeSuffix(".git")}/zipball" + (ref?.let { "/${com.opencode.engine.Api.enc(it)}" } ?: "")
        val dir = File(context.cacheDir, "extension-${System.nanoTime()}").apply { mkdirs() }
        val connection = URL(target).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 30000
        try {
            check(connection.responseCode == 200) { "GitHub: HTTP ${connection.responseCode}" }
            unpack(connection.inputStream, dir, 128L * 1024 * 1024)
            dir.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: error(tr("Некорректный архив репозитория", "Invalid repository archive"))
        } catch (e: Exception) { dir.deleteRecursively(); throw e } finally { connection.disconnect() }
    }
}
