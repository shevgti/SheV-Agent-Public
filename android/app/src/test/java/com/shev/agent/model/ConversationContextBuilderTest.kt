package com.shev.agent.model

import com.shev.agent.data.ConversationMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextBuilderTest {

    @Test
    fun includesRecentCompletedTurnsAndCurrentMessage() {
        val history = listOf(
            message("1", "user", "Привет", "complete", 1),
            message("2", "assistant", "Здравствуйте", "complete", 2),
            message("3", "user", "Черновик", "pending", 3)
        )

        val result = ConversationContextBuilder.build(history, "Продолжим")

        assertTrue(result.contains("Пользователь: Привет"))
        assertTrue(result.contains("SheV: Здравствуйте"))
        val heading = "Новое сообщение пользователя:"
        assertTrue(result.contains(heading))
        assertTrue(result.contains("Продолжим"))
        assertTrue(result.indexOf(heading) < result.indexOf("Продолжим"))
        assertFalse(result.contains("Черновик"))
    }

    @Test
    fun returnsCurrentMessageWithoutEmptyHistoryWrapper() {
        assertEquals("Проверка", ConversationContextBuilder.build(emptyList(), "Проверка"))
    }

    @Test
    fun excludesFailedAndUnknownRoleRows() {
        val history = listOf(
            message("1", "user", "Нормальный запрос", "complete", 1),
            message("2", "assistant", "Ошибка модели", "failed", 2),
            message("3", "system", "Неизвестная роль", "complete", 3)
        )

        val result = ConversationContextBuilder.build(history, "Следующий вопрос")

        assertTrue(result.contains("Нормальный запрос"))
        assertFalse(result.contains("Ошибка модели"))
        assertFalse(result.contains("Неизвестная роль"))
    }

    @Test
    fun retainsOnlyTwelveMostRecentEligibleMessages() {
        val history = (1..16).map { index ->
            message(
                id = "m$index",
                role = if (index % 2 == 0) "assistant" else "user",
                content = "Запись-$index",
                status = "complete",
                timestamp = index.toLong()
            )
        }

        val result = ConversationContextBuilder.build(history, "Текущий запрос")

        assertFalse(result.contains("Пользователь: Запись-1\\n"))
        assertFalse(result.contains("SheV: Запись-4\\n"))
        assertTrue(result.contains("Пользователь: Запись-5"))
        assertTrue(result.contains("SheV: Запись-16"))
        assertTrue(result.contains("Текущий запрос"))
    }

    @Test
    fun longHistoryIsBoundedAndCurrentMessageIsNotTruncated() {
        val history = listOf(
            message("long", "user", "ИСТОРИЯ".repeat(2_000), "complete", 1)
        )
        val current = "ВАЖНОЕ НОВОЕ СООБЩЕНИЕ"

        val result = ConversationContextBuilder.build(history, current)

        assertTrue(result.length < 7_000)
        assertTrue(result.endsWith(current))
    }

    @Test
    fun includesRollingSummaryBeforeRecentHistoryAndCurrentMessage() {
        val history = listOf(
            message("1", "user", "Последний вопрос", "complete", 1),
            message("2", "assistant", "Последний ответ", "complete", 2)
        )

        val result = ConversationContextBuilder.build(
            chronologicalHistory = history,
            currentMessage = "Что дальше?",
            rollingSummary = "Решение: продолжаем главный диалог."
        )

        assertTrue(result.contains("Решение: продолжаем главный диалог."))
        assertTrue(result.contains("Пользователь: Последний вопрос"))
        assertTrue(result.contains("SheV: Последний ответ"))
        assertTrue(result.indexOf("Решение:") < result.indexOf("Последний вопрос"))
        assertTrue(result.indexOf("Последний ответ") < result.indexOf("Что дальше?"))
    }

    @Test
    fun blankSummaryDoesNotAddEmptyMemorySection() {
        val result = ConversationContextBuilder.build(
            chronologicalHistory = emptyList(),
            currentMessage = "Новый вопрос",
            rollingSummary = "   "
        )

        assertEquals("Новый вопрос", result)
    }

    @Test
    fun summaryAndHistoryAreBoundedButCurrentMessageIsPreserved() {
        val longSummary = "СВОДКА".repeat(10_000)
        val longHistory = listOf(
            message("1", "user", "ИСТОРИЯ".repeat(10_000), "complete", 1)
        )
        val current = "НЕОБРЕЗАННЫЙ ТЕКУЩИЙ ЗАПРОС"

        val result = ConversationContextBuilder.build(longHistory, current, longSummary)

        assertTrue(result.length < 6_500 + current.length)
        assertTrue(result.endsWith(current))
        assertTrue(result.contains("НЕОБРЕЗАННЫЙ ТЕКУЩИЙ ЗАПРОС"))
    }

    @Test
    fun doesNotRepeatHistoryAlreadyCoveredBySummaryCheckpoint() {
        val history = listOf(
            message("1", "user", "Уже в сводке", "complete", 1),
            message("2", "assistant", "Ответ уже в сводке", "complete", 2),
            message("3", "user", "Новое после сводки", "complete", 3)
        )

        val result = ConversationContextBuilder.build(
            chronologicalHistory = history,
            currentMessage = "Следующий вопрос",
            rollingSummary = "Сохранено предыдущее обсуждение.",
            summaryThroughMessageId = "2"
        )

        assertTrue(result.contains("Сохранено предыдущее обсуждение."))
        assertFalse(result.contains("Уже в сводке"))
        assertFalse(result.contains("Ответ уже в сводке"))
        assertTrue(result.contains("Новое после сводки"))
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        status: String,
        timestamp: Long
    ) = ConversationMessageEntity(id, role, content, status, timestamp)
}
