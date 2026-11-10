package com.shev.agent.model

import java.util.Locale

/** Deterministic routing for device facts that must never be guessed by an LLM. */
object LocalTimeIntentRouter {
    private val patterns = listOf(
        Regex("\\bкотор(?:ый|ое)\\s+час\\b"),
        Regex("\\bсколько(?:\\s+сейчас)?\\s+времени\\b"),
        Regex("\\bтекущ(?:ее|ую)\\s+время\\b"),
        Regex("\\bвремя\\s+сейчас\\b"),
        Regex("\\bкакое\\s+сегодня\\s+число\\b"),
        Regex("\\bкакая\\s+сегодня\\s+дата\\b"),
        Regex("\\bсегодняшн(?:яя|юю)\\s+дат(?:а|у)\\b"),
        Regex("\\bкакой\\s+сегодня\\s+день\\b")
    )

    fun isCurrentTimeRequest(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[!?.,;:]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized.isNotEmpty() && patterns.any { it.containsMatchIn(normalized) }
    }
}
