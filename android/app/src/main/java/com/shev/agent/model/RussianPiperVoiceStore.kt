package com.shev.agent.model

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class RussianPiperVoice(
    val title: String,
    val folder: String,
    val modelFile: String,
    val archiveUrl: String,
    val sha256: String
) {
    RUSLAN("Руслан · мужской", "vits-piper-ru_RU-ruslan-medium", "ru_RU-ruslan-medium.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-ruslan-medium.tar.bz2",
        "0690b1cad01f86e8db9ba988af24898bdc1af774e23cb2e46b9c730269b6fd83"),
    IRINA("Ирина · женский", "vits-piper-ru_RU-irina-medium", "ru_RU-irina-medium.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-irina-medium.tar.bz2",
        "1fc0f54e5e084fe287c07909f2f6e0ba6d857864cf800e3ab80286a4e8233008"),
    DMITRI("Дмитрий · мужской", "vits-piper-ru_RU-dmitri-medium", "ru_RU-dmitri-medium.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-dmitri-medium.tar.bz2",
        "c86d0803737de13d441923ff3b3f309482fab8d7af3ec85949942809eb9a3660")
}

/** Downloads only pinned official model archives and verifies SHA-256 before extraction. */
class RussianPiperVoiceStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "voice-models/piper-ru")

    fun isInstalled(voice: RussianPiperVoice): Boolean {
        val dir = File(root, voice.folder)
        return File(dir, voice.modelFile).isFile &&
            File(dir, "tokens.txt").isFile &&
            File(dir, "espeak-ng-data").isDirectory
    }

    suspend fun install(
        voice: RussianPiperVoice,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        require(voice.archiveUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/")) {
            "Недоверенный адрес голосовой модели."
        }
        check(root.exists() || root.mkdirs()) { "Не удалось создать папку голосовых моделей." }
        val archive = File(root, "${voice.folder}.tar.bz2.part")
        val connection = (URL(voice.archiveUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Сервер голосовой модели ответил HTTP ${connection.responseCode}."
            }
            val total = connection.contentLengthLong
            require(total in 1..MAX_ARCHIVE_BYTES) { "Недопустимый размер архива голосовой модели." }
            connection.inputStream.use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var countTotal = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        countTotal += count
                        require(countTotal <= MAX_ARCHIVE_BYTES) { "Архив голосовой модели слишком большой." }
                        output.write(buffer, 0, count)
                        onProgress(countTotal, total)
                    }
                    output.fd.sync()
                }
            }
            require(sha256(archive) == voice.sha256) { "SHA-256 архива не совпал. Архив удалён." }
            val staging = File(root, "${voice.folder}.installing")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Не удалось подготовить установку модели." }
            try {
                BZip2CompressorInputStream(archive.inputStream().buffered()).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tar.nextTarEntry ?: break
                            val target = File(staging, entry.name).canonicalFile
                            require(target.toPath().startsWith(staging.canonicalFile.toPath())) {
                                "Архив содержит недопустимый путь."
                            }
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else if (entry.isFile) {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { output -> tar.copyTo(output) }
                            }
                        }
                    }
                }
                val model = staging.walkTopDown().firstOrNull { it.isFile && it.name == voice.modelFile }
                    ?: error("В архиве не найдена ONNX-модель.")
                val modelDir = model.parentFile ?: error("Некорректная папка модели.")
                require(File(modelDir, "tokens.txt").isFile && File(modelDir, "espeak-ng-data").isDirectory) {
                    "Архив модели неполный: отсутствуют tokens.txt или espeak-ng-data."
                }
                val finalDir = File(root, voice.folder)
                finalDir.deleteRecursively()
                check(modelDir.renameTo(finalDir)) { "Не удалось завершить установку голосовой модели." }
            } finally {
                staging.deleteRecursively()
            }
            File(root, voice.folder)
        } catch (failure: Throwable) {
            archive.delete()
            throw failure
        } finally {
            connection.disconnect()
            archive.delete()
        }
    }

    suspend fun delete(voice: RussianPiperVoice) = withContext(Dispatchers.IO) {
        File(root, voice.folder).deleteRecursively()
    }

    suspend fun speakSample(context: Context, voice: RussianPiperVoice, text: String) = withContext(Dispatchers.Default) {
        require(isInstalled(voice)) { "Сначала скачай голос." }
        val dir = File(root, voice.folder)
        piperPhonemizeInitialize(context.applicationContext)
        val phonemes = piperPhonemizeText(text, "ru")
            ?: error("Не удалось подготовить русский текст для синтеза.")
        val codepoints = try {
            Array(phonemes.numSentences) { index -> phonemes.getPhonemes(index) }
        } finally {
            phonemes.close()
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, voice.modelFile).absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath
                ),
                numThreads = 2,
                provider = "cpu"
            ),
            maxNumSentences = codepoints.size.coerceAtLeast(1)
        )
        val engine = OfflineTts(config = config)
        try {
            val audio = engine.generateWithConfig(text, GenerationConfig(phonemeCodepoints = codepoints))
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setBufferSizeInBytes(audio.samples.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            try {
                track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                track.play()
                while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    kotlinx.coroutines.delay(100)
                    currentCoroutineContext().ensureActive()
                }
            } finally {
                track.release()
            }
        } finally {
            engine.release()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 120L * 1024L * 1024L
    }
}
