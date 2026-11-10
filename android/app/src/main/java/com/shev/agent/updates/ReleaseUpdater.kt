package com.shev.agent.updates

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(val version: String, val apkUrl: String, val checksumUrl: String)

object ReleaseUpdater {
    const val RELEASE_REPOSITORY = "shevgti/SheV-Agent-Public"
    private const val APK_ASSET = "app-debug.apk"
    private const val CHECKSUM_ASSET = "app-debug.apk.sha256"

    suspend fun check(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        val connection = (URL("https://api.github.com/repos/$RELEASE_REPOSITORY/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            if (connection.responseCode == 404) return@withContext null
            check(connection.responseCode in 200..299) { "GitHub: HTTP ${connection.responseCode}" }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.optString("tag_name").removePrefix("v")
            if (version.isBlank() || compareVersions(version, currentVersion) <= 0) return@withContext null
            val assets = release.getJSONArray("assets")
            var apk: String? = null
            var checksum: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                when (asset.optString("name")) {
                    APK_ASSET -> apk = asset.optString("browser_download_url")
                    CHECKSUM_ASSET -> checksum = asset.optString("browser_download_url")
                }
            }
            if (apk.isNullOrBlank() || checksum.isNullOrBlank()) error("В релизе отсутствует APK или SHA-256")
            ReleaseInfo(version, apk!!, checksum!!)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndPromptInstall(context: Context, release: ReleaseInfo): Boolean =
        withContext(Dispatchers.IO) {
            val apkFile = File(context.cacheDir, APK_ASSET)
            val partialFile = File(context.cacheDir, "$APK_ASSET.part")
            partialFile.delete()
            download(release.apkUrl, partialFile)
            val expected = readTrustedText(release.checksumUrl).trim().substringBefore(" ").lowercase()
            val actual = sha256(partialFile)
            check(expected.matches(Regex("[a-f0-9]{64}")) && expected == actual) {
                partialFile.delete()
                "Контрольная сумма APK не совпадает"
            }
            check(partialFile.renameTo(apkFile) || run {
                partialFile.copyTo(apkFile, overwrite = true)
                partialFile.delete()
            }) { "Не удалось подготовить APK после проверки." }
            withContext(Dispatchers.Main) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settingsIntent)
                    return@withContext false
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            true
        }

    private fun readTrustedText(url: String): String {
        val connection = openTrustedConnection(url, 10_000)
        try {
            check(connection.responseCode in 200..299) {
                "Ошибка загрузки контрольной суммы: HTTP ${connection.responseCode}"
            }
            return connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(256)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(result.length + count <= MAX_CHECKSUM_CHARS) {
                        "Файл контрольной суммы слишком большой."
                    }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, destination: File) {
        val connection = openTrustedConnection(url, 60_000)
        try {
            check(connection.responseCode in 200..299) {
                "Ошибка скачивания APK: HTTP ${connection.responseCode}"
            }
            val length = connection.contentLengthLong
            require(length < 0L || length <= MAX_APK_BYTES) { "APK превышает допустимый размер." }
            var copied = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_APK_BYTES) { "APK превышает допустимый размер." }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    /** Manually follows a small number of redirects and validates every hop before connecting. */
    private fun openTrustedConnection(initialUrl: String, readTimeoutMs: Int): HttpURLConnection {
        var current = ReleaseDownloadPolicy.validate(URL(initialUrl))
        repeat(MAX_REDIRECTS + 1) { hop ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
            }
            val status = connection.responseCode
            if (status in REDIRECT_STATUS_CODES) {
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

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val result = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private const val MAX_REDIRECTS = 5
    private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    private const val MAX_CHECKSUM_CHARS = 1024
    private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
}
