package com.opencode.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.opencode.BuildConfig
import com.opencode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    object UpToDate : UpdateStatus()
    data class Available(
        val version: String,
        val notes: String,
        val downloadUrl: String,
        val sizeBytes: Long
    ) : UpdateStatus()
    data class Downloading(val progress: Float) : UpdateStatus()
    data class ReadyToInstall(val version: String, val file: File) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

class Updater(private val context: Context, var repo: String = "Bebrik2283555/OpencodeM") {
    var status: UpdateStatus by mutableStateOf(UpdateStatus.Idle)
        private set

    suspend fun check(manual: Boolean = false) = withContext(Dispatchers.IO) {
        if (status is UpdateStatus.Checking || status is UpdateStatus.Downloading) return@withContext
        status = UpdateStatus.Checking
        try {
            val url = URL("https://api.github.com/repos/$repo/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "OpencodeM-Android")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 8000
                readTimeout = 12000
            }
            val code = conn.responseCode
            if (code == 404) {
                status = if (manual) UpdateStatus.Error(tr("Релизы в репозитории пока не найдены", "No releases found in repository yet")) else UpdateStatus.Idle
                return@withContext
            }
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                status = UpdateStatus.Error(tr("Ошибка проверки ($code)", "Check error ($code)"))
                return@withContext
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val tagName = json.optString("tag_name", "").trim()
            val notes = json.optString("body", "").trim()
            val assets = json.optJSONArray("assets")

            var apkUrl: String? = null
            var apkSize: Long = 0
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        apkSize = asset.optLong("size", 0)
                        break
                    }
                }
            }

            val currentVer = BuildConfig.VERSION_NAME
            if (!tagName.matches(Regex("""^v?[0-9]+(\.[0-9]+)*(-[a-zA-Z0-9.]+)?$"""))) {
                status = UpdateStatus.Error(tr("Некорректная версия релиза", "Invalid release tag"))
                return@withContext
            }
            if (tagName.isNotBlank() && isNewerVersion(tagName, currentVer) && apkUrl != null) {
                status = UpdateStatus.Available(
                    version = tagName.removePrefix("v"),
                    notes = notes,
                    downloadUrl = apkUrl,
                    sizeBytes = apkSize
                )
            } else {
                status = UpdateStatus.UpToDate
            }
        } catch (e: Exception) {
            status = UpdateStatus.Error(e.message ?: e.toString())
        }
    }

    suspend fun downloadAndInstall(available: UpdateStatus.Available) = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "OpencodeM-${available.version}.apk")
        updatesDir.listFiles().orEmpty()
            .filter { it.extension.equals("apk", true) && it.name != apkFile.name }
            .forEach { it.delete() }

        status = UpdateStatus.Downloading(0f)

        try {
            var currentUrl = available.downloadUrl
            var redirectCount = 0
            var conn: HttpURLConnection

            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "OpencodeM-Android")
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                val code = conn.responseCode
                if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                    val loc = conn.getHeaderField("Location") ?: break
                    currentUrl = loc
                    redirectCount++
                    if (redirectCount > 8) throw IllegalStateException(tr("Слишком много перенаправлений", "Too many redirects"))
                    continue
                }
                break
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: available.sizeBytes
            var bytesRead = 0L

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(32768)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (totalBytes > 0) {
                            val prog = (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                            status = UpdateStatus.Downloading(prog)
                        }
                    }
                }
            }

            status = UpdateStatus.ReadyToInstall(available.version, apkFile)
            install(apkFile)
        } catch (e: Exception) {
            apkFile.delete()
            status = UpdateStatus.Error(e.message ?: e.toString())
        }
    }

    suspend fun install(apkFile: File) = withContext(Dispatchers.IO) {
        if (!apkFile.exists()) return@withContext
        if (!signedByThisApp(apkFile)) {
            apkFile.delete()
            status = UpdateStatus.Error(tr("APK подписан не этим приложением", "APK is not signed by this app"))
            return@withContext
        }
        // Try root install with timeout if available
        val rootSuccess = runCatching {
            require(apkFile.name.matches(Regex("^[a-zA-Z0-9._-]+\\.apk$"))) { "Invalid APK filename" }
            val escapedPath = apkFile.absolutePath.replace("'", "'\\''")
            val p = ProcessBuilder("/system/bin/su", "-c", "pm install -r '$escapedPath'").start()
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)

        if (rootSuccess) {
            apkFile.delete()
        } else {
            // Standard Android Package Installer
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            withContext(Dispatchers.Main) { context.startActivity(intent) }
        }
    }

    private fun signedByThisApp(apk: File): Boolean {
        return runCatching {
            val pm = context.packageManager
            val target = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
            if (target.packageName != context.packageName) return false
            val current = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            // history keeps the old key after a signing-key rotation
            val trusted = current.signingInfo?.signingCertificateHistory
                ?: current.signingInfo?.apkContentsSigners ?: return false
            val archive = target.signingInfo?.apkContentsSigners ?: return false
            sameSigners(archive.map { it.toByteArray() }, trusted.map { it.toByteArray() })
        }.getOrDefault(false)
    }

    companion object {
        /** Every signer of the downloaded APK must be one this app trusts (current or rotated-out key). */
        fun sameSigners(archive: List<ByteArray>, trusted: List<ByteArray>): Boolean =
            archive.isNotEmpty() && archive.all { a -> trusted.any { it.contentEquals(a) } }
        fun isNewerVersion(latest: String, current: String): Boolean {
            val l = latest.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
            val c = current.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(l.size, c.size)
            for (i in 0 until maxLen) {
                val lv = l.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (lv > cv) return true
                if (lv < cv) return false
            }
            return false
        }
    }
}
