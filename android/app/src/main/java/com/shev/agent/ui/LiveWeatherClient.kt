package com.shev.agent.ui

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object LiveWeatherClient {
    suspend fun current(city: String): String = withContext(Dispatchers.IO) {
        val normalized = city.trim()
        require(normalized.isNotEmpty()) { "Укажи город в команде, например «погода в Амстердаме»." }
        val geocodeUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(normalized, "UTF-8")}&count=1&language=ru&format=json"
        val geo = request(geocodeUrl)
        val location = JsonParser.parseString(geo).asJsonObject.getAsJsonArray("results")
            ?.firstOrNull()?.asJsonObject ?: throw IllegalArgumentException("Город не найден: $normalized")
        val lat = location.get("latitude").asDouble
        val lon = location.get("longitude").asDouble
        val name = location.get("name")?.asString ?: normalized
        val country = location.get("country")?.asString.orEmpty()
        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&timezone=auto"
        val weather = JsonParser.parseString(request(weatherUrl)).asJsonObject.getAsJsonObject("current")
        val temp = weather.get("temperature_2m").asInt
        val feels = weather.get("apparent_temperature").asInt
        val wind = weather.get("wind_speed_10m").asInt
        val code = weather.get("weather_code").asInt
        val description = describe(code)
        val humidity = weather.get("relative_humidity_2m").asInt
        "$name${if (country.isBlank()) "" else ", $country"} · $temp° · $description · ощущается $feels° · ветер $wind км/ч · влажность $humidity%"
    }

    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Погодный сервис вернул HTTP $status")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(code: Int): String = when (code) {
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
        95, 96, 99 -> "гроза"
        else -> "погодные условия"
    }
}
