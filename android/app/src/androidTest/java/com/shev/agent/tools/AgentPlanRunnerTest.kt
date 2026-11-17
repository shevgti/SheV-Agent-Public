package com.shev.agent.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shev.agent.AgentCapability
import com.shev.agent.AgentPhase
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.PlanStep
import com.shev.agent.core.ToolRisk
import com.shev.agent.data.AgentTaskAuthorizationVerifier
import com.shev.agent.data.AgentTaskEntity
import com.shev.agent.data.RoomAgentPlanRepository
import com.shev.agent.data.RoomAgentTaskRepository
import com.shev.agent.data.RoomAgentTaskAuthorizationVerifier
import com.shev.agent.data.SheVAgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentPlanRunnerTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var dao: com.shev.agent.data.AgentDao
    private lateinit var taskRepository: RoomAgentTaskRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentDao()
        taskRepository = RoomAgentTaskRepository(
            database,
            RoomAgentTaskAuthorizationVerifier(dao)
        )
        dao.insertTask(
            AgentTaskEntity(
                id = TASK_ID,
                title = "Проверить запуск плана",
                phase = AgentPhase.PLANNING.name,
                requiresApproval = true,
                createdAtEpochMillis = NOW,
                updatedAtEpochMillis = NOW,
                revision = 0L
            )
        )
        assertEquals(
            com.shev.agent.data.AgentPlanSaveResult.STORED,
            RoomAgentPlanRepository(database).saveValidatedPlan(
                AgentPlan(
                    taskId = TASK_ID,
                    revision = 0L,
                    summary = "Выполнить безопасное локальное чтение",
                    steps = listOf(
                        PlanStep(
                            id = "read_step",
                            toolId = "test.read",
                            argumentsJson = """{"value":"hello"}""",
                            risk = ToolRisk.READ_ONLY,
                            purpose = "Прочитать тестовое значение",
                            expectedOutcome = "Вернуть значение"
                        )
                    )
                ),
                NOW + 1
            )
        )
        val task = dao.getTask(TASK_ID)!!
        dao.saveTask(task.copy(phase = AgentPhase.APPROVED.name))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun runnerStartsApprovedPlanVerifiesItsExecutionReceiptAndCompletesTask() = runBlocking {
        var handlerCalled = false
        val registry = AgentToolRegistry().apply {
            register(
                AgentToolDescriptor(
                    id = "test.read",
                    title = "Тестовое чтение",
                    description = "Возвращает переданное значение",
                    capabilities = setOf(AgentCapability.ANALYZE_DATA),
                    inputSchema = ToolInputSchema(
                        listOf(ToolInputField("value", ToolInputType.STRING))
                    ),
                    planExecutable = true
                )
            )
        }
        val toolExecutor = AgentToolExecutor(
            registry = registry,
            handlers = mapOf(
                "test.read" to AgentTool {
                    handlerCalled = true
                    AgentToolResult(true, "verified:$it")
                }
            )
        )
        val runner = AgentPlanRunner(
            database = database,
            executor = AgentPlanExecutor(database, registry, toolExecutor),
            taskRepository = taskRepository
        )
        val approvedRevision = dao.getTask(TASK_ID)!!.revision

        val result = runner.startApproved(TASK_ID, approvedRevision)

        assertEquals(AgentPlanRunStatus.COMPLETED, result.status)
        assertEquals(1, result.completedSteps)
        assertTrue(handlerCalled)
        assertEquals(AgentPhase.COMPLETED.name, dao.getTask(TASK_ID)?.phase)
    }

    private companion object {
        const val TASK_ID = "plan-runner-test"
        const val NOW = 90_000L
    }
}
