package com.shev.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.McpInvocationAuthorization
import com.shev.agent.core.McpInvocationBinding
import com.shev.agent.core.McpInvocationExecutionContext
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
class McpInvocationApprovalRequestRoomTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var dao: AgentDao
    private lateinit var repository: RoomMcpInvocationApprovalRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentDao()
        repository = RoomMcpInvocationApprovalRepository(database)
        dao.insertTask(
            AgentTaskEntity(
                id = TASK_ID,
                title = "Проверка точного MCP-действия",
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
                    summary = "План для теста",
                    steps = listOf(
                        PlanStep(
                            id = STEP_ID,
                            toolId = "test-server.search_docs",
                            argumentsJson = ARGUMENTS,
                            risk = ToolRisk.EXTERNAL_SIDE_EFFECT,
                            purpose = "Вызвать удалённый инструмент",
                            expectedOutcome = "Получить ответ сервера"
                        )
                    )
                ),
                NOW + 1
            )
        )
        val task = dao.getTask(TASK_ID)!!
        dao.saveTask(task.copy(phase = "RUNNING"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun approvalPersistsEncryptedReviewAndIssuesOneUseAuthorization() = runBlocking {
        val draft = draft()
        assertEquals(McpInvocationApprovalResult.CREATED, repository.createPending(draft))
        assertEquals(McpInvocationApprovalResult.ALREADY_EXISTS, repository.createPending(draft))

        val request = dao.getMcpInvocationApprovalRequest(INVOCATION_ID)!!
        assertEquals(McpInvocationApprovalRequestEntity.STATUS_PENDING, request.status)
        assertTrue(request.argumentsCiphertext.startsWith("enc:v1:"))
        assertTrue(request.toolDefinitionCiphertext.startsWith("enc:v1:"))
        assertEquals(
            "tool-definition" to ARGUMENTS,
            repository.readSensitiveReviewContent(INVOCATION_ID)
        )

        assertEquals(
            McpInvocationApprovalResult.APPROVED,
            repository.approve(INVOCATION_ID, NOW + 10)
        )
        val approved = dao.getMcpInvocationApprovalRequest(INVOCATION_ID)!!
        assertEquals(McpInvocationApprovalRequestEntity.STATUS_APPROVED, approved.status)
        assertTrue(approved.authorizationNonceCiphertext!!.startsWith("enc:v1:"))
        assertNotNull(approved.issuedAtEpochMillis)

        val nonce = LocalPlanCipher.decrypt(approved.authorizationNonceCiphertext!!)
        val authorization = McpInvocationAuthorization(
            invocationId = INVOCATION_ID,
            taskId = TASK_ID,
            taskRevision = TASK_REVISION,
            planRevision = PLAN_REVISION,
            stepId = STEP_ID,
            stepRevision = 0L,
            binding = binding(),
            issuedAtEpochMillis = approved.issuedAtEpochMillis!!,
            expiresAtEpochMillis = approved.expiresAtEpochMillis,
            nonce = nonce
        )
        val verifier = RoomMcpInvocationAuthorizationVerifier(dao)
        assertTrue(verifier.consume(authorization, binding(), NOW + 11))
        assertFalse(verifier.consume(authorization, binding(), NOW + 12))
    }

    @Test
    fun approvalFailsIfTaskRevisionChangesAfterRequest() = runBlocking {
        assertEquals(McpInvocationApprovalResult.CREATED, repository.createPending(draft()))
        val task = dao.getTask(TASK_ID)!!
        dao.saveTask(task.copy(revision = task.revision + 1L))
        assertEquals(
            McpInvocationApprovalResult.STALE,
            repository.approve(INVOCATION_ID, NOW + 10)
        )
        assertEquals(
            McpInvocationApprovalRequestEntity.STATUS_PENDING,
            dao.getMcpInvocationApprovalRequest(INVOCATION_ID)!!.status
        )
    }

    @Test
    fun rejectedRequestCannotBeApprovedLater() = runBlocking {
        assertEquals(McpInvocationApprovalResult.CREATED, repository.createPending(draft()))
        assertEquals(McpInvocationApprovalResult.REJECTED, repository.reject(INVOCATION_ID, NOW + 10))
        assertEquals(McpInvocationApprovalResult.REJECTED, repository.approve(INVOCATION_ID, NOW + 11))
    }

    @Test
    fun expiredRequestCannotBeApproved() = runBlocking {
        assertEquals(McpInvocationApprovalResult.CREATED, repository.createPending(draft()))
        assertEquals(McpInvocationApprovalResult.EXPIRED, repository.approve(INVOCATION_ID, NOW + 10_000))
    }

    private fun draft() = McpInvocationApprovalDraft(
        binding = binding(),
        context = McpInvocationExecutionContext(
            invocationId = INVOCATION_ID,
            taskId = TASK_ID,
            taskRevision = TASK_REVISION,
            planRevision = PLAN_REVISION,
            stepId = STEP_ID,
            stepRevision = 0L
        ),
        toolTitle = "Поиск документов",
        toolDescription = "Тестовый удалённый поиск",
        risk = ToolRisk.EXTERNAL_SIDE_EFFECT.name,
        toolDefinitionJson = "tool-definition",
        argumentsJson = ARGUMENTS,
        createdAtEpochMillis = NOW + 2,
        expiresAtEpochMillis = NOW + 5_000
    )

    private fun binding() = McpInvocationBinding(
        profileId = "test-server",
        endpoint = "https://example.org/mcp",
        nativeToolId = "test-server.search_docs",
        toolName = "search_docs",
        toolDefinitionSha256 = "a".repeat(64),
        argumentsSha256 = "b".repeat(64)
    )

    private companion object {
        const val TASK_ID = "mcp-task"
        const val STEP_ID = "search-step"
        const val INVOCATION_ID = "mcp-invocation-0001"
        const val PLAN_REVISION = 0L
        const val TASK_REVISION = 1L
        const val NOW = 20_000L
        const val ARGUMENTS = """{"query":"kotlin","limit":5}"""
    }
}
