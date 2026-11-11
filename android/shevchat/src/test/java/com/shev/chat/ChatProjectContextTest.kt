package com.shev.chat

import com.shev.chat.data.ChatEntryEntity

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProjectContextTest {
    @Test
    fun eachKnownProjectGetsItsOwnContinuationContext() {
        val photo = ChatProjectContext.build("ФотоПорядок")
        val agent = ChatProjectContext.build("SheV Agent")
        val memory = ChatProjectContext.build("Моя память")

        assertTrue(photo.contains("Android-фотогалереей"))
        assertTrue(agent.contains("Android-помощником"))
        assertTrue(memory.contains("календарём и системой напоминаний"))
        assertTrue(photo.contains(ChatProjectContext.MEMORY_CHECKPOINT_URL))
        assertTrue(agent.contains(ChatProjectContext.MEMORY_CHECKPOINT_URL))
        assertTrue(memory.contains(ChatProjectContext.MEMORY_CHECKPOINT_URL))
    }

    @Test
    fun promptDoesNotClaimAutomaticChatControlOrTranscriptAccess() {
        val prompt = ChatProjectContext.build("ФотоПорядок")
        assertTrue(prompt.contains("не читает историю ChatGPT"))
        assertTrue(prompt.contains("не отправляет сообщение автоматически"))
    }

    @Test
    fun recentManualHistoryIsIncludedAndClearlyMarkedAsLocal() {
        val entries = listOf(
            ChatEntryEntity(
                id = "1",
                projectId = "ФотоПорядок",
                role = "USER",
                content = "Проверь новую сборку и не теряй checkpoint.",
                createdAtEpochMillis = 100L
            ),
            ChatEntryEntity(
                id = "2",
                projectId = "ФотоПорядок",
                role = "ASSISTANT",
                content = "Сборка ещё не подтверждена.",
                createdAtEpochMillis = 200L
            )
        )

        val prompt = ChatProjectContext.build("ФотоПорядок", entries)

        assertTrue(prompt.contains("Последние записи локальной истории"))
        assertTrue(prompt.contains("Проверь новую сборку"))
        assertTrue(prompt.contains("Сборка ещё не подтверждена"))
        assertTrue(prompt.contains("не считать внешне синхронизированными"))
    }

    @Test
    fun unknownProjectDoesNotSilentlyBecomeAnotherProject() {
        val prompt = ChatProjectContext.build("Неизвестный проект")
        assertTrue(prompt.contains("Неизвестный проект"))
        assertTrue(prompt.contains("найди его актуальный паспорт"))
    }
}
