package com.shev.agent.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class OpenAiFunctionTool(
    val name: String,
    val description: String,
    val parameters: JsonObject
) {
    init {
        require(name.matches(Regex("[a-zA-Z0-9_]{1,64}")))
        require(description.isNotBlank() && description.length <= 1024)
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        addProperty("name", name)
        addProperty("description", description)
        add("parameters", parameters)
        addProperty("strict", true)
    }
}

/**
 * Stateless Responses API tool loop for SheV's client-owned local tools.
 * The app, not the model, validates and executes every proposed function call.
 */
class OpenAiResponsesClient(
    private val config: OpenAiCompatibleConfig,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 120_000,
    private val maxResponseBytes: Int = 1_048_576,
    private val maxToolRounds: Int = 4
) {
    private val baseUrl = config.validatedBaseUrl().trimEnd('/')

    init {
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1..180_000)
        require(maxResponseBytes in 1_024..4_194_304)
        require(maxToolRounds in 1..8)
    }

    suspend fun completeWithTools(
        history: JsonArray,
        instructions: String,
        tools: List<OpenAiFunctionTool>,
        executeTool: suspend (name: String, argumentsJson: String) -> String
    ): String = withContext(Dispatchers.IO) {
        require(instructions.isNotBlank() && instructions.length <= 8_000)
        require(tools.size <= 16)
        val input = history.deepCopy()
        val toolArray = JsonArray().apply { tools.forEach { add(it.toJson()) } }

        repeat(maxToolRounds + 1) { round ->
            coroutineContext.ensureActive()
            val body = JsonObject().apply {
                addProperty("model", config.modelId)
                addProperty("instructions", instructions)
                add("input", input)
                add("tools", toolArray)
                addProperty("tool_choice", "auto")
                addProperty("parallel_tool_calls", false)
                addProperty("max_output_tokens", 2048)
                addProperty("store", false)
            }
            val response = request(body)
            val output = response.getAsJsonArray("output")
                ?: throw IllegalStateException("OpenAI Responses API вернул ответ без output.")
            val functionCalls = output.filter { item ->
                item.isJsonObject && item.asJsonObject.get("type")?.asString == "function_call"
            }.map { it.asJsonObject }

            if (functionCalls.isEmpty()) {
                return@withContext extractAssistantText(response, output)
            }
            if (round >= maxToolRounds) {
                throw IllegalStateException("SheV остановила цепочку инструментов: достигнут безопасный лимит вызовов.")
            }

            // Stateless continuation must replay every response output item, including
            // reasoning items and function_call items, before appending function outputs.
            output.forEach { input.add(it.deepCopy()) }
            for (call in functionCalls) {
                coroutineContext.ensureActive()
                val callId = call.get("call_id")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: throw IllegalStateException("OpenAI вернул вызов функции без call_id.")
                val name = call.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: throw IllegalStateException("OpenAI вернул вызов функции без имени.")
                val arguments = call.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: "{}"
                val toolResult = try {
                    executeTool(name, arguments).take(8_000)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    "Инструмент не выполнен. SheV не изменила данные."
                }
                input.add(JsonObject().apply {
                    addProperty("type", "function_call_output")
                    addProperty("call_id", callId)
                    addProperty("output", toolResult)
                })
            }
        }
        throw IllegalStateException("OpenAI Responses API не вернул итоговый ответ.")
    }

    private fun extractAssistantText(response: JsonObject, output: JsonArray): String {
        response.get("output_text")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }?.let { return it }
        val parts = output.filter { it.isJsonObject }
            .map { it.asJsonObject }
            .filter { it.get("type")?.asString == "message" && it.get("role")?.asString == "assistant" }
            .flatMap { message ->
                message.getAsJsonArray("content")?.mapNotNull { part ->
                    part.takeIf { it.isJsonObject }?.asJsonObject
                        ?.takeIf { it.get("type")?.asString == "output_text" }
                        ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                }.orEmpty()
            }
        return parts.joinToString("").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenAI Responses API вернул пустой текстовый ответ.")
    }

    private suspend fun request(body: JsonObject): JsonObject {
        coroutineContext.ensureActive()
        val connection = (URL("$baseUrl/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${config.apiToken}")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            coroutineContext.ensureActive()
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException(
                    when (status) {
                        401, 403 -> "OpenAI отклонил API-ключ (HTTP $status)."
                        404 -> "OpenAI Responses API не найден (HTTP 404). Проверь модель и адрес /v1."
                        429 -> "OpenAI временно ограничил запросы (HTTP 429)."
                        in 500..599 -> "Ошибка сервера OpenAI (HTTP $status)."
                        in 300..399 -> "Перенаправления для защищённого запроса запрещены."
                        else -> "Запрос к OpenAI отклонён (HTTP $status)."
                    }
                )
            }
            val bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxResponseBytes) {
                        throw IllegalStateException("Ответ OpenAI превышает допустимый размер.")
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
}
