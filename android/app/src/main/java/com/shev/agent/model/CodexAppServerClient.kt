package com.shev.agent.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * In-process Android host for the official Codex App Server executable packaged
 * in the APK's nativeLibraryDir. The server speaks newline-delimited JSON-RPC
 * over stdio; no Termux, shell setup, API key, or remote host is involved.
 */
internal class CodexAppServerClient(
    context: Context,
    private val onProcessExit: (Int) -> Unit = {}
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val mutableEvents = MutableSharedFlow<JsonObject>(replay = 128, extraBufferCapacity = 256)
    val events: SharedFlow<JsonObject> = mutableEvents.asSharedFlow()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var exitJob: Job? = null
    private val writeLock = Any()

    val isAlive: Boolean get() = process?.isAlive == true

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isAlive) return@withContext
        val binary = java.io.File(appContext.applicationInfo.nativeLibraryDir, CODEX_BINARY_NAME)
        require(binary.exists()) {
            "В APK пока нет встроенного Codex App Server для этой архитектуры Android."
        }
        val codexHome = java.io.File(appContext.noBackupFilesDir, "codex-home").apply { mkdirs() }
        val workspace = java.io.File(appContext.noBackupFilesDir, "codex-workspace").apply { mkdirs() }
        val cache = appContext.cacheDir.apply { mkdirs() }
        val configFile = java.io.File(codexHome, "config.toml")
        if (!configFile.exists()) {
            configFile.writeText(
                "approval_policy = \"on-request\"\n" +
                    "sandbox_mode = \"read-only\"\n" +
                    "model_reasoning_effort = \"medium\"\n",
                StandardCharsets.UTF_8
            )
        }
        val builder = ProcessBuilder(
            binary.absolutePath,
            "--listen", "stdio://",
            "--session-source", "shev_agent"
        ).directory(workspace).redirectErrorStream(false)
        builder.environment().apply {
            put("HOME", codexHome.absolutePath)
            put("CODEX_HOME", codexHome.absolutePath)
            put("XDG_CONFIG_HOME", codexHome.absolutePath)
            put("XDG_CACHE_HOME", cache.absolutePath)
            put("TMPDIR", cache.absolutePath)
            put("PATH", "/system/bin:/system/xbin")
            put("TERM", "dumb")
            put("NO_COLOR", "1")
        }
        val started = try {
            builder.start()
        } catch (error: Exception) {
            throw IllegalStateException(
                "Не удалось запустить встроенный Codex App Server (${error.javaClass.simpleName}: ${error.message}).",
                error
            )
        }
        process = started
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
        stdoutJob = scope.launch { readStdout(started) }
        stderrJob = scope.launch { readStderr(started) }
        exitJob = scope.launch {
            val code = started.waitFor()
            if (process === started) {
                process = null
                failPending("Codex App Server завершился с кодом $code.")
                onProcessExit(code)
            }
        }
        val initialized = request(
            "initialize",
            JsonObject().apply {
                add("clientInfo", JsonObject().apply {
                    addProperty("name", "shev_agent")
                    addProperty("title", "SheV Agent")
                    addProperty("version", "1.0.0")
                })
            }
        )
        require(initialized.has("userAgent")) { "Codex App Server не подтвердил инициализацию." }
        notify("initialized", JsonObject())
    }

    suspend fun accountRead(refreshToken: Boolean = false): JsonObject =
        request("account/read", JsonObject().apply { addProperty("refreshToken", refreshToken) })

    suspend fun beginChatGptLogin(): JsonObject =
        request("account/login/start", JsonObject().apply {
            addProperty("type", "chatgpt")
            addProperty("useHostedLoginSuccessPage", true)
            addProperty("appBrand", "chatgpt")
        })

    suspend fun awaitLogin(loginId: String, timeoutMillis: Long = LOGIN_TIMEOUT_MILLIS): JsonObject =
        withTimeout(timeoutMillis) {
            events.first { event ->
                event.get("method")?.asString == "account/login/completed" &&
                    event.getAsJsonObject("params")?.get("loginId")?.asString?.let { it == loginId } != false
            }.getAsJsonObject("params")
        }

    suspend fun logout(): JsonObject = request("account/logout", JsonObject())

    suspend fun listModels(): JsonObject = request("model/list", JsonObject().apply {
        addProperty("limit", 100)
        addProperty("includeHidden", false)
    })

    suspend fun startThread(model: String): String {
        val workspace = java.io.File(appContext.noBackupFilesDir, "codex-workspace").apply { mkdirs() }
        val result = request(
            "thread/start",
            JsonObject().apply {
                addProperty("model", model)
                addProperty("cwd", workspace.absolutePath)
                addProperty("sandbox", "read-only")
                addProperty("serviceName", "shev_agent")
                addProperty(
                    "developerInstructions",
                    "Ты — SheV, персональный AI-агент пользователя. Всегда отвечай по-русски. " +
                        "Работай в папке рабочего пространства приложения. Не заявляй, что действие выполнено, " +
                        "если оно не завершилось успешно. Перед командами и изменениями файлов запрашивай подтверждение. " +
                        "Не пытайся обходить ограничения Android или получать доступ к данным вне разрешённой папки."
                )
            }
        )
        return result.get("thread")?.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.asString
            ?: throw IllegalStateException("Codex не вернул идентификатор диалога.")
    }

    suspend fun resumeThread(threadId: String): String {
        val result = request("thread/resume", JsonObject().apply {
            addProperty("threadId", threadId)
            addProperty("excludeTurns", true)
        })
        return result.get("thread")?.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.asString
            ?: throw IllegalStateException("Не удалось восстановить диалог Codex.")
    }

    suspend fun startTurn(threadId: String, text: String): JsonObject =
        request("turn/start", JsonObject().apply {
            addProperty("threadId", threadId)
            add("input", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                })
            })
            addProperty("effort", "medium")
            addProperty("summary", "concise")
        })

    suspend fun interrupt(threadId: String, turnId: String): JsonObject =
        request("turn/interrupt", JsonObject().apply {
            addProperty("threadId", threadId)
            addProperty("turnId", turnId)
        })

    suspend fun respondToServerRequest(id: Long, result: JsonObject) {
        writeMessage(JsonObject().apply {
            addProperty("id", id)
            add("result", result)
        })
    }

    fun openAuthUrl(context: Context, url: String) {
        require(url.startsWith("https://chatgpt.com/") || url.startsWith("https://auth.openai.com/")) {
            "Codex вернул неожиданный адрес авторизации."
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun request(method: String, params: JsonObject = JsonObject()): JsonObject {
        check(isAlive) { "Codex App Server не запущен." }
        val id = requestIds.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            writeMessage(JsonObject().apply {
                addProperty("id", id)
                addProperty("method", method)
                add("params", params)
            })
            return withTimeout(REQUEST_TIMEOUT_MILLIS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun readStdout(child: Process) {
        try {
            BufferedReader(InputStreamReader(child.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val message = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                        ?: continue
                    val id = message.get("id")?.takeIf { it.isJsonPrimitive }?.let {
                        runCatching { it.asLong }.getOrNull()
                    }
                    val method = message.get("method")?.asString
                    if (id != null && method == null) {
                        val waiter = pending[id]
                        if (waiter != null) {
                            if (message.has("error")) {
                                val error = message.getAsJsonObject("error")
                                waiter.completeExceptionally(
                                    IllegalStateException(error.get("message")?.asString ?: "Codex RPC error.")
                                )
                            } else {
                                waiter.complete(message.getAsJsonObject("result") ?: JsonObject())
                            }
                        }
                    } else {
                        mutableEvents.emit(message)
                    }
                }
            }
        } catch (error: Exception) {
            if (child === process) failPending("Поток Codex App Server завершился: ${error.message}")
        } finally {
            if (child === process) failPending("Codex App Server закрыл соединение.")
        }
    }

    private suspend fun readStderr(child: Process) {
        runCatching {
            BufferedReader(InputStreamReader(child.errorStream, StandardCharsets.UTF_8)).use { reader ->
                while (reader.readLine() != null) {
                    // Keep stderr out of the conversation transcript; it can contain paths and diagnostics.
                }
            }
        }
    }

    private fun notify(method: String, params: JsonObject) {
        writeMessage(JsonObject().apply {
            addProperty("method", method)
            add("params", params)
        })
    }

    private fun writeMessage(message: JsonObject) {
        synchronized(writeLock) {
            val stream = writer ?: throw IllegalStateException("Codex App Server stdin недоступен.")
            stream.write(message.toString())
            stream.newLine()
            stream.flush()
        }
    }

    private fun failPending(message: String) {
        pending.values.forEach { it.completeExceptionally(IllegalStateException(message)) }
        pending.clear()
    }

    override fun close() {
        runCatching { writer?.close() }
        writer = null
        runCatching { process?.destroy() }
        process = null
        stdoutJob?.cancel()
        stderrJob?.cancel()
        exitJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val CODEX_BINARY_NAME = "libcodex_app_server.so"
        private const val REQUEST_TIMEOUT_MILLIS = 60_000L
        private const val LOGIN_TIMEOUT_MILLIS = 240_000L
    }
}
