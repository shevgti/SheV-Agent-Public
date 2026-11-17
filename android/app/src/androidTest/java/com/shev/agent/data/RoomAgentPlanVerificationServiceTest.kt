package com.shev.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shev.agent.AgentPhase
import com.shev.agent.AgentWorkflowCommand
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.PlanStep
import com.shev.agent.core.ToolRisk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentPlanVerificationServiceTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var dao: AgentDao
    private lateinit var taskRepository: RoomAgentTaskRepository
    private lateinit var planRepository: RoomAgentPlanRepository
    private lateinit var executionRepository: RoomAgentPlanExecutionRepository
    private lateinit var verificationService: RoomAgentPlanVerificationService

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentDao()
        taskRepository = RoomAgentTaskRepository(database, RoomAgentTaskAuthorizationVerifier(dao))
        planRepository = RoomAgentPlanRepository(database)
        executionRepository = RoomAgentPlanExecutionRepository(database, planRepository)
        verificationService = RoomAgentPlanVerificationService(database, planRepository)
        dao.insertTask(
            AgentTaskEntity(
                id = TASK_ID,
                title = "Проверить доказательства выполнения",
                phase = AgentPhase.PLANNING.name,
                requiresApproval = true,
                createdAtEpochMillis = NOW,
                updatedAtEpochMillis = NOW,
                revision = 0L
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun readOnlyExecutionProducesOneUseEvidenceAndCompletesTask() = runBlocking {
        prepareAndExecute(ToolRisk.READ_ONLY)
        val verifyingTask = beginVerification()
        val outcome = verificationService.verifyAndIssueCompletionEvidence(
            TASK_ID, verifyingTask.revision, NOW + 8
        )

        assertEquals(PlanVerificationStatus.VERIFIED, outcome.status)
        val token = outcome.completionEvidenceToken
        assertNotNull(token)
        assertEquals(1, outcome.acceptedEvidenceRefs.size)

        val completed = taskRepository.transition(
            AgentTaskTransitionRequest(
                taskId = TASK_ID,
                command = AgentWorkflowCommand.COMPLETE,
                expectedRevision = verifyingTask.revision,
                eventId = "complete-event",
                idempotencyKey = "complete-task",
                occurredAtEpochMillis = NOW + 9,
                evidenceRef = token
            )
        )
        assertTrue(completed is AgentTaskRepositoryResult.Applied)
        assertEquals(AgentPhase.COMPLETED.name, dao.getTask(TASK_ID)!!.phase)

        val digest = ApprovalEvidenceToken.digest(token!!)
        val evidenceRow = dao.getApprovalEvidence(digest)
        assertNotNull(evidenceRow)
        assertEquals("COMPLETE", evidenceRow!!.operation)
        assertEquals(NOW + 9, evidenceRow.consumedAtEpochMillis)
        val completionEvent = dao.findEventByIdempotencyKey("complete-task")!!
        assertEquals(digest, completionEvent.evidenceRef)
        assertFalse(completionEvent.evidenceRef!!.contains(token))
    }

    @Test
    fun reversibleWriteCannotCompleteWithoutFileOrTestReceipt() = runBlocking {
        prepareAndExecute(ToolRisk.REVERSIBLE_WRITE)
        val verifyingTask = beginVerification()
        val outcome = verificationService.verifyAndIssueCompletionEvidence(
            TASK_ID, verifyingTask.revision, NOW + 8
        )

        assertEquals(PlanVerificationStatus.INSUFFICIENT_EVIDENCE, outcome.status)
        assertNull(outcome.completionEvidenceToken)
        assertEquals(AgentPhase.VERIFYING.name, dao.getTask(TASK_ID)!!.phase)
        assertEquals(0, dao.countApprovalEvidence())
    }

    private suspend fun prepareAndExecute(risk: ToolRisk) {
        assertEquals(
            AgentPlanSaveResult.STORED,
            planRepository.saveValidatedPlan(
                AgentPlan(
                    taskId = TASK_ID,
                    revision = 0L,
                    summary = "Выполнить один проверяемый шаг",
                    steps = listOf(
                        PlanStep(
                            id = "inspect",
                            toolId = "local.inspect",
                            argumentsJson = "{}",
                            risk = risk,
                            purpose = "Проверить состояние",
                            expectedOutcome = "Получен результат инструмента"
                        )
                    )
                ),
                NOW + 1
            )
        )
        val task = dao.getTask(TASK_ID)!!
        dao.saveTask(task.copy(phase = AgentPhase.APPROVED.name))
        assertEquals(StartPlanExecutionResult.STARTED, executionRepository.startPlan(TASK_ID, 1L, NOW + 2))
        val claim = executionRepository.claimStep(TASK_ID, "inspect", false, NOW + 3).claim!!
        assertTrue(
            executionRepository.markDispatched(
                TASK_ID, "inspect", claim.execution.invocationId,
                claim.execution.taskRevision, claim.execution.planRevision,
                claim.execution.stepRevision, NOW + 4
            )
        )
        assertTrue(
            executionRepository.completeStep(
                TASK_ID, "inspect", claim.execution.invocationId,
                claim.execution.taskRevision, claim.execution.planRevision,
                claim.execution.stepRevision, "tool-result", NOW + 5
            )
        )
    }

    private suspend fun beginVerification(): AgentTaskEntity {
        val running = dao.getTask(TASK_ID)!!
        assertEquals(AgentPhase.RUNNING.name, running.phase)
        val transition = taskRepository.transition(
            AgentTaskTransitionRequest(
                taskId = TASK_ID,
                command = AgentWorkflowCommand.BEGIN_VERIFICATION,
                expectedRevision = running.revision,
                eventId = "verify-event",
                idempotencyKey = "verify-task",
                occurredAtEpochMillis = NOW + 6
            )
        )
        assertTrue(transition is AgentTaskRepositoryResult.Applied)
        return dao.getTask(TASK_ID)!!
    }

    private companion object {
        const val TASK_ID = "verification-service-test"
        const val NOW = 200_000L
    }
}
