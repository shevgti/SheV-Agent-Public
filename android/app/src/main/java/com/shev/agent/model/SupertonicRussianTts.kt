package com.shev.agent.model

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional Russian neural TTS pack. Runtime is sherpa-onnx (Apache-2.0);
 * model weights are separately licensed by Supertone under OpenRAIL-M.
 * Model files stay in app-private storage and are downloaded only on user request.
 */
class SupertonicRussianTts(private val context: Context) {
    companion object {
        const val SPEAKER_COUNT = 10
        const val MODEL_ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
        const val MODEL_FOLDER = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        private val REQUIRED_FILES = setOf(
            "duration_predictor.int8.onnx",
            "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx",
            "vocoder.int8.onnx",
            "tts.json",
            "unicode_indexer.bin",
            "voice.bin"
        )
    }

    private val modelDir = File(context.filesDir, "voice-models/$MODEL_FOLDER")
    private val installMarker = File(modelDir, ".complete")
    private val mutex = Mutex()
    private var tts: OfflineTts? = null
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var playbackCancelled: Boolean = false

    fun isInstalled(): Boolean =
        installMarker.isFile && REQUIRED_FILES.all { File(modelDir, it).isFile && File(modelDir, it).length() > 0L }

    suspend fun downloadModel(onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        withContext(Dispatchers.IO) {
            if (isInstalled()) return@withContext
            val parent = modelDir.parentFile ?: error("Не удалось определить папку голосовой модели.")
            check(parent.mkdirs() || parent.isDirectory) { "Не удалось создать папку для голосовой модели." }
            val archive = File(context.cacheDir, "supertonic-ru-model.tar.bz2.part")
            val archiveReady = File(context.cacheDir, "supertonic-ru-model.tar.bz2")
            val staging = File(parent, "$MODEL_FOLDER.installing")
            archive.delete()
            archiveReady.delete()
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Не удалось подготовить установку голосовой модели." }
            try {
                val connection = (URL(MODEL_ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                try {
                    connection.connect()
                    check(connection.responseCode in 200..299) { "Сервер голосовой модели ответил: HTTP ${connection.responseCode}" }
                    val total = connection.contentLengthLong.coerceAtLeast(-1L)
                    var received = 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(archive).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                received += count
                                onProgress(received, total)
                            }
                            output.fd.sync()
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                check(archive.length() > 1024L * 1024L) { "Загруженный архив голосов повреждён или слишком мал." }
                check(archive.renameTo(archiveReady)) { "Не удалось подготовить архив голосов." }

                BZip2CompressorInputStream(BufferedInputStream(archiveReady.inputStream())).use { bzip ->
                    TarArchiveInputStream(bzip).use { tar ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tar.nextTarEntry ?: break
                            if (entry.isDirectory) continue
                            val name = entry.name.substringAfterLast('/')
                            if (name !in REQUIRED_FILES) continue
                            val outputFile = File(staging, name)
                            FileOutputStream(outputFile).use { output ->
                                tar.copyTo(output, 128 * 1024)
                                output.fd.sync()
                            }
                        }
                    }
                }
                check(REQUIRED_FILES.all { File(staging, it).isFile && File(staging, it).length() > 0L }) {
                    "В архиве отсутствуют необходимые файлы модели Supertonic."
                }
                modelDir.deleteRecursively()
                staging.renameTo(modelDir).also { renamed ->
                    check(renamed) { "Не удалось установить голосовую модель." }
                }
                installMarker.writeText("sherpa-onnx-supertonic-3-ru\n")
            } catch (failure: Throwable) {
                staging.deleteRecursively()
                modelDir.deleteRecursively()
                throw failure
            } finally {
                archive.delete()
                archiveReady.delete()
            }
        }
    }

    suspend fun speak(text: String, speakerId: Int, emotionTag: String? = null, speed: Float = 1.0f) {
        require(speakerId in 0 until SPEAKER_COUNT) { "Неизвестный диктор." }
        require(text.isNotBlank() && text.length <= 4000) { "Текст для озвучивания пустой или слишком длинный." }
        require(emotionTag == null || emotionTag in SupertonicVoiceCatalog.expressionTags) { "Неизвестная эмоция для Supertonic." }
        mutex.withLock {
            withContext(Dispatchers.Default) {
                playbackCancelled = false
                check(isInstalled()) { "Сначала загрузи пакет русских нейроголосов." }
                val engine = tts ?: createEngine().also { tts = it }
                val expressiveText = if (emotionTag == null) text else "$emotionTag $text"
                val audio = engine.generateWithConfig(
                    expressiveText,
                    GenerationConfig(
                        sid = speakerId,
                        numSteps = 8,
                        speed = speed.coerceIn(0.85f, 1.15f),
                        extra = mapOf("lang" to "ru")
                    )
                )
                if (!playbackCancelled) playAudio(audio.samples, audio.sampleRate)
            }
        }
    }

    private fun createEngine(): OfflineTts {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(modelDir, "duration_predictor.int8.onnx").absolutePath,
                    textEncoder = File(modelDir, "text_encoder.int8.onnx").absolutePath,
                    vectorEstimator = File(modelDir, "vector_estimator.int8.onnx").absolutePath,
                    vocoder = File(modelDir, "vocoder.int8.onnx").absolutePath,
                    ttsJson = File(modelDir, "tts.json").absolutePath,
                    unicodeIndexer = File(modelDir, "unicode_indexer.bin").absolutePath,
                    voiceStyle = File(modelDir, "voice.bin").absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        return OfflineTts(config = config).also {
            check(it.numSpeakers() == SPEAKER_COUNT) { "Модель вернула неожиданное число дикторов: ${it.numSpeakers()}." }
        }
    }

    private suspend fun playAudio(samples: FloatArray, sampleRate: Int) {
        check(samples.isNotEmpty()) { "Модель не сгенерировала аудио." }
        val pcm = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        activeTrack = track
        try {
            if (playbackCancelled) return
            var offset = 0
            while (offset < pcm.size) {
                currentCoroutineContext().ensureActive()
                val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (playbackCancelled) return
                check(written > 0) { "Не удалось подготовить аудиопоток." }
                offset += written
            }
            if (playbackCancelled) return
            track.play()
            while (!playbackCancelled && track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < pcm.size) {
                currentCoroutineContext().ensureActive()
                Thread.sleep(20)
            }
        } finally {
            if (activeTrack === track) activeTrack = null
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Stop current neural audio immediately when the user starts speaking. */
    fun stopPlayback() {
        playbackCancelled = true
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
        }
    }

    fun release() {
        stopPlayback()
        runCatching { tts?.release() }
        tts = null
    }
}
