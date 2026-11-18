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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentTaskApprovalTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var taskRepository: RoomAgentTaskRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = RoomAgentTaskRepository(database, AcceptingAuthorizationVerifier)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun taskCannotBeApprovedUntilCanonicalPlanIsPersisted() = runBlocking {
        val draft = taskRepository.createDraft(
            AgentTaskDraftRequest(
                taskId = TASK_ID,
                title = "Подготовить краткий отчёт",
                eventId = "draft-event-1",
                idempotencyKey = "draft-task-1",
                occurredAtEpochMillis = NOW
            )
        )
        assertTrue(draft is AgentTaskDraftResult.Created)

        val missingPlan = taskRepository.approveFromUser(
            taskId = TASK_ID,
            expectedRevision = 1L,
            eventId = "approve-event-missing",
            idempotencyKey = "approve-missing-plan",
            occurredAtEpochMillis = NOW + 1
        )
        assertEquals(
            AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.PLAN_REQUIRED),
            missingPlan
        )

        val planResult = RoomAgentPlanRepository(database).saveValidatedPlan(
            AgentPlan(
                taskId = TASK_ID,
                revision = 0L,
                summary = "Найти материалы и составить отчёт",
                steps = listOf(
                    PlanStep(
                        id = "collect",
                        toolId = "memory.search",
                        argumentsJson = """{"query":"report"}""",
                        risk = ToolRisk.READ_ONLY,
                        purpose = "Собрать материалы",
                        expectedOutcome = "Получен список материалов"
                    )
                )
            ),
            NOW + 2
        )
        assertEquals(AgentPlanSaveResult.STORED, planResult)

        val approved = taskRepository.approveFromUser(
            taskId = TASK_ID,
            expectedRevision = 2L,
            eventId = "approve-event-1",
            idempotencyKey = "approve-plan-1",
            occurredAtEpochMillis = NOW + 3
        )
        assertTrue(approved is AgentTaskRepositoryResult.Applied)
        val current = database.agentDao().getTask(TASK_ID)!!
        assertEquals(AgentPhase.APPROVED.name, current.phase)
        assertEquals(3L, current.revision)

        val started = taskRepository.transition(
            AgentTaskTransitionRequest(
                taskId = TASK_ID,
                command = AgentWorkflowCommand.START_EXECUTION,
                expectedRevision = current.revision,
                eventId = "start-event-1",
                idempotencyKey = "start-task-1",
                occurredAtEpochMillis = NOW + 4
            )
        )
        assertTrue(started is AgentTaskRepositoryResult.Applied)
        val executionContext = RoomAgentPlanRepository(database).createExecutionContext(
            TASK_ID,
            "collect",
            "invocation-000001"
        )
        assertTrue(executionContext != null)
        assertEquals(4L, executionContext!!.taskRevision)
        assertEquals(0L, executionContext.planRevision)
        assertEquals(0L, executionContext.stepRevision)
    }

    private object AcceptingAuthorizationVerifier : AgentTaskAuthorizationVerifier {
        override suspend fun consumeApprovalEvidence(
            taskId: String,
            expectedRevision: Long,
            evidenceRef: String,
            nowMillis: Long
        ) = true

        override suspend fun consumeCompletionEvidence(
            taskId: String,
            expectedRevision: Long,
            evidenceRef: String,
            nowMillis: Long
        ) = true
    }

    private companion object {
        const val TASK_ID = "task-approval-test"
        const val NOW = 100_000L
    }
}
