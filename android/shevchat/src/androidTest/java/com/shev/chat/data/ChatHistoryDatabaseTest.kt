package com.shev.chat.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryDatabaseTest {
    private lateinit var database: ChatHistoryDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            ChatHistoryDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun entriesAreIsolatedByProjectAndNewestFirst() = runBlocking {
        val dao = database.entries()
        dao.insert(entry("p1-old", "photos", 10L))
        dao.insert(entry("p2", "memory", 20L))
        dao.insert(entry("p1-new", "photos", 30L))

        val photos = dao.observeRecent("photos").first()
        assertEquals(listOf("p1-new", "p1-old"), photos.map { it.id })
        assertEquals(1, dao.countForProject("memory"))
    }

    @Test
    fun clearingOneProjectPreservesOtherProjects() = runBlocking {
        val dao = database.entries()
        dao.insert(entry("p1", "photos", 10L))
        dao.insert(entry("p2", "memory", 20L))

        dao.deleteProjectHistory("photos")

        assertTrue(dao.observeRecent("photos").first().isEmpty())
        assertEquals(listOf("p2"), dao.observeRecent("memory").first().map { it.id })
    }

    @Test
    fun insertAndPruneKeepsOnlyTheNewestHundredForThatProject() = runBlocking {
        val dao = database.entries()
        repeat(105) { index ->
            dao.insertAndPrune(entry("note-$index", "photos", index.toLong()))
        }
        dao.insert(entry("other-project-note", "memory", 500L))

        val photos = dao.observeRecent("photos").first()
        assertEquals(100, photos.size)
        assertEquals("note-104", photos.first().id)
        assertEquals("note-5", photos.last().id)
        assertEquals(100, dao.countForProject("photos"))
        assertEquals(1, dao.countForProject("memory"))
    }

    private fun entry(id: String, project: String, time: Long) = ChatEntryEntity(
        id = id,
        projectId = project,
        role = "USER",
        content = "message-$id",
        createdAtEpochMillis = time
    )
}
