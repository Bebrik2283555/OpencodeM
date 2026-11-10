package com.opencode.data

import android.content.Context
import android.net.Uri
import com.opencode.Brain
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Backup(private val context: Context, private val brain: Brain) {
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val chats = brain.api.get("/api/session?limit=10000").getJSONArray("data").objects()
        ZipOutputStream(context.contentResolver.openOutputStream(uri, "wt")!!).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("margy-backup.json", jsonOf("version" to 1).toString().toByteArray())
            for (chat in chats) {
                val id = chat.getString("id")
                val data = brain.api.get("/api/experimental/session/$id/export").getJSONObject("data")
                put("chats/$id.json", data.toString().toByteArray())
            }
            put("settings/prefs.json", brain.prefs.json().toString().toByteArray())
            for (name in listOf("opencode.json", "AGENTS.md", "agents", "commands", "skills", "plugins")) {
                val root = File(brain.engine.config, name)
                if (!root.exists()) continue
                Files.walk(root.toPath()).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
                    it.none { segment -> segment.toString() == "node_modules" } }.forEach {
                    put("config/" + brain.engine.config.toPath().relativize(it).toString().replace('\\', '/'), Files.readAllBytes(it))
                } }
            }
        }
    }

    suspend fun restore(uri: Uri): Int {
        check(brain.busy.values.none { it } && brain.downloads.isEmpty()) { tr("Сначала завершите текущую работу", "Finish active work before restoring") }
        val staging = File(context.cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        val previous = File(context.filesDir, "before-restore-${System.currentTimeMillis()}")
        try {
            val files = withContext(Dispatchers.IO) { unpack(context.contentResolver.openInputStream(uri)!!, staging) }
            check(JSONObject(File(staging, "margy-backup.json").readText()).getInt("version") == 1) { tr("Неподдерживаемая резервная копия", "Unsupported backup") }
            val chats = files.filter { it.parentFile == File(staging, "chats") }.map { JSONObject(it.readText()) }
            val config = File(staging, "config/opencode.json")
            if (config.exists()) JSONObject(config.readText())
            val preferences = File(staging, "settings/prefs.json").takeIf { it.exists() }?.let { Prefs.from(JSONObject(it.readText())) }
            val configRoot = File(staging, "config")
            val replacements = files.filter { it.toPath().startsWith(configRoot.toPath()) }.map { file ->
                val relative = configRoot.toPath().relativize(file.toPath()).toString()
                require(relative.substringBefore(File.separator) in listOf("opencode.json", "AGENTS.md", "agents", "commands", "skills", "plugins")) { tr("Неизвестный элемент конфигурации", "Unknown config entry") }
                file to archiveTarget(brain.engine.config, relative)
            }
            for (chat in chats) { chat.getJSONObject("info").getString("id"); chat.getJSONArray("messages") }
            var imported = 0
            val remaining = chats.toMutableList()
            while (remaining.isNotEmpty()) {
                val ready = remaining.filter { chat -> remaining.none { it.getJSONObject("info").getString("id") == chat.getJSONObject("info").str("parentID") } }
                val batch = if (ready.isNotEmpty()) ready else {
                    // Resolve broken or cyclic parent references by importing remaining chats as roots
                    remaining.forEach { it.getJSONObject("info").remove("parentID") }
                    remaining.toList()
                }
                for (chat in batch) {
                    try {
                        brain.api.post("/api/experimental/session/import", jsonOf("info" to chat.getJSONObject("info"),
                            "messages" to chat.getJSONArray("messages"), "location" to jsonOf("directory" to brain.folder().absolutePath)))
                        imported++
                    } catch (e: com.opencode.engine.ApiError) { if (e.code != 409) throw e }
                    remaining.remove(chat)
                }
            }
            val originals = replacements.associate { (_, out) -> out to out.takeIf { it.exists() }?.readBytes() }
            try {
                withContext(Dispatchers.IO) {
                    previous.mkdirs()
                    for ((out, bytes) in originals) if (bytes != null) {
                        val saved = archiveTarget(previous, brain.engine.config.toPath().relativize(out.toPath()).toString())
                        saved.parentFile!!.mkdirs(); saved.writeBytes(bytes)
                    }
                    for ((file, out) in replacements) { out.parentFile!!.mkdirs(); file.copyTo(out, overwrite = true) }
                }
                brain.reload()
                if (preferences != null) { brain.update { preferences }; brain.restart() }
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    for ((out, bytes) in originals) if (bytes == null) out.delete() else out.writeBytes(bytes)
                }
                withContext(NonCancellable) { runCatching { brain.reload() } }
                throw e
            }
            return imported
        } finally { withContext(NonCancellable + Dispatchers.IO) { staging.deleteRecursively() } }
    }
}
