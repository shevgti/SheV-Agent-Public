package com.shev.agent.model

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class ModelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let {
            (downloadedBytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

/**
 * Resumable, integrity-checked downloader for the optional on-device model.
 * All files stay in app-private storage; callers must explicitly invoke download().
 */
class ModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, "models")

    suspend fun findVerifiedModel(
        spec: ModelArtifactSpec = ModelArtifactSpec.GEMMA_4_E2B_IT
    ): File? = withContext(Dispatchers.IO) {
        val candidate = File(modelDirectory, spec.fileName)
        candidate.takeIf { it.isFile && sha256(it) == spec.sha256 }
    }

    /** Remove the pinned Gemma artifact and any interrupted download/import files. */
    suspend fun deleteModel(spec: ModelArtifactSpec = ModelArtifactSpec.GEMMA_4_E2B_IT) = withContext(Dispatchers.IO) {
        val targets = listOf(
            File(modelDirectory, spec.fileName),
            File(modelDirectory, "${spec.fileName}.part"),
            File(modelDirectory, "${spec.fileName}.import.part")
        )
        targets.forEach { file ->
            if (file.exists() && !file.delete()) {
                error("Не удалось удалить файл модели: ${file.name}")
            }
        }
    }

    /**
     * Imports an already-downloaded model from a user-selected document.
     * The source is never trusted: bytes are copied privately and SHA-256 is checked
     * before the file becomes visible to the runtime.
     */
    suspend fun importModel(
        source: Uri,
        spec: ModelArtifactSpec = ModelArtifactSpec.GEMMA_4_E2B_IT,
        onProgress: (ModelDownloadProgress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val destination = File(modelDirectory, spec.fileName)
        if (destination.isFile && sha256(destination) == spec.sha256) {
            onProgress(ModelDownloadProgress(destination.length(), destination.length()))
            return@withContext destination
        }
        requireFreeSpace(MODEL_SIZE_BYTES + STORAGE_RESERVE_BYTES)
        check(modelDirectory.exists() || modelDirectory.mkdirs()) {
            "Unable to create private model directory."
        }
        val partial = File(modelDirectory, "${spec.fileName}.import.part")
        if (partial.exists() && !partial.delete()) {
            error("Unable to clear an incomplete imported model.")
        }
        val totalBytes = runCatching {
            appContext.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0L }
        require(totalBytes == null || totalBytes <= MAX_IMPORT_BYTES) {
            "The selected file is larger than the safe model import limit."
        }
        try {
            val input = appContext.contentResolver.openInputStream(source)
                ?: error("Unable to open the selected model file.")
            var copied = 0L
            input.use { stream ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    onProgress(ModelDownloadProgress(0L, totalBytes))
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_IMPORT_BYTES) {
                            "The selected file is larger than the safe model import limit."
                        }
                        output.write(buffer, 0, count)
                        onProgress(ModelDownloadProgress(copied, totalBytes))
                    }
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            if (!partial.isFile || sha256(partial) != spec.sha256) {
                error("Imported model SHA-256 does not match the pinned Gemma artifact.")
            }
            promote(partial, destination)
            onProgress(ModelDownloadProgress(destination.length(), destination.length()))
            destination
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
    }

    suspend fun download(
        spec: ModelArtifactSpec = ModelArtifactSpec.GEMMA_4_E2B_IT,
        onProgress: (ModelDownloadProgress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        ModelDownloadUrlPolicy.validate(URL(spec.downloadUrl))
        check(modelDirectory.exists() || modelDirectory.mkdirs()) {
            "Unable to create private model directory."
        }

        val destination = File(modelDirectory, spec.fileName)
        val partial = File(modelDirectory, "${spec.fileName}.part")

        if (destination.isFile && sha256(destination) == spec.sha256) {
            onProgress(ModelDownloadProgress(destination.length(), destination.length()))
            return@withContext destination
        }
        val existingOffset = partial.takeIf { it.isFile }?.length() ?: 0L
        if (existingOffset > ModelDownloadLimits.MAX_BYTES) {
            partial.delete()
            error("Saved partial model exceeds the safe download limit and was discarded.")
        }
        requireFreeSpace((MODEL_SIZE_BYTES - existingOffset).coerceAtLeast(0L) + STORAGE_RESERVE_BYTES)
        if (destination.exists() && !destination.delete()) {
            error("Unable to replace an invalid existing model file.")
        }

        var offset = partial.takeIf { it.isFile }?.length() ?: 0L
        var connection: HttpURLConnection? = null
        try {
            currentCoroutineContext().ensureActive()
            connection = openModelConnection(spec.downloadUrl, offset)

            val status = connection.responseCode
            if (status == HTTP_RANGE_NOT_SATISFIABLE) {
                if (partial.isFile && sha256(partial) == spec.sha256) {
                    promote(partial, destination)
                    onProgress(ModelDownloadProgress(destination.length(), destination.length()))
                    return@withContext destination
                }
                // The saved offset is no longer accepted by the server; next user retry starts cleanly.
                partial.delete()
                error("The server rejected the saved resume offset; retry the download from the beginning.")
            }
            require(status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) {
                "Model server returned HTTP $status."
            }

            val append = status == HttpURLConnection.HTTP_PARTIAL
            val contentRange = if (append) {
                parseModelContentRange(connection.getHeaderField("Content-Range"))
                    ?: run {
                        partial.delete()
                        error("Server returned an invalid or unexpected resume range.")
                    }
            } else null
            if (append && contentRange?.start != offset) {
                partial.delete()
                error("Server returned an invalid or unexpected resume range.")
            }
            val responseLength = connection.contentLengthLong.takeIf { it >= 0L }
            val totalBytes = if (append) contentRange?.totalBytes else responseLength
            if (totalBytes != null && !ModelDownloadLimits.allows(totalBytes)) {
                partial.delete()
                error("Model server advertised a file larger than the safe download limit.")
            }
            if (append && contentRange != null &&
                !ModelDownloadLimits.allows(contentRange.endInclusive + 1L)) {
                partial.delete()
                error("Model server returned a range beyond the safe download limit.")
            }
            if (!append) {
                // The server ignored Range; truncating the partial file restarts from byte zero.
                // Recheck the full required space before opening FileOutputStream(false).
                offset = 0L
                requireFreeSpace(MODEL_SIZE_BYTES + STORAGE_RESERVE_BYTES)
            }

            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = offset
                    onProgress(ModelDownloadProgress(downloaded, totalBytes))
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        val nextDownloaded = downloaded + count
                        if (nextDownloaded > ModelDownloadLimits.MAX_BYTES) {
                            partial.delete()
                            error("Model download exceeded the safe file-size limit.")
                        }
                        output.write(buffer, 0, count)
                        downloaded = nextDownloaded
                        onProgress(ModelDownloadProgress(downloaded, totalBytes))
                    }
                    output.fd.sync()
                }
            }

            currentCoroutineContext().ensureActive()
            if (!partial.isFile || sha256(partial) != spec.sha256) {
                // Never resume from bytes that failed integrity verification.
                partial.delete()
                error("Model SHA-256 verification failed; the incomplete file was discarded.")
            }
            promote(partial, destination)
            onProgress(ModelDownloadProgress(destination.length(), destination.length()))
            destination
        } finally {
            connection?.disconnect()
        }
    }

    /** Follow only trusted HTTPS Hugging Face redirects; preserve the resume Range header. */
    private fun openModelConnection(initialUrl: String, offset: Long): HttpURLConnection {
        var current = ModelDownloadUrlPolicy.validate(URL(initialUrl))
        repeat(MAX_REDIRECTS + 1) { hop ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/octet-stream")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            val status = connection.responseCode
            if (status in REDIRECT_STATUS_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank() && hop < MAX_REDIRECTS) {
                    "Слишком много перенаправлений при загрузке модели."
                }
                current = ModelDownloadUrlPolicy.validate(URL(current, location))
            } else {
                return connection
            }
        }
        error("Не удалось безопасно открыть адрес модели.")
    }

    private fun requireFreeSpace(requiredBytes: Long) {
        val availableBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        require(availableBytes >= requiredBytes) {
            "Недостаточно свободного места. Нужно ещё примерно ${requiredBytes / 1_000_000_000} ГБ с резервом."
        }
    }

    private fun promote(partial: File, destination: File) {
        try {
            Files.move(
                partial.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            check(partial.renameTo(destination)) { "Unable to finalize downloaded model file." }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 256 * 1024
        const val MAX_IMPORT_BYTES = 4L * 1024L * 1024L * 1024L
        const val MODEL_SIZE_BYTES = 2_580_000_000L
        const val STORAGE_RESERVE_BYTES = 512L * 1024L * 1024L
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)")
    }
}
