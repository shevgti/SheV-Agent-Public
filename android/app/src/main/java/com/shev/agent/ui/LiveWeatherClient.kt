package com.shev.agent.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * City names are resolved to coordinates, but all meteorological values come from Yandex Weather.
 * The API key is supplied by the user and is kept only in private app preferences.
 */
object LiveWeatherClient {
    @Volatile
    var latestDisplay: String? = null
        private set

    fun publishLatestDisplay(value: String?) {
        latestDisplay = value
    }

    suspend fun current(city: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val normalized = city.trim()
        require(normalized.isNotEmpty()) { "Укажи город, например «погода в Амстердаме»." }
        require(apiKey.isNotBlank()) {
            "Для Яндекс.Погоды нужен API-ключ. Открой Настройки → Яндекс.Погода и добавь ключ."
        }
        val candidates = buildList {
            add(normalized)
            if (normalized.endsWith("е")) {
                add(normalized.removeSuffix("е"))
                if (normalized.endsWith("ве")) add(normalized.removeSuffix("ве") + "ва")
                if (normalized.endsWith("ге")) add(normalized.removeSuffix("ге") + "га")
            }
            if (normalized.endsWith("у")) add(normalized.removeSuffix("у"))
        }.distinct()
        var location: JsonObject? = null
        for (candidate in candidates) {
            val geocodeUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(candidate, "UTF-8")}&count=1&language=ru&format=json"
            val geo = request(geocodeUrl)
            location = JsonParser.parseString(geo).asJsonObject.getAsJsonArray("results")
                ?.firstOrNull()?.asJsonObject
            if (location != null) break
        }
        // Open-Meteo may occasionally omit Russian-localized settlement names.
        // Keep a verified coordinate fallback for Mytishchi so weather lookup can continue.
        if (location == null && normalized.equals("Мытищи", ignoreCase = true) ||
            location == null && normalized.equals("г. Мытищи", ignoreCase = true) ||
            location == null && normalized.equals("Mytishchi", ignoreCase = true)
        ) {
            location = JsonObject().apply {
                addProperty("latitude", 55.910503)
                addProperty("longitude", 37.73633)
                addProperty("name", "Мытищи")
                addProperty("country", "Россия")
            }
        }
        val resolvedLocation = location ?: throw IllegalArgumentException("Город не найден: $normalized")
        val lat = resolvedLocation.get("latitude").asDouble
        val lon = resolvedLocation.get("longitude").asDouble
        val name = resolvedLocation.get("name")?.asString ?: normalized
        val country = resolvedLocation.get("country")?.asString.orEmpty()

        val weatherUrl = "https://api.weather.yandex.ru/v2/forecast?lat=$lat&lon=$lon&lang=ru_RU&limit=1&hours=false"
        val root = JsonParser.parseString(request(weatherUrl, apiKey)).asJsonObject
        val fact = root.getAsJsonObject("fact")
            ?: throw IllegalStateException("Яндекс.Погода не вернула текущие погодные данные.")
        val temp = fact.get("temp")?.asInt ?: throw IllegalStateException("В ответе Яндекс.Погоды нет температуры.")
        val feels = fact.get("feels_like")?.asInt
        val wind = fact.get("wind_speed")?.asDouble
        val humidity = fact.get("humidity")?.asInt
        val condition = describe(fact.get("condition")?.asString.orEmpty())
        buildString {
            append("$name${if (country.isBlank()) "" else ", $country"} · $temp° · $condition")
            feels?.let { append(" · ощущается $it°") }
            wind?.let { append(" · ветер ${"%.1f".format(java.util.Locale.US, it)} м/с") }
            humidity?.let { append(" · влажность $it%") }
            append(" · данные Яндекс.Погоды")
        }
    }

    private fun request(url: String, apiKey: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            apiKey?.let { connection.setRequestProperty("X-Yandex-Weather-Key", it.trim()) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = runCatching {
                    (connection.errorStream ?: connection.inputStream).bufferedReader().use { it.readText() }
                }.getOrNull().orEmpty()
                val message = when (status) {
                    403 -> "Яндекс.Погода отклонила ключ (HTTP 403). Проверь ключ и подключённый тариф API."
                    404 -> "Яндекс.Погода не нашла координаты этого города."
                    429 -> "Превышен лимит запросов Яндекс.Погоды (HTTP 429)."
                    else -> "Яндекс.Погода вернула HTTP $status."
                }
                throw IllegalStateException(if (detail.isBlank()) message else "$message $detail".take(400))
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(condition: String): String = when (condition.lowercase()) {
        "clear" -> "ясно"
        "partly-cloudy" -> "малооблачно"
        "cloudy" -> "облачно с прояснениями"
        "overcast" -> "пасмурно"
        "drizzle" -> "морось"
        "light-rain" -> "небольшой дождь"
        "rain" -> "дождь"
        "moderate-rain" -> "умеренный дождь"
        "heavy-rain" -> "сильный дождь"
        "continuous-heavy-rain" -> "затяжной сильный дождь"
        "showers" -> "ливень"
        "wet-snow" -> "мокрый снег"
        "light-snow" -> "небольшой снег"
        "snow" -> "снег"
        "snow-showers" -> "снегопад"
        "hail" -> "град"
        "thunderstorm" -> "гроза"
        "thunderstorm-with-rain" -> "гроза с дождём"
        "thunderstorm-with-hail" -> "гроза с градом"
        else -> if (condition.isBlank()) "условия не указаны" else condition
    }
}
