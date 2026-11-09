package com.shev.agent.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Runtime-only credentials: never persist this object or include it in logs. */
data class OpenAiCompatibleConfig(
    val baseUrl: String,
    val modelId: String,
    val apiToken: String
) {
    fun validatedBaseUrl(): String {
        require(baseUrl.length <= MAX_BASE_URL_LENGTH) { "Адрес сервера слишком длинный." }
        val uri = runCatching { URI(baseUrl.trim()) }
            .getOrElse { throw IllegalArgumentException("Некорректный адрес сервера.") }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Для удалённого подключения требуется HTTPS."
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Адрес должен содержать только HTTPS-хост и путь без логина, параметров и фрагмента."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Некорректный порт сервера." }
        val path = uri.path.trimEnd('/')
        require(path.isEmpty() || path == "/v1") { "Путь сервера должен быть пустым или оканчиваться на /v1." }
        require(modelId.isNotBlank() && modelId.length <= MAX_MODEL_ID_LENGTH) { "Некорректный идентификатор модели." }
        require(apiToken.isNotBlank() && apiToken.length <= MAX_TOKEN_LENGTH) { "Не задан API-токен LM Studio." }
        return "${uri.scheme}://${uri.rawAuthority}/v1"
    }

    private companion object {
        const val MAX_BASE_URL_LENGTH = 512
        const val MAX_MODEL_ID_LENGTH = 256
        const val MAX_TOKEN_LENGTH = 4096
    }
}

data class RemoteModelDescriptor(val id: String)

class OpenAiCompatibleClient(
    private val config: OpenAiCompatibleConfig,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 120_000,
    private val maxResponseBytes: Int = 1_048_576
) {
    private val baseUrl = config.validatedBaseUrl()

    init {
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1..180_000)
        require(maxResponseBytes in 1_024..4_194_304)
    }

    suspend fun listModels(): List<RemoteModelDescriptor> = withContext(Dispatchers.IO) {
        val json = request("GET", "$baseUrl/models")
        val data = json.getAsJsonArray("data") ?: throw IllegalStateException("Сервер вернул неверный список моделей.")
        data.mapNotNull { item ->
            item.takeIf { it.isJsonObject }?.asJsonObject?.get("id")
                ?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let(::RemoteModelDescriptor)
        }
    }

    suspend fun complete(userText: String): String = withContext(Dispatchers.IO) {
        require(userText.isNotBlank() && userText.length <= MAX_INPUT_CHARS) {
            "Сообщение пустое или слишком длинное."
        }
        val body = JsonObject().apply {
            addProperty("model", config.modelId)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userText)
                })
            })
            addProperty("stream", false)
            addProperty("temperature", 0.2)
            addProperty("max_tokens", 2048)
        }
        val json = request("POST", "$baseUrl/chat/completions", body.toString())
        json.getAsJsonArray("choices")
            ?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")
            ?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Модель вернула пустой или неподдерживаемый ответ.")
    }

    private suspend fun request(method: String, endpoint: String, body: String? = null) : com.google.gson.JsonObject {
        coroutineContext.ensureActive()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiToken}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            coroutineContext.ensureActive()
            val status = connection.responseCode
            if (status !in 200..299) {
                // Do not include response body or headers: they may contain private server details.
                throw IllegalStateException(
                    when (status) {
                        401, 403 -> "LM Studio отклонил токен доступа (HTTP $status)."
                        404 -> "Эндпоинт LM Studio не найден (HTTP 404). Проверь адрес /v1."
                        429 -> "LM Studio временно ограничил запросы (HTTP 429)."
                        in 500..599 -> "Ошибка сервера LM Studio (HTTP $status)."
                        in 300..399 -> "Перенаправления сервера запрещены для защищённого запроса."
                        else -> "Запрос к LM Studio отклонён (HTTP $status)."
                    }
                )
            }
            val input = connection.inputStream
            val bytes = input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxResponseBytes) {
                        throw IllegalStateException("Ответ LM Studio превышает допустимый размер.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_INPUT_CHARS = 32_000
    }
}
