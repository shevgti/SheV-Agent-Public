package com.shev.chat

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shev.chat.data.ChatHistoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatShareImportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { ChatHistoryDatabase.get(context) }

    @Before
    fun clearProjectHistory() = runBlocking {
        listOf("ФотоПорядок", "SheV Agent", "Моя память").forEach {
            database.entries().deleteProjectHistory(it)
        }
    }

    @Test
    fun coldStartImportsSharedChatGptTextIntoSelectedProjectAsIncomingMessage() = runBlocking {
        context.getSharedPreferences("shev_chat_projects", android.content.Context.MODE_PRIVATE)
            .edit().putString("selected_project", "SheV Agent").commit()

        val activity = instrumentation.startActivitySync(shareIntent("Проверь направление передачи сообщений"))
        assertTrue(activity is ChatActivity)

        val entries = withTimeout(10_000) {
            database.entries().observeRecent("SheV Agent").first { list ->
                list.any { it.content == "Проверь направление передачи сообщений" }
            }
        }
        val imported = entries.single { it.content == "Проверь направление передачи сообщений" }

        assertEquals("ASSISTANT_SHARED", imported.role)
        assertEquals(0, database.entries().countForProject("ФотоПорядок"))
    }

    @Test
    fun recreatedActivityDoesNotImportTheSameShareAgain() = runBlocking {
        context.getSharedPreferences("shev_chat_projects", android.content.Context.MODE_PRIVATE)
            .edit().putString("selected_project", "ФотоПорядок").commit()

        val activity = instrumentation.startActivitySync(shareIntent("Одно входящее сообщение")) as ChatActivity
        withTimeout(10_000) {
            database.entries().observeRecent("ФотоПорядок").first { it.size == 1 }
        }

        instrumentation.runOnMainSync { activity.recreate() }
        instrumentation.waitForIdleSync()

        assertEquals(1, database.entries().countForProject("ФотоПорядок"))
    }

    @Test
    fun blankSharedTextDoesNotCreateHistoryEntry() = runBlocking {
        instrumentation.startActivitySync(shareIntent("   "))
        instrumentation.waitForIdleSync()

        assertEquals(0, database.entries().countForProject("ФотоПорядок"))
    }

    private fun shareIntent(text: String) = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        .setClassName(context, ChatActivity::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
