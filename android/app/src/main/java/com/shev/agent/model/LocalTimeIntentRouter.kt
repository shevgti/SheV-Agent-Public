package com.shev.agent.model

import java.util.Locale

/** Deterministic routing for device facts that must never be guessed by an LLM. */
object LocalTimeIntentRouter {
    private val patterns = listOf(
        Regex("(?<![\\p{L}\\p{N}_])котор(?:ый|ое)\\s+час(?![\\p{L}\\p{N}_])"),
        Regex("(?<![\\p{L}\\p{N}_])сколько(?:\\s+сейчас)?\\s+времени(?![\\p{L}\\p{N}_])"),
        Regex("(?<![\\p{L}\\p{N}_])текущ(?:ее|ую)\\s+время(?![\\p{L}\\p{N}_])"),
        Regex("(?<![\\p{L}\\p{N}_])время\\s+сейчас(?![\\p{L}\\p{N}_])"),
        Regex("(?<![\\p{L}\\p{N}_])какое\\s+сегодня\\s+число(?![\\p{L}\\p{N}_])"),
        Regex("(?<![\\p{L}\\p{N}_])какая\\s+сегодня\\s+дата(?![\\p{L}\\p{N}_])"),
        Regex("(?<![\\p{L}\\p{N}_])сегодняшн(?:яя|юю)\\s+дат(?:а|у)(?![\\p{L}\\p{N}_])"),
        Regex("(?<![\\p{L}\\p{N}_])какой\\s+сегодня\\s+день(?![\\p{L}\\p{N}_])")
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
