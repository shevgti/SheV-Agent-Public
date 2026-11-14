package com.shev.agent.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shev.agent.updates.ReleaseDownloadPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val checksumUrl: String,
    val notes: String
)

internal object AppUpdater {
    private const val RELEASE_API = "https://api.github.com/repos/shevgti/SheV-Agent-Public/releases/latest"
    private const val APK_NAME = "app-debug.apk"
    private const val CHECKSUM_NAME = "app-debug.apk.sha256"
    private const val PREFS = "shev_downloaded_update"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_VERSION_NAME = "version_name"
    private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5

    suspend fun check(installedVersionCode: Int): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setUseCaches(false)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SheV-Agent-Android")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (connection.responseCode !in 200..299) error("GitHub ответил: HTTP ${connection.responseCode}")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@withContext null
            val tag = release.optString("tag_name").trim()
            if (tag.isBlank()) error("Релиз не содержит номер версии")
            val versionName = tag.removePrefix("v")
            val versionCode = Regex("""(?im)^\s*versionCode\s*:\s*(\d+)\s*$""")
                .find(release.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
                ?: error("В описании релиза отсутствует versionCode")
            val assets = release.optJSONArray("assets") ?: error("В релизе нет файлов")
            var apkUrl: String? = null
            var checksumUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                when (asset.optString("name")) {
                    APK_NAME -> apkUrl = asset.optString("browser_download_url")
                    CHECKSUM_NAME -> checksumUrl = asset.optString("browser_download_url")
                }
            }
            if (apkUrl.isNullOrBlank()) error("В релизе нет файла $APK_NAME")
            if (checksumUrl.isNullOrBlank()) error("В релизе нет контрольной суммы $CHECKSUM_NAME")
            if (versionCode <= installedVersionCode) null
            else AppUpdate(versionCode, versionName, apkUrl, checksumUrl, release.optString("body"))
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads and verifies the update in the background. Does not open Android's installer. */
    suspend fun downloadToCache(
        context: Context,
        update: AppUpdate,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply {
            if (!exists() && !mkdirs()) error("Не удалось создать папку обновления")
        }
        val apk = File(directory, APK_NAME)
        val partial = File(directory, "$APK_NAME.part")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (apk.isFile && apk.length() > 0L &&
            prefs.getInt(KEY_VERSION_CODE, 0) == update.versionCode
        ) {
            withContext(Dispatchers.Main) { onProgress(apk.length(), apk.length()) }
            return@withContext apk
        }

        partial.delete()
        try {
            val connection = openTrustedConnection(update.downloadUrl, 180_000)
            try {
                if (connection.responseCode !in 200..299) error("Загрузка APK ответила: HTTP ${connection.responseCode}")
                val expectedLength = connection.contentLengthLong
                require(expectedLength < 0L || expectedLength <= MAX_APK_BYTES) {
                    "APK превышает допустимый размер."
                }
                var downloaded = 0L
                var lastReported = 0L
                connection.inputStream.buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            require(downloaded <= MAX_APK_BYTES) { "APK превышает допустимый размер." }
                            output.write(buffer, 0, count)
                            if (downloaded - lastReported >= 256 * 1024) {
                                lastReported = downloaded
                                withContext(Dispatchers.Main) { onProgress(downloaded, expectedLength) }
                            }
                        }
                    }
                }
                require(downloaded >= 2L && (expectedLength < 0L || downloaded == expectedLength)) {
                    "Загрузка APK завершилась не полностью."
                }
            } finally {
                connection.disconnect()
            }

            val magic = partial.inputStream().use { byteArrayOf(it.read().toByte(), it.read().toByte()) }
            require(magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()) {
                "GitHub вернул файл, который не похож на APK."
            }

            val checksumText = readTrustedText(update.checksumUrl)
            val expectedHash = Regex("""(?i)\b[a-f0-9]{64}\b""").find(checksumText)?.value?.lowercase()
                ?: error("Файл контрольной суммы не содержит SHA-256.")
            val actualHash = sha256(partial)
            require(expectedHash == actualHash) { "Контрольная сумма APK не совпадает." }

            if (apk.exists() && !apk.delete()) error("Не удалось заменить предыдущий APK.")
            if (!partial.renameTo(apk)) error("Не удалось сохранить скачанный APK.")
            withContext(Dispatchers.Main) { onProgress(apk.length(), apk.length()) }
            prefs.edit()
                .putInt(KEY_VERSION_CODE, update.versionCode)
                .putString(KEY_VERSION_NAME, update.versionName)
                .apply()
            apk
        } catch (failure: Exception) {
            partial.delete()
            throw failure
        }
    }

    /** Backward-compatible foreground flow used by the in-chat update dialog. */
    suspend fun downloadAndInstall(
        context: Context,
        update: AppUpdate,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        downloadToCache(context, update, onProgress)
        return withContext(Dispatchers.Main) { openInstaller(context) }
    }

    fun hasDownloadedUpdate(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_VERSION_CODE, 0) > 0 &&
            File(context.cacheDir, "updates/$APK_NAME").let { it.isFile && it.length() > 0L }
    }

    fun downloadedVersionName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VERSION_NAME, "").orEmpty()

    /** Called only from a foreground Activity after the user taps the update notification. */
    fun openInstaller(context: Context): Boolean {
        val apk = File(context.cacheDir, "updates/$APK_NAME")
        check(apk.isFile && apk.length() > 0L) { "Скачанный APK не найден. Подключись к интернету и повтори проверку." }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(settings)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        return true
    }

    private fun readTrustedText(url: String): String {
        val connection = openTrustedConnection(url, 15_000)
        try {
            if (connection.responseCode !in 200..299) error("Не удалось скачать SHA-256: HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText().take(1024) }
        } finally {
            connection.disconnect()
        }
    }

    private fun openTrustedConnection(initialUrl: String, readTimeoutMs: Int): HttpURLConnection {
        var current = ReleaseDownloadPolicy.validate(URL(initialUrl))
        repeat(MAX_REDIRECTS + 1) { hop ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SheV-Agent-Android")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank() && hop < MAX_REDIRECTS) {
                    "Слишком много перенаправлений при загрузке обновления."
                }
                current = ReleaseDownloadPolicy.validate(URL(current, location))
            } else {
                return connection
            }
        }
        error("Не удалось безопасно открыть адрес загрузки.")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
