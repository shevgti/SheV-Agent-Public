package com.shev.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.PlanStep
import com.shev.agent.core.ToolRisk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentPlanRepositoryTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var dao: AgentDao
    private lateinit var repository: RoomAgentPlanRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentDao()
        repository = RoomAgentPlanRepository(database)
        runBlocking {
            dao.insertTask(
                AgentTaskEntity(
                    id = TASK_ID,
                    title = "Проверка сохранения плана",
                    phase = "PLANNING",
                    requiresApproval = true,
                    createdAtEpochMillis = NOW,
                    updatedAtEpochMillis = NOW,
                    revision = 0L
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistsAndRestoresCanonicalDagAndStepRevisions() = runBlocking {
        val initial = plan(
            revision = 0L,
            lookupArguments = """{"query":"alpha"}"""
        )
        assertEquals(
            AgentPlanSaveResult.STORED,
            repository.saveValidatedPlan(initial, NOW + 1)
        )

        val restored = repository.loadPlan(TASK_ID)
        assertNotNull(restored)
        assertEquals(initial, restored!!.plan)
        assertEquals(mapOf("lookup" to 0L, "summarize" to 0L), restored.stepRevisions)

        val changed = plan(
            revision = 1L,
            lookupArguments = """{"query":"beta"}"""
        )
        assertEquals(
            AgentPlanSaveResult.STORED,
            repository.saveValidatedPlan(changed, NOW + 2)
        )

        val afterReplan = repository.loadPlan(TASK_ID)
        assertNotNull(afterReplan)
        assertEquals(changed, afterReplan!!.plan)
        assertEquals(mapOf("lookup" to 1L, "summarize" to 0L), afterReplan.stepRevisions)
        assertEquals(1L, dao.getTask(TASK_ID)!!.revision)
    }

    @Test
    fun stalePlanRevisionCannotOverwriteCurrentPlan() = runBlocking {
        val initial = plan(0L, """{"query":"alpha"}""")
        assertEquals(AgentPlanSaveResult.STORED, repository.saveValidatedPlan(initial, NOW + 1))

        val next = plan(1L, """{"query":"beta"}""")
        assertEquals(AgentPlanSaveResult.STORED, repository.saveValidatedPlan(next, NOW + 2))

        val stale = plan(1L, """{"query":"gamma"}""")
        assertEquals(
            AgentPlanSaveResult.STALE_REVISION,
            repository.saveValidatedPlan(stale, NOW + 3)
        )
        assertEquals(next, repository.loadPlan(TASK_ID)!!.plan)
        assertEquals(1L, dao.getTask(TASK_ID)!!.revision)
    }

    @Test
    fun missingTaskCannotReceiveAPlan() = runBlocking {
        val missing = plan(0L, """{"query":"alpha"}""").copy(taskId = "missing-task")
        assertEquals(
            AgentPlanSaveResult.TASK_NOT_FOUND,
            repository.saveValidatedPlan(missing, NOW + 1)
        )
        assertNull(repository.loadPlan("missing-task"))
    }

    private fun plan(revision: Long, lookupArguments: String) = AgentPlan(
        taskId = TASK_ID,
        revision = revision,
        summary = "Найти сведения и подготовить краткое резюме",
        steps = listOf(
            PlanStep(
                id = "lookup",
                toolId = "memory.search",
                argumentsJson = lookupArguments,
                risk = ToolRisk.READ_ONLY,
                purpose = "Найти исходные сведения",
                expectedOutcome = "Найдены подходящие записи"
            ),
            PlanStep(
                id = "summarize",
                toolId = "memory.summarize",
                argumentsJson = """{"style":"brief"}""",
                dependsOn = setOf("lookup"),
                risk = ToolRisk.READ_ONLY,
                purpose = "Сжать найденные сведения",
                expectedOutcome = "Получено краткое резюме"
            )
        )
    )

    private companion object {
        const val TASK_ID = "task-plan-test"
        const val NOW = 50_000L
    }
}
