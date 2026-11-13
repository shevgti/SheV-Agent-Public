package com.shev.agent.tools

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Read-only public weather lookup via Open-Meteo. No API key and no personal data
 * are sent; only the city name is used for geocoding.
 */
class OpenMeteoWeatherTool(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 10_000
) {
    suspend fun currentWeather(city: String): String = withContext(Dispatchers.IO) {
        val normalizedCity = city.trim()
        require(normalizedCity.isNotEmpty() && normalizedCity.length <= 100) {
            "Укажи название города."
        }
        val encodedCity = URLEncoder.encode(normalizedCity, Charsets.UTF_8.name())
        val geocoding = requestJson(
            "https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=ru&format=json"
        )
        val place = geocoding.getAsJsonArray("results")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return@withContext "Не удалось найти город «$normalizedCity». Уточни название или добавь регион."
        val latitude = place.get("latitude")?.takeIf { it.isJsonPrimitive }?.asDouble
            ?: error("Сервис геокодирования не вернул координаты.")
        val longitude = place.get("longitude")?.takeIf { it.isJsonPrimitive }?.asDouble
            ?: error("Сервис геокодирования не вернул координаты.")
        val name = place.get("name")?.asString ?: normalizedCity
        val region = place.get("admin1")?.takeIf { it.isJsonPrimitive }?.asString
        val country = place.get("country")?.takeIf { it.isJsonPrimitive }?.asString
        val placeLabel = listOfNotNull(name, region, country).distinct().joinToString(", ")
        val forecast = requestJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1"
        )
        val current = forecast.getAsJsonObject("current")
            ?: error("Сервис погоды не вернул текущие условия.")
        val daily = forecast.getAsJsonObject("daily")
        val temperature = current.get("temperature_2m")?.asDouble
            ?: error("Нет данных о температуре.")
        val feelsLike = current.get("apparent_temperature")?.asDouble
        val humidity = current.get("relative_humidity_2m")?.asInt
        val precipitation = current.get("precipitation")?.asDouble
        val wind = current.get("wind_speed_10m")?.asDouble
        val code = current.get("weather_code")?.asInt ?: -1
        val max = daily?.getAsJsonArray("temperature_2m_max")?.firstOrNull()?.asDouble
        val min = daily?.getAsJsonArray("temperature_2m_min")?.firstOrNull()?.asDouble
        val rainChance = daily?.getAsJsonArray("precipitation_probability_max")?.firstOrNull()?.asInt
        buildString {
            append("Погода в ").append(placeLabel).append(": ")
            append(weatherDescription(code)).append(", ")
            append(formatNumber(temperature)).append(" °C")
            if (feelsLike != null) append(", ощущается как ").append(formatNumber(feelsLike)).append(" °C")
            append(".")
            if (min != null && max != null) {
                append(" За день: ").append(formatNumber(min)).append("…")
                    .append(formatNumber(max)).append(" °C.")
            }
            if (rainChance != null) append(" Вероятность осадков: ").append(rainChance).append("%.")
            if (precipitation != null) append(" Осадки сейчас: ").append(formatNumber(precipitation)).append(" мм.")
            if (humidity != null) append(" Влажность: ").append(humidity).append("%.")
            if (wind != null) append(" Ветер: ").append(formatNumber(wind)).append(" км/ч.")
            append(" Данные Open-Meteo.")
        }
    }

    private suspend fun requestJson(endpoint: String) = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SheV-Agent-Android")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Сервис погоды временно недоступен (HTTP $status).")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 256 * 1024) error("Ответ сервиса погоды слишком большой.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun formatNumber(value: Double): String =
        String.format(java.util.Locale.forLanguageTag("ru-RU"), "%.1f", value)

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "преимущественно ясно"
        2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55 -> "морось"
        56, 57 -> "ледяная морось"
        61, 63, 65 -> "дождь"
        66, 67 -> "ледяной дождь"
        71, 73, 75, 77 -> "снег"
        80, 81, 82 -> "ливень"
        85, 86 -> "снегопад"
        95 -> "гроза"
        96, 99 -> "гроза с градом"
        else -> "погодные условия неизвестны"
    }
}
