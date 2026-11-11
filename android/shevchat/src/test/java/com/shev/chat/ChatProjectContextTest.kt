package com.shev.chat

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
    fun unknownProjectDoesNotSilentlyBecomeAnotherProject() {
        val prompt = ChatProjectContext.build("Неизвестный проект")
        assertTrue(prompt.contains("Неизвестный проект"))
        assertTrue(prompt.contains("найди его актуальный паспорт"))
    }
}
