package com.shev.agent.tools

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTimeToolTest {
    @Test
    fun returnsActualClockValueAndDeviceTimezoneInRussian() {
        val clock = Clock.fixed(
            Instant.parse("2026-01-01T12:34:56Z"),
            ZoneId.of("Europe/Warsaw")
        )

        val result = LocalTimeTool(clock).readCurrentTime()

        assertTrue(result.contains("1 января 2026"))
        assertTrue(result.contains("13:34:56"))
        assertTrue(result.contains("Europe/Warsaw"))
    }
}
