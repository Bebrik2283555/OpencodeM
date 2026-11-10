package com.opencode.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The app's own choices. Keys, chats and providers live in opencode. */
data class Prefs(
    val model: String? = null,
    val favourites: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    /** Chosen variant (thinking level) per model ref; missing means the model's default. */
    val variants: Map<String, String> = emptyMap(),
    val theme: String = "system",
    val language: String = "auto",
    /** Where new chats work; empty means the app's own workspace. */
    val folder: String = "",
    /** Run the agent's commands in Alpine once it is installed. */
    val linux: Boolean = true,
    val runtime: String = "auto",
    /** Context for models on the phone: more remembers more but needs more memory. */
    val localContext: Int = 16384,
    /** Models on the phone get opencode's tools too; off, they just talk, with a prompt ten times shorter. */
    val localTools: Boolean = false,
    val agent: String = "build",
    val lastSession: String? = null,
    val phoneControl: Boolean = false,
    /** Run Alpine commands through a real root chroot; proot hangs on pipes on some kernels. */
    val alpineRoot: Boolean = false,
    /** default asks before sensitive actions; turbo allows everything. */
    val permissionMode: String = "default",
    /** Model ids per provider, so disabling one does not move it to the end. */
    val modelOrder: Map<String, List<String>> = emptyMap(),
    val projects: List<String> = emptyList(),
) {
    fun json() = jsonOf(
        "model" to model,
        "favourites" to JSONArray(favourites),
        "recent" to JSONArray(recent),
        "variants" to JSONObject(variants),
        "theme" to theme,
        "language" to language,
        "folder" to folder,
        "linux" to linux,
        "runtime" to runtime,
        "localContext" to localContext,
        "localTools" to localTools,
        "agent" to agent,
        "lastSession" to lastSession,
        "phoneControl" to phoneControl,
        "alpineRoot" to alpineRoot,
        "permissionMode" to permissionMode,
        "modelOrder" to JSONObject(modelOrder),
        "projects" to JSONArray(projects),
    )

    companion object {
        private fun file(context: Context) = File(context.filesDir, "prefs.json")

        fun load(context: Context): Prefs {
            val o = runCatching { JSONObject(file(context).readText()) }.getOrNull() ?: return Prefs()
            return from(o)
        }

        fun from(o: JSONObject): Prefs {
            return Prefs(
                model = o.str("model"),
                favourites = o.optJSONArray("favourites").strings(),
                recent = o.optJSONArray("recent").strings(),
                variants = o.optJSONObject("variants")?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } } ?: emptyMap(),
                theme = o.str("theme") ?: "system",
                language = o.str("language")?.takeIf { it in listOf("auto", "ru", "en") } ?: "auto",
                folder = o.str("folder") ?: "",
                linux = o.optBoolean("linux", true),
                runtime = o.str("runtime")?.takeIf { it in listOf("auto", "native", "alpine") } ?: "auto",
                localContext = o.optInt("localContext", 16384),
                localTools = o.optBoolean("localTools"),
                agent = o.str("agent") ?: "build",
                lastSession = o.str("lastSession"),
                phoneControl = o.optBoolean("phoneControl"),
                alpineRoot = o.optBoolean("alpineRoot"),
                permissionMode = o.str("permissionMode")?.takeIf { it in listOf("default", "turbo") } ?: "default",
                modelOrder = o.optJSONObject("modelOrder")?.let { m ->
                    m.keys().asSequence().associateWith { m.optJSONArray(it).strings() }
                } ?: emptyMap(),
                projects = o.optJSONArray("projects").strings(),
            )
        }

        fun save(context: Context, prefs: Prefs) {
            val tmp = File(context.filesDir, "prefs.json.tmp")
            tmp.writeText(prefs.json().toString(1))
            runCatching {
                java.nio.file.Files.move(
                    tmp.toPath(), file(context).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            }.recover {
                if (!tmp.renameTo(file(context))) {
                    tmp.copyTo(file(context), overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}
