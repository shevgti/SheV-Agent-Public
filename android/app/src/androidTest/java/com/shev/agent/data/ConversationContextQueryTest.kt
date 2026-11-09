package com.shev.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationContextQueryTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var dao: AgentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun returnsOnlyCompletedUserAndAssistantRowsInStableNewestFirstOrder() = runBlocking {
        val rows = listOf(
            message("u1", "user", "Вопрос 1", "complete", 100L),
            message("a1", "assistant", "Ответ 1", "complete", 200L),
            message("pending", "user", "Черновик", "pending", 300L),
            message("failed", "assistant", "Сбой", "failed", 400L),
            message("system", "system", "Системная строка", "complete", 500L),
            message("u2", "user", "Вопрос 2", "complete", 600L),
            message("a2", "assistant", "Ответ 2", "complete", 700L)
        )
        rows.forEach { dao.appendConversationMessage(it) }

        val newestFirst = dao.getRecentCompletedConversationMessages(10)

        assertEquals(listOf("a2", "u2", "a1", "u1"), newestFirst.map { it.id })
        assertEquals(listOf("Ответ 2", "Вопрос 2", "Ответ 1", "Вопрос 1"), newestFirst.map { it.content })
        assertEquals(listOf("u1", "a1", "u2", "a2"), newestFirst.asReversed().map { it.id })
    }

    @Test
    fun limitAppliesAfterFilteringIncompleteRows() = runBlocking {
        dao.appendConversationMessage(message("old", "user", "Старое завершённое", "complete", 1L))
        (2L..20L).forEach { index ->
            dao.appendConversationMessage(
                message("pending-$index", "user", "Черновик $index", "pending", index)
            )
        }
        dao.appendConversationMessage(message("new", "assistant", "Новое завершённое", "complete", 21L))

        val result = dao.getRecentCompletedConversationMessages(2)

        assertEquals(listOf("new", "old"), result.map { it.id })
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        status: String,
        timestamp: Long
    ) = ConversationMessageEntity(id, role, content, status, timestamp)
}
