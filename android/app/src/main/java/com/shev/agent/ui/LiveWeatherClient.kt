package com.shev.agent.ui

import com.shev.agent.tools.OpenMeteoWeatherTool

/**
 * Live weather adapter. Uses the same keyless Open-Meteo provider as the agent's
 * built-in weather tool, so live cards and chat share one weather source.
 */
object LiveWeatherClient {
    private val provider = OpenMeteoWeatherTool()

    @Volatile
    var latestDisplay: String? = null
        private set

    fun publishLatestDisplay(value: String?) {
        latestDisplay = value
    }

    suspend fun current(city: String): String {
        val result = provider.currentWeather(city)
        publishLatestDisplay(result)
        return result
    }
}
