package com.shev.agent.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shev.agent.updates.ReleaseDownloadPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

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

    suspend fun check(installedVersionCode: Int): AppUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setUseCaches(false)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SheV-Agent-Android")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("GitHub ответил: HTTP $status")
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
            if (checksumUrl.isNullOrBlank()) error("В релизе нет файла $CHECKSUM_NAME")
            val trustedApkUrl = ReleaseDownloadPolicy.validate(URL(apkUrl!!)).toString()
            val trustedChecksumUrl = ReleaseDownloadPolicy.validate(URL(checksumUrl!!)).toString()
            if (versionCode <= installedVersionCode) null
            else AppUpdate(versionCode, versionName, trustedApkUrl, trustedChecksumUrl, release.optString("body"))
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
            var lastFailure: Exception? = null
            var prepared = false

            for (attempt in 1..DOWNLOAD_ATTEMPTS) {
                partial.delete()
                try {
                    val (downloaded, total) = downloadOnce(update.downloadUrl, partial, onProgress)
                    val expectedHash = readChecksum(update.checksumUrl)
                    val actualHash = sha256(partial)
                    check(expectedHash == actualHash) {
                        "Контрольная сумма APK не совпала. Файл удалён, установка отменена."
                    }
                    validateApk(partial)
                    if (apk.exists() && !apk.delete()) error("Не удалось заменить предыдущий APK")
                    check(partial.renameTo(apk)) { "Не удалось сохранить проверенный APK" }
                    withContext(Dispatchers.Main) {
                        onProgress(downloaded, if (total > 0L) total else downloaded)
                    }
                    prepared = true
                    break
                } catch (cancelled: CancellationException) {
                    partial.delete()
                    throw cancelled
                } catch (failure: Exception) {
                    partial.delete()
                    lastFailure = failure
                    if (attempt < DOWNLOAD_ATTEMPTS) delay(RETRY_DELAY_MS * attempt)
                }
            }

            check(prepared) {
                "Не удалось скачать и проверить обновление после $DOWNLOAD_ATTEMPTS попыток: ${lastFailure?.message ?: "ошибка сети"}"
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

    private suspend fun downloadOnce(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit
    ): Pair<Long, Long> {
        val connection = openTrustedConnection(url, DOWNLOAD_READ_TIMEOUT_MS)
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Загрузка ответила: HTTP $status")
            val total = connection.contentLengthLong
            require(total <= MAX_APK_BYTES) { "APK превышает допустимый размер 512 МБ." }
            var downloaded = 0L
            var reported = 0L
            withContext(Dispatchers.Main) { onProgress(0L, total.coerceAtLeast(0L)) }
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= MAX_APK_BYTES) {
                            "APK превышает допустимый размер 512 МБ."
                        }
                        output.write(buffer, 0, count)
                        if (downloaded - reported >= PROGRESS_STEP_BYTES) {
                            reported = downloaded
                            withContext(Dispatchers.Main) {
                                onProgress(downloaded, total.coerceAtLeast(0L))
                            }
                        }
                    }
                    output.flush()
                }
            }
            require(downloaded > 0L && (total <= 0L || downloaded == total)) {
                "Загрузка APK завершилась не полностью: получено $downloaded из $total байт."
            }
            withContext(Dispatchers.Main) {
                onProgress(downloaded, if (total > 0L) total else downloaded)
            }
            return downloaded to total
        } finally {
            connection.disconnect()
        }
    }

    private fun readChecksum(url: String): String {
        val connection = openTrustedConnection(url, CHECKSUM_READ_TIMEOUT_MS)
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Не удалось получить SHA-256: HTTP $status")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(256)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_CHECKSUM_BYTES) {
                        "Файл контрольной суммы слишком большой."
                    }
                    output.write(buffer, 0, count)
                }
            }
            val text = output.toString(Charsets.UTF_8.name())
            return Regex("(?i)\\b([a-f0-9]{64})\\b").find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                ?: error("Файл релиза содержит некорректную контрольную сумму SHA-256.")
        } finally {
            connection.disconnect()
        }
    }

    private fun openTrustedConnection(initialUrl: String, readTimeoutMs: Int): HttpURLConnection {
        var current = ReleaseDownloadPolicy.validate(URL(initialUrl))
        repeat(MAX_REDIRECTS + 1) { hop ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "SheV-Agent-Android")
                setRequestProperty("Accept", "application/octet-stream")
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

    private fun validateApk(file: File) {
        val signature = file.inputStream().use { input ->
            ByteArray(4) { input.read().toByte() }
        }
        require(
            signature.size == 4 &&
                signature[0] == 0x50.toByte() &&
                signature[1] == 0x4B.toByte() &&
                signature[2] == 0x03.toByte() &&
                signature[3] == 0x04.toByte()
        ) { "GitHub вернул файл, который не является корректным APK-архивом." }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 300_000
        const val CHECKSUM_READ_TIMEOUT_MS = 20_000
        const val DOWNLOAD_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_500L
        const val PROGRESS_STEP_BYTES = 128L * 1024L
        const val MAX_APK_BYTES = 512L * 1024L * 1024L
        const val MAX_CHECKSUM_BYTES = 4_096
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
