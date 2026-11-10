package com.shev.agent.tools

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reads actual device-local time; it does not depend on model knowledge or network access. */
class LocalTimeTool(
    private val clock: Clock = Clock.systemDefaultZone()
) {
    fun readCurrentTime(): String {
        val now = ZonedDateTime.now(clock)
        val formatter = DateTimeFormatter.ofPattern(
            "EEEE, d MMMM yyyy, HH:mm:ss",
            Locale.forLanguageTag("ru-RU")
        )
        return "Сейчас ${now.format(formatter)}. Часовой пояс: ${now.zone.id}."
    }
}
