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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentPlanExecutionRepositoryTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var dao: AgentDao
    private lateinit var repository: RoomAgentPlanExecutionRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentDao()
        repository = RoomAgentPlanExecutionRepository(database)
        dao.insertTask(
            AgentTaskEntity(
                id = TASK_ID,
                title = "Тест исполнения плана",
                phase = "PLANNING",
                requiresApproval = true,
                createdAtEpochMillis = NOW,
                updatedAtEpochMillis = NOW,
                revision = 0L
            )
        )
        assertEquals(
            AgentPlanSaveResult.STORED,
            RoomAgentPlanRepository(database).saveValidatedPlan(
                AgentPlan(
                    taskId = TASK_ID,
                    revision = 0L,
                    summary = "Два зависимых шага",
                    steps = listOf(
                        PlanStep(
                            id = "step_one",
                            toolId = "test.read",
                            argumentsJson = "{}",
                            risk = ToolRisk.READ_ONLY,
                            purpose = "Первый шаг",
                            expectedOutcome = "Первый результат"
                        ),
                        PlanStep(
                            id = "step_two",
                            toolId = "test.write",
                            argumentsJson = "{}",
                            dependsOn = setOf("step_one"),
                            risk = ToolRisk.REVERSIBLE_WRITE,
                            purpose = "Второй шаг",
                            expectedOutcome = "Второй результат"
                        )
                    )
                ),
                NOW + 1
            )
        )
        val task = dao.getTask(TASK_ID)!!
        dao.saveTask(task.copy(phase = "APPROVED"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun startPlanCreatesStableStepInvocationsAndIsIdempotent() = runBlocking {
        assertEquals(StartPlanExecutionResult.STARTED, repository.startPlan(TASK_ID, 1L, NOW + 2))
        assertEquals(StartPlanExecutionResult.ALREADY_STARTED, repository.startPlan(TASK_ID, 1L, NOW + 3))
        val task = dao.getTask(TASK_ID)!!
        assertEquals("RUNNING", task.phase)
        assertEquals(2L, task.revision)
        val rows = dao.getAgentPlanStepExecutions(TASK_ID)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.status == AgentPlanStepExecutionEntity.PENDING })
        assertTrue(rows.all { it.taskRevision == 2L })
        assertTrue(rows.map { it.invocationId }.distinct().size == 2)
    }

    @Test
    fun dependenciesGateClaimsAndRestartNeverRetriesDispatchedStep() = runBlocking {
        assertEquals(StartPlanExecutionResult.STARTED, repository.startPlan(TASK_ID, 1L, NOW + 2))

        assertEquals(
            ClaimPlanStepStatus.DEPENDENCIES_NOT_MET,
            repository.claimStep(TASK_ID, "step_two", false, NOW + 3).status
        )

        val first = repository.claimStep(TASK_ID, "step_one", true, NOW + 4)
        assertEquals(ClaimPlanStepStatus.CLAIMED, first.status)
        val firstClaim = first.claim!!
        assertEquals(AgentPlanStepExecutionEntity.WAITING_APPROVAL, firstClaim.execution.status)
        assertTrue(firstClaim.awaitingApproval)

        assertTrue(
            repository.markDispatched(
                TASK_ID, "step_one", firstClaim.execution.invocationId,
                firstClaim.execution.taskRevision, firstClaim.execution.planRevision,
                firstClaim.execution.stepRevision, NOW + 5
            )
        )
        assertTrue(
            repository.completeStep(
                TASK_ID, "step_one", firstClaim.execution.invocationId,
                firstClaim.execution.taskRevision, firstClaim.execution.planRevision,
                firstClaim.execution.stepRevision, "first-output", NOW + 6
            )
        )
        assertEquals("first-output", repository.readStepOutput(TASK_ID, "step_one"))

        val second = repository.claimStep(TASK_ID, "step_two", false, NOW + 7)
        assertEquals(ClaimPlanStepStatus.CLAIMED, second.status)
        val secondClaim = second.claim!!
        assertEquals(AgentPlanStepExecutionEntity.DISPATCHING, secondClaim.execution.status)
        assertTrue(
            repository.markDispatched(
                TASK_ID, "step_two", secondClaim.execution.invocationId,
                secondClaim.execution.taskRevision, secondClaim.execution.planRevision,
                secondClaim.execution.stepRevision, NOW + 8
            )
        )

        assertEquals(1, repository.recoverAfterProcessRestart(TASK_ID, NOW + 9))
        val recovered = dao.getAgentPlanStepExecution(TASK_ID, "step_two")!!
        assertEquals(AgentPlanStepExecutionEntity.OUTCOME_UNKNOWN, recovered.status)
        assertEquals("PROCESS_RESTART_DURING_DISPATCH", recovered.errorCode)
        assertEquals(ClaimPlanStepStatus.ALREADY_CLAIMED, repository.claimStep(TASK_ID, "step_two", false, NOW + 10).status)
        assertFalse(repository.allStepsSucceeded(TASK_ID))
        assertNotNull(recovered.dispatchedAtEpochMillis)
    }

    @Test
    fun dispatchedStepWithUncertainFailureBecomesOutcomeUnknown() = runBlocking {
        assertEquals(StartPlanExecutionResult.STARTED, repository.startPlan(TASK_ID, 1L, NOW + 2))
        val claim = repository.claimStep(TASK_ID, "step_one", false, NOW + 3).claim!!
        assertTrue(
            repository.markDispatched(
                TASK_ID, claim.step.id, claim.execution.invocationId,
                claim.execution.taskRevision, claim.execution.planRevision,
                claim.execution.stepRevision, NOW + 4
            )
        )
        assertTrue(
            repository.failStep(
                TASK_ID, claim.step.id, claim.execution.invocationId,
                claim.execution.taskRevision, claim.execution.planRevision,
                claim.execution.stepRevision, "REMOTE_TIMEOUT",
                mayHaveSideEffect = true,
                nowMillis = NOW + 5
            )
        )
        assertEquals(
            AgentPlanStepExecutionEntity.OUTCOME_UNKNOWN,
            dao.getAgentPlanStepExecution(TASK_ID, "step_one")!!.status
        )
    }

    private companion object {
        const val TASK_ID = "plan-execution-test"
        const val NOW = 50_000L
    }
}
