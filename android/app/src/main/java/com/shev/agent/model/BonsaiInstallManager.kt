package com.shev.agent.model

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Secure, resumable installer for the pinned Bonsai GGUF and Android ARM64 runtime. */
class BonsaiInstallManager(context: Context) {
    private val root = File(context.applicationContext.filesDir, "bonsai")
    private val model = File(root, MODEL_FILE)
    private val modelPart = File(root, "$MODEL_FILE.part")
    private val modelMarker = File(root, "model.sha256")
    private val runtimeDir = File(root, "runtime")
    private val runtimeZip = File(root, "runtime.zip.part")

    data class Progress(val stage: String, val downloaded: Long, val total: Long?) {
        val fraction: Float? get() = total?.takeIf { it > 0L }?.let {
            (downloaded.toDouble() / it).toFloat().coerceIn(0f, 1f)
        }
    }

    fun installedModelFile(): File = model
    fun installedRuntimeLibDirectory(): File = File(runtimeDir, "lib")

    suspend fun isModelInstalled() = withContext(Dispatchers.IO) {
        model.isFile && model.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES &&
            modelMarker.isFile && modelMarker.readText().trim() == MODEL_SHA256
    }

    suspend fun isRuntimeInstalled() = withContext(Dispatchers.IO) {
        File(runtimeDir, "lib/libllama.so").isFile &&
            File(runtimeDir, "lib/libbonsai_jni.so").isFile &&
            File(runtimeDir, "BUILD-METADATA.txt").isFile
    }

    suspend fun installModel(onProgress: (Progress) -> Unit = {}): File = withContext(Dispatchers.IO) {
        root.mkdirs()
        if (isModelInstalled()) return@withContext model
        requireFreeSpace(MAX_MODEL_BYTES + STORAGE_RESERVE_BYTES)
        download(MODEL_URL, modelPart, MAX_MODEL_BYTES, "Модель", onProgress)
        if (modelPart.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            modelPart.delete()
            error("Размер GGUF не соответствует ожидаемому; неполный файл удалён.")
        }
        if (sha256(modelPart) != MODEL_SHA256) {
            modelPart.delete()
            error("SHA-256 Bonsai не совпал; файл удалён, повтори загрузку.")
        }
        promote(modelPart, model)
        modelMarker.writeText(MODEL_SHA256)
        model
    }

    suspend fun installRuntime(onProgress: (Progress) -> Unit = {}): File = withContext(Dispatchers.IO) {
        root.mkdirs()
        if (isRuntimeInstalled()) return@withContext runtimeDir
        requireFreeSpace(RUNTIME_REQUIRED_SPACE_BYTES)
        val base = "https://github.com/shevgti/SheV-Agent-Public/releases/download/$RUNTIME_TAG"
        download("$base/$RUNTIME_ZIP", runtimeZip, MAX_RUNTIME_ZIP_BYTES, "Движок", onProgress)
        if (sha256(runtimeZip) != RUNTIME_SHA256) {
            runtimeZip.delete()
            error("SHA-256 архива движка не совпал с закреплённой сборкой; архив удалён.")
        }
        val staging = File(root, "runtime.installing")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Не удалось подготовить установку движка." }
        try {
            ZipInputStream(runtimeZip.inputStream().buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.replace('\\', '/')
                    require(!relative.startsWith("/") && relative.split('/').none { it == ".." }) { "Архив содержит небезопасный путь." }
                    val output = File(staging, relative).canonicalFile
                    require(output.path.startsWith(staging.canonicalPath + File.separator)) { "Архив выходит за каталог установки." }
                    if (entry.isDirectory) output.mkdirs() else {
                        output.parentFile?.mkdirs()
                        val destination = output.outputStream().buffered()
                        try {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                destination.write(buffer, 0, count)
                            }
                            destination.flush()
                        } finally { destination.close() }
                    }
                    zip.closeEntry()
                }
            }
            check(File(staging, "lib/libllama.so").isFile) { "В архиве отсутствует libllama.so." }
            check(File(staging, "BUILD-METADATA.txt").isFile) { "В архиве отсутствуют метаданные сборки." }
            runtimeDir.deleteRecursively()
            check(staging.renameTo(runtimeDir)) { "Не удалось активировать runtime." }
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
        runtimeZip.delete()
        runtimeDir
    }

    private suspend fun download(url: String, partial: File, maxBytes: Long, stage: String, onProgress: (Progress) -> Unit) {
        require(URL(url).protocol == "https") { "Разрешены только HTTPS-загрузки." }
        var offset = partial.takeIf { it.isFile }?.length() ?: 0L
        if (offset > maxBytes) { partial.delete(); offset = 0L }
        var connection: HttpURLConnection? = null
        try {
            currentCoroutineContext().ensureActive()
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MegaAgent-Android")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            val status = connection.responseCode
            if (status == 416) {
                partial.delete()
                error("$stage: сервер отклонил смещение продолжения; повтори загрузку.")
            }
            require(status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) { "$stage: HTTP $status." }
            val append = status == HttpURLConnection.HTTP_PARTIAL
            if (append && !connection.getHeaderField("Content-Range").orEmpty().startsWith("bytes $offset-")) {
                partial.delete()
                error("$stage: сервер вернул неверный диапазон продолжения.")
            }
            if (offset > 0L && !append) { partial.delete(); offset = 0L }
            val length = connection.contentLengthLong.takeIf { it >= 0L }
            val total = if (append) length?.plus(offset) else length
            require(total == null || total <= maxBytes) { "$stage превышает допустимый размер." }
            var written = offset
            onProgress(Progress(stage, written, total))
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= maxBytes) { "$stage превысил допустимый размер." }
                        output.write(buffer, 0, count)
                        onProgress(Progress(stage, written, total))
                    }
                    output.fd.sync()
                }
            }
            if (total != null) check(written == total) { "$stage загрузился не полностью." }
        } finally { connection?.disconnect() }
    }

    private fun requireFreeSpace(requiredBytes: Long) {
        val available = StatFs(root.absolutePath).availableBytes
        require(available >= requiredBytes) {
            "Для установки Bonsai нужно ещё ${requiredBytes / 1_000_000_000L} ГБ свободного места."
        }
    }

    private fun promote(source: File, target: File) {
        val backup = File(target.parentFile, target.name + ".old")
        if (backup.exists()) backup.delete()
        if (target.exists()) check(target.renameTo(backup)) { "Не удалось сохранить прежний файл." }
        if (!source.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            error("Не удалось завершить установку.")
        }
        backup.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_REPOSITORY = "prism-ml/Ternary-Bonsai-2-9B-gguf"
        const val MODEL_REVISION = "5b2abb02362efbed46bbe204014b02837778b2c2"
        const val MODEL_FILE = "Ternary-Bonsai-2-9B-Q2_0.gguf"
        const val MODEL_SHA256 = "1f884aa56aacd2bbbb2e4b7abaead2b1102b74a9c75a23fc33dab916813a384d"
        const val MODEL_URL = "https://huggingface.co/$MODEL_REPOSITORY/resolve/$MODEL_REVISION/$MODEL_FILE"
        const val MIN_MODEL_BYTES = 2_400_000_000L
        const val MAX_MODEL_BYTES = 2_800_000_000L
        const val RUNTIME_TAG = "bonsai-android-arm64-latest"
        const val RUNTIME_ZIP = "bonsai-llama-android-arm64.zip"
        const val RUNTIME_SHA256 = "61b6d9c15755a6c89a5eb59b2411e3df0add06a82c62207f09dce9c16367bcad"
        const val MAX_RUNTIME_ZIP_BYTES = 500_000_000L
        const val RUNTIME_REQUIRED_SPACE_BYTES = 1_000_000_000L
        const val STORAGE_RESERVE_BYTES = 512_000_000L
    }
}
