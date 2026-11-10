package com.shev.agent.model

import kotlinx.coroutines.CancellationException

object ModelFallbackRouter {
    fun candidates(configured: String): List<String> = configured
        .split(Regex("[,;\\n]"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(8)

    suspend fun <T> firstSuccessful(
        modelIds: List<String>,
        request: suspend (String) -> T
    ): Pair<String, T> {
        require(modelIds.isNotEmpty()) { "Добавь хотя бы одну модель для маршрута." }
        val failures = mutableListOf<String>()
        for (modelId in modelIds.distinct().take(8)) {
            try {
                return modelId to request(modelId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += "$modelId: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Все настроенные модели недоступны. " + failures.joinToString(" · "))
    }
}
