package com.shev.agent.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shev.agent.AgentCapability
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.PlanStep
import com.shev.agent.core.ToolRisk
import com.shev.agent.data.AgentPlanStepExecutionEntity
import com.shev.agent.data.AgentTaskEntity
import com.shev.agent.data.RoomAgentPlanExecutionRepository
import com.shev.agent.data.RoomAgentPlanRepository
import com.shev.agent.data.SheVAgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentPlanExecutorTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var dao: com.shev.agent.data.AgentDao
    private lateinit var executionRepository: RoomAgentPlanExecutionRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentDao()
        executionRepository = RoomAgentPlanExecutionRepository(database)
        dao.insertTask(
            AgentTaskEntity(
                id = TASK_ID,
                title = "Выполнить локальный шаг",
                phase = "PLANNING",
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
                    summary = "Прочитать локальный тестовый ресурс",
                    steps = listOf(
                        PlanStep(
                            id = "read_step",
                            toolId = "test.read",
                            argumentsJson = """{"value":"hello"}""",
                            risk = ToolRisk.READ_ONLY,
                            purpose = "Прочитать значение",
                            expectedOutcome = "Вернуть значение"
                        )
                    )
                ),
                NOW + 1
            )
        )
        val task = dao.getTask(TASK_ID)!!
        dao.saveTask(task.copy(phase = "APPROVED"))
        assertEquals(
            com.shev.agent.data.StartPlanExecutionResult.STARTED,
            executionRepository.startPlan(TASK_ID, 1L, NOW + 2)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun executorPersistsDispatchBeforeCallingHandlerAndCompletesStep() = runBlocking {
        var handlerCalled = false
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                id = "test.read",
                title = "Тестовое чтение",
                description = "Возвращает переданное значение",
                capabilities = setOf(AgentCapability.READ_LOCAL_DATA),
                inputSchema = ToolInputSchema(
                    listOf(ToolInputField("value", ToolInputType.STRING))
                ),
                planExecutable = true
            )
        )
        val toolExecutor = AgentToolExecutor(
            registry = registry,
            handlers = mapOf(
                "test.read" to AgentTool { input ->
                    handlerCalled = true
                    AgentToolResult(true, "done:$input")
                }
            )
        )
        val executor = AgentPlanExecutor(database, registry, toolExecutor, executionRepository)

        val result = executor.executeNext(
            taskId = TASK_ID,
            grantedCapabilities = setOf(AgentCapability.READ_LOCAL_DATA),
            explicitlyApprovedCapabilities = emptySet(),
            nowMillis = NOW + 3
        )

        assertEquals(PlanExecutionAttemptStatus.STEP_COMPLETED, result.status)
        assertTrue(handlerCalled)
        val execution = dao.getAgentPlanStepExecution(TASK_ID, "read_step")!!
        assertEquals(AgentPlanStepExecutionEntity.SUCCEEDED, execution.status)
        assertEquals("done:{\"value\":\"hello\"}", execution.resultCiphertext?.let {
            com.shev.agent.data.LocalPlanCipher.decrypt(it)
        })
        assertEquals(
            PlanExecutionAttemptStatus.PLAN_COMPLETE,
            executor.executeNext(
                taskId = TASK_ID,
                grantedCapabilities = setOf(AgentCapability.READ_LOCAL_DATA),
                explicitlyApprovedCapabilities = emptySet(),
                nowMillis = NOW + 4
            ).status
        )
    }

    private companion object {
        const val TASK_ID = "plan-executor-test"
        const val NOW = 70_000L
    }
}
