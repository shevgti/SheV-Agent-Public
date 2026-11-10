package com.shev.agent.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val notes: String
)

internal object AppUpdater {
    private const val RELEASE_API = "https://api.github.com/repos/shevgti/SheV-Agent-Public/releases/latest"
    private const val APK_NAME = "app-debug.apk"

    suspend fun check(installedVersionCode: Int): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
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
            val versionCode = Regex("""(?im)^\\s*versionCode\\s*:\\s*(\\d+)\\s*$""")
                .find(release.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
                ?: error("В описании релиза отсутствует versionCode")
            val assets = release.optJSONArray("assets") ?: error("В релизе нет файлов")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == APK_NAME) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) error("В релизе нет файла $APK_NAME")
            if (versionCode <= installedVersionCode) null
            else AppUpdate(versionCode, versionName, apkUrl, release.optString("body"))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        update: AppUpdate,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val canInstall = withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                false
            } else true
        }
        if (!canInstall) return false

        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates").apply {
                if (!exists() && !mkdirs()) error("Не удалось создать папку обновления")
            }
            val apk = File(directory, APK_NAME)
            val partial = File(directory, "$APK_NAME.part")
            partial.delete()
            val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30000
                readTimeout = 90000
                setRequestProperty("User-Agent", "SheV-Agent-Android")
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                val response = connection.responseCode
                if (response !in 200..299) error("Загрузка ответила: HTTP $response")
                val total = connection.contentLengthLong
                var downloaded = 0L
                var reported = 0L
                connection.inputStream.buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - reported >= 256 * 1024) {
                                reported = downloaded
                                withContext(Dispatchers.Main) { onProgress(downloaded, total) }
                            }
                        }
                    }
                }
                if (downloaded < 2L || (total > 0L && downloaded != total)) {
                    error("Загрузка APK завершилась не полностью")
                }
                val signature = partial.inputStream().use { byteArrayOf(it.read().toByte(), it.read().toByte()) }
                if (signature[0] != 0x50.toByte() || signature[1] != 0x4B.toByte()) {
                    error("GitHub вернул файл, который не похож на APK")
                }
                if (apk.exists() && !apk.delete()) error("Не удалось заменить временный APK")
                if (!partial.renameTo(apk)) error("Не удалось сохранить APK")
                withContext(Dispatchers.Main) { onProgress(downloaded, if (total > 0) total else downloaded) }
            } catch (error: Exception) {
                partial.delete()
                throw error
            } finally {
                connection.disconnect()
            }
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(installIntent)
            }
        }
        return true
    }
}
