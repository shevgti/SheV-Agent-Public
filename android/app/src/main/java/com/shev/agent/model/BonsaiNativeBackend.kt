package com.shev.agent.model

import java.io.Closeable
import java.io.File

/** Owns one loaded native Bonsai model. Calls are serialized across all specialist roles. */
internal class BonsaiNativeBackend private constructor(private var handle: Long) : Closeable {
    @Synchronized
    fun generate(prompt: String, maxTokens: Int = 256): String {
        check(handle != 0L) { "Bonsai backend is closed." }
        require(prompt.isNotBlank())
        return BonsaiJni.nativeGenerate(handle, prompt, maxTokens.coerceIn(1, 2048))
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            BonsaiJni.nativeRelease(handle)
            handle = 0L
        }
    }

    companion object {
        @Volatile private var librariesLoaded = false

        @Synchronized
        fun load(runtimeLibDirectory: File, modelFile: File, contextSize: Int = 2048): BonsaiNativeBackend {
            require(modelFile.isFile) { "Файл Bonsai GGUF не найден." }
            require(contextSize in 512..8192) { "Недопустимый размер контекста." }
            if (!librariesLoaded) {
                listOf("libggml-base.so", "libggml-cpu.so", "libggml.so", "libllama.so", "libbonsai_jni.so").forEach { name ->
                    val library = File(runtimeLibDirectory, name)
                    require(library.isFile) { "Не найдена библиотека $name." }
                    System.load(library.absolutePath)
                }
                librariesLoaded = true
            }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val nativeHandle = BonsaiJni.nativeLoad(modelFile.absolutePath, contextSize, threads)
            check(nativeHandle != 0L) { "llama.cpp не смог загрузить Bonsai GGUF." }
            return BonsaiNativeBackend(nativeHandle)
        }
    }
}

private object BonsaiJni {
    @JvmStatic external fun nativeLoad(modelPath: String, contextSize: Int, threads: Int): Long
    @JvmStatic external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    @JvmStatic external fun nativeRelease(handle: Long)
}
