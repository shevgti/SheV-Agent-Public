package com.shev.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentSkillLibraryTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var library: AgentSkillLibrary

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        library = AgentSkillLibrary(database.agentDao()) { 1_700_000_000_000L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun draftIsNotRetrievedUntilUserActivatesIt() = runBlocking {
        library.saveDraft(
            skillId = "skill-github-release",
            title = "Проверка сборки GitHub",
            summary = "Как проверить результат Android-сборки в GitHub Actions",
            instructions = "Открой Actions, выбери последний workflow и проверь итог каждого job.",
            keywords = listOf("GitHub", "Actions", "сборка"),
            sourceUrls = listOf("https://docs.github.com/actions")
        )

        assertTrue(library.retrieveFor("проверь сборку GitHub").isEmpty())
        assertEquals(1, library.observeAll().first().size)

        library.activateAfterUserReview("skill-github-release")
        assertEquals(
            listOf("skill-github-release"),
            library.retrieveFor("проверь сборку GitHub").map { it.skillId }
        )
    }

    @Test
    fun disablingSkillRemovesItFromPromptRetrieval() = runBlocking {
        library.saveDraft(
            skillId = "skill-weather",
            title = "Погода",
            summary = "Получение текущей погоды в городе",
            instructions = "Сначала уточни город, затем используй погодный инструмент.",
            keywords = listOf("погода", "город"),
            sourceUrls = emptyList()
        )
        library.activateAfterUserReview("skill-weather")
        assertTrue(library.buildContext("погода в Берлине").contains("## Погода"))
        assertTrue(library.disable("skill-weather"))
        assertFalse(library.buildContext("погода в Берлине").contains("## Погода"))
    }

    @Test
    fun rejectsPrivateUrlsAndSecretLikeInstructions() = runBlocking {
        try {
            library.saveDraft(
                skillId = "skill-private-url",
                title = "Приватная ссылка",
                summary = "Тест",
                instructions = "Безопасная инструкция",
                keywords = emptyList(),
                sourceUrls = listOf("http://127.0.0.1/admin")
            )
            throw AssertionError("Private URL must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        try {
            library.saveDraft(
                skillId = "skill-secret",
                title = "Секрет",
                summary = "Тест",
                instructions = "api_key = 'abcdefghijklmnop'",
                keywords = emptyList(),
                sourceUrls = emptyList()
            )
            throw AssertionError("Secret-like content must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
