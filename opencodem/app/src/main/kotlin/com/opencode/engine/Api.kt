package com.opencode.engine

import com.opencode.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

class ApiError(val code: Int, message: String) : Exception(message)

/** opencode's HTTP API, as its own desktop app uses it. */
class Api(private val engine: Engine) {
    private fun open(path: String): HttpURLConnection {
        val c = URL("http://127.0.0.1:${engine.port}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 120_000
        c.setRequestProperty("Authorization", engine.auth())
        return c
    }

    suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val c = open(path)
        try {
            // Android's HttpURLConnection is OkHttp underneath and takes PATCH, unlike the JDK's.
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val o = runCatching { JSONObject(text) }.getOrNull()
                throw ApiError(code, o?.str("message") ?: o?.optJSONObject("error")?.str("message") ?: text.take(400).ifEmpty { "HTTP $code" })
            }
            if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject().put("text", text) }
        } finally {
            c.disconnect()
        }
    }

    suspend fun get(path: String) = call("GET", path)
    suspend fun post(path: String, body: JSONObject = JSONObject()) = call("POST", path, body)
    suspend fun delete(path: String) = call("DELETE", path)

    /** Every event opencode sends, until the coroutine is cancelled or the stream breaks. */
    suspend fun events(on: (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val c = open("/api/event")
        c.readTimeout = 0
        c.setRequestProperty("Accept", "text/event-stream")
        val hook = coroutineContext.job.invokeOnCompletion { runCatching { c.disconnect() } }
        try {
            if (c.responseCode != 200) throw ApiError(c.responseCode, "events: HTTP ${c.responseCode}")
            val data = StringBuilder()
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    coroutineContext.ensureActive()
                    val line = r.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) runCatching { JSONObject(data.toString()) }.getOrNull()?.let(on)
                            data.clear()
                        }
                        line.startsWith("data:") -> data.append(line.substring(5).trimStart())
                    }
                }
            }
        } finally {
            hook.dispose()
            c.disconnect()
        }
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
