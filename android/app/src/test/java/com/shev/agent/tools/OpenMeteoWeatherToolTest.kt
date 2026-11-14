package com.shev.agent.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoWeatherToolTest {
    @Test
    fun rejectsBlankCityWithoutNetworkRequest() = runBlocking {
        val failure = runCatching { OpenMeteoWeatherTool().currentWeather("   ") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("город", ignoreCase = true) == true)
    }

    @Test
    fun rejectsOverlongCityWithoutNetworkRequest() = runBlocking {
        val failure = runCatching { OpenMeteoWeatherTool().currentWeather("М".repeat(101)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
