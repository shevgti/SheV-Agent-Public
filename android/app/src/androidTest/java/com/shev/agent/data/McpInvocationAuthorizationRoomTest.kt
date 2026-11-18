package com.shev.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.McpInvocationAuthorization
import com.shev.agent.core.McpInvocationBinding
import com.shev.agent.core.PlanStep
import com.shev.agent.core.ToolRisk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class McpInvocationAuthorizationRoomTest {
    private lateinit var database: SheVAgentDatabase
    private lateinit var dao: AgentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SheVAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentDao()
        runBlocking {
            dao.insertTask(
                AgentTaskEntity(
                    id = TASK_ID,
                    title = "Проверка MCP-подтверждения",
                    phase = "WAITING_APPROVAL",
                    requiresApproval = true,
                    createdAtEpochMillis = NOW,
                    updatedAtEpochMillis = NOW,
                    revision = INITIAL_TASK_REVISION
                )
            )
            val persisted = RoomAgentPlanRepository(database).saveValidatedPlan(
                AgentPlan(
                    taskId = TASK_ID,
                    revision = PLAN_REVISION,
                    summary = "План для проверки точного подтверждения",
                    steps = listOf(
                        PlanStep(
                            id = STEP_ID,
                            toolId = "memory.search",
                            argumentsJson = """{"query":"test"}""",
                            risk = ToolRisk.READ_ONLY,
                            purpose = "Проверить привязку подтверждения",
                            expectedOutcome = "Подтверждение связано с текущим шагом"
                        )
                    )
                ),
                NOW + 1
            )
            check(persisted == AgentPlanSaveResult.STORED)
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactBindingIsConsumedOnceAndCannotBeReplayed() = runBlocking {
        val binding = McpInvocationBinding(
            profileId = "test-server",
            endpoint = "https://example.org/mcp",
            nativeToolId = "test-server.search_docs",
            toolName = "search_docs",
            toolDefinitionSha256 = "d".repeat(64),
            argumentsSha256 = "a".repeat(64)
        )
        val authorization = authorization(binding)
        insertAuthorization(authorization)

        val verifier = RoomMcpInvocationAuthorizationVerifier(database)
        val changedArguments = binding.copy(argumentsSha256 = "b".repeat(64))

        assertFalse(verifier.consume(authorization, changedArguments, NOW + 1))
        assertTrue(verifier.consume(authorization, binding, NOW + 1))
        assertFalse(verifier.consume(authorization, binding, NOW + 2))
    }

    @Test
    fun taskAndStepRevisionsArePartOfThePersistedBinding() = runBlocking {
        val binding = binding()
        val authorization = authorization(binding)
        insertAuthorization(authorization)

        val staleTask = authorization.copy(taskRevision = authorization.taskRevision + 1)
        val stalePlan = authorization.copy(planRevision = authorization.planRevision + 1)
        val staleStep = authorization.copy(stepRevision = authorization.stepRevision + 1)
        val verifier = RoomMcpInvocationAuthorizationVerifier(database)

        assertFalse(verifier.consume(staleTask, binding, NOW + 1))
        assertFalse(verifier.consume(stalePlan, binding, NOW + 1))
        assertFalse(verifier.consume(staleStep, binding, NOW + 1))
        assertTrue(verifier.consume(authorization, binding, NOW + 1))
    }

    @Test
    fun expiredAuthorizationIsRejectedBeforeDatabaseConsumption() = runBlocking {
        val binding = binding()
        val authorization = authorization(binding)
        insertAuthorization(authorization)

        val verifier = RoomMcpInvocationAuthorizationVerifier(database)
        assertFalse(verifier.consume(authorization, binding, authorization.expiresAtEpochMillis))
    }

    private fun binding() = McpInvocationBinding(
        profileId = "test-server",
        endpoint = "https://example.org/mcp",
        nativeToolId = "test-server.search_docs",
        toolName = "search_docs",
        toolDefinitionSha256 = "d".repeat(64),
        argumentsSha256 = "c".repeat(64)
    )

    private fun authorization(binding: McpInvocationBinding) = McpInvocationAuthorization(
        invocationId = "invocation-000001",
        taskId = TASK_ID,
        taskRevision = TASK_REVISION,
        planRevision = PLAN_REVISION,
        stepId = STEP_ID,
        stepRevision = 0L,
        binding = binding,
        issuedAtEpochMillis = NOW,
        expiresAtEpochMillis = NOW + 60_000,
        nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also {
            SecureRandom().nextBytes(it)
        })
    )

    private suspend fun insertAuthorization(authorization: McpInvocationAuthorization) {
        dao.insertMcpInvocationAuthorizationEvidence(authorization.toEntity())
        dao.insertMcpInvocationApprovalRequest(
            McpInvocationApprovalRequestEntity(
                invocationId = authorization.invocationId,
                taskId = authorization.taskId,
                taskRevision = authorization.taskRevision,
                planRevision = authorization.planRevision,
                stepId = authorization.stepId,
                stepRevision = authorization.stepRevision,
                profileId = authorization.binding.profileId,
                endpoint = authorization.binding.endpoint,
                nativeToolId = authorization.binding.nativeToolId,
                remoteToolName = authorization.binding.toolName,
                toolDefinitionSha256 = authorization.binding.toolDefinitionSha256,
                argumentsSha256 = authorization.binding.argumentsSha256,
                toolTitle = authorization.binding.toolName,
                toolDescription = "test",
                risk = com.shev.agent.core.ToolRisk.EXTERNAL_SIDE_EFFECT.name,
                toolDefinitionCiphertext = LocalPlanCipher.encrypt("definition"),
                argumentsCiphertext = LocalPlanCipher.encrypt("{}"),
                createdAtEpochMillis = authorization.issuedAtEpochMillis,
                expiresAtEpochMillis = authorization.expiresAtEpochMillis,
                status = McpInvocationApprovalRequestEntity.STATUS_APPROVED,
                decidedAtEpochMillis = authorization.issuedAtEpochMillis,
                issuedAtEpochMillis = authorization.issuedAtEpochMillis,
                authorizationNonceCiphertext = LocalPlanCipher.encrypt(authorization.nonce)
            )
        )
    }

    private fun McpInvocationAuthorization.toEntity() = McpInvocationAuthorizationEntity(
        nonceDigest = MessageDigest.getInstance("SHA-256")
            .digest(nonce.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
        invocationId = invocationId,
        taskId = taskId,
        taskRevision = taskRevision,
        planRevision = planRevision,
        stepId = stepId,
        stepRevision = stepRevision,
        profileId = binding.profileId,
        endpoint = binding.endpoint,
        nativeToolId = binding.nativeToolId,
        remoteToolName = binding.toolName,
        toolDefinitionSha256 = binding.toolDefinitionSha256,
        argumentsSha256 = binding.argumentsSha256,
        issuedAtEpochMillis = issuedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis
    )

    private companion object {
        const val TASK_ID = "task-1"
        const val STEP_ID = "step-1"
        const val INITIAL_TASK_REVISION = 4L
        const val TASK_REVISION = 5L
        const val PLAN_REVISION = 0L
        const val NOW = 10_000L
    }
}
