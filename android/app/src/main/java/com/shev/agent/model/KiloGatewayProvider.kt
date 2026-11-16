package com.shev.agent.model

import com.google.adk.kt.models.openai.ChatCompletions
import com.google.adk.kt.types.HttpOptions
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

data class KiloFreeToolModel(
    val id: String,
    val contextLength: Int? = null
)

/** Anonymous Kilo access is restricted to live models verified as free and tool-capable. */
object KiloGatewayProvider {
    const val BASE_URL = "https://ai-gateway.kilo.ai/api/v1"
    const val MODELS_URL = "$BASE_URL/models"
    const val RATE_LIMIT_NOTE = "Бесплатно · без ключа · до 200 запросов/ч на IP"

    internal fun parseFreeToolModels(body: String): List<KiloFreeToolModel> {
        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("Kilo вернул некорректный каталог моделей.")
        val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalArgumentException("В каталоге Kilo отсутствует список моделей.")

        return data.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val item = element.asJsonObject
            val id = item.get("id")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf { it.matches(Regex("[A-Za-z0-9._:/+-]{1,200}")) }
                ?: return@mapNotNull null
            val pricing = item.get("pricing")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: return@mapNotNull null
            val promptPrice = pricing.get("prompt")
                ?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber) }
                ?.asString?.toBigDecimalOrNull()
                ?: return@mapNotNull null
            val completionPrice = pricing.get("completion")
                ?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber) }
                ?.asString?.toBigDecimalOrNull()
                ?: return@mapNotNull null
            if (promptPrice.compareTo(BigDecimal.ZERO) != 0 ||
                completionPrice.compareTo(BigDecimal.ZERO) != 0) return@mapNotNull null

            val parameters = item.get("supported_parameters")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?: return@mapNotNull null
            val supportsTools = parameters.any { parameter ->
                parameter.isJsonPrimitive && parameter.asJsonPrimitive.isString &&
                    parameter.asString == "tools"
            }
            if (!supportsTools) return@mapNotNull null

            val contextLength = runCatching {
                item.get("context_length")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asInt
                    ?.takeIf { it > 0 }
            }.getOrNull()
            KiloFreeToolModel(id = id, contextLength = contextLength)
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    suspend fun listFreeToolModels(): List<KiloFreeToolModel> = withContext(Dispatchers.IO) {
        val connection = (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                throw IOException("Каталог Kilo недоступен (HTTP $status). ${detail.take(240)}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseFreeToolModels(body)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun requireCurrentFreeToolModel(modelId: String): KiloFreeToolModel {
        require(modelId.matches(Regex("[A-Za-z0-9._:/+-]{1,200}"))) {
            "Некорректный идентификатор модели Kilo."
        }
        return listFreeToolModels().firstOrNull { it.id == modelId }
            ?: throw IllegalStateException(
                "Модель $modelId больше не подтверждена как бесплатная модель с поддержкой инструментов. Платная замена не выбиралась."
            )
    }

    fun createModel(model: KiloFreeToolModel): ChatCompletions =
        ChatCompletions(name = model.id, httpOptions = HttpOptions(baseUrl = BASE_URL))
}
