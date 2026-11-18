package com.shev.agent.data

import com.shev.agent.core.McpInvocationApprovalPrompt
import com.shev.agent.core.McpInvocationAuthorization
import com.shev.agent.core.McpInvocationAuthorizationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Persists an exact-action request, waits for the user-facing inbox decision,
 * then reconstructs the short-lived authorization from encrypted persisted data.
 * A process restart can resume the same invocationId and recover an already
 * approved request; no in-memory callback is the source of truth.
 */
class RoomMcpInvocationAuthorizationProvider(
    private val database: SheVAgentDatabase,
    private val repository: RoomMcpInvocationApprovalRepository = RoomMcpInvocationApprovalRepository(database)
) : McpInvocationAuthorizationProvider {
    override suspend fun issue(prompt: McpInvocationApprovalPrompt): McpInvocationAuthorization? {
        val draft = McpInvocationApprovalDraft(
            binding = prompt.binding,
            context = prompt.context,
            toolTitle = prompt.toolTitle,
            toolDescription = prompt.toolDescription,
            risk = prompt.risk,
            toolDefinitionJson = prompt.toolDefinitionJson,
            argumentsJson = prompt.argumentsJson,
            createdAtEpochMillis = prompt.createdAtEpochMillis,
            expiresAtEpochMillis = prompt.expiresAtEpochMillis
        )
        when (repository.createPending(draft)) {
            McpInvocationApprovalResult.CREATED,
            McpInvocationApprovalResult.ALREADY_EXISTS -> Unit
            else -> return null
        }

        val dao = database.agentDao()
        val initial = dao.getMcpInvocationApprovalRequest(prompt.context.invocationId) ?: return null
        val now = System.currentTimeMillis()
        dao.expireMcpInvocationApprovalRequests(now)
        dao.expireApprovedMcpInvocationApprovalRequests(now)
        val remainingMillis = (initial.expiresAtEpochMillis - now).coerceAtLeast(1L)
        val changed = withTimeoutOrNull(remainingMillis) {
            dao.observeMcpInvocationApprovalRequest(prompt.context.invocationId).first {
                it != null && it.status != McpInvocationApprovalRequestEntity.STATUS_PENDING
            }
        } ?: dao.getMcpInvocationApprovalRequest(prompt.context.invocationId)

        val approved = changed ?: return null
        if (approved.status != McpInvocationApprovalRequestEntity.STATUS_APPROVED) return null
        val encryptedNonce = approved.authorizationNonceCiphertext ?: return null
        val nonce = runCatching { LocalPlanCipher.decrypt(encryptedNonce) }.getOrNull() ?: return null
        return runCatching {
            McpInvocationAuthorization(
                invocationId = approved.invocationId,
                taskId = approved.taskId,
                taskRevision = approved.taskRevision,
                planRevision = approved.planRevision,
                stepId = approved.stepId,
                stepRevision = approved.stepRevision,
                binding = prompt.binding,
                issuedAtEpochMillis = approved.issuedAtEpochMillis
                    ?: error("Approved MCP request has no issue timestamp"),
                expiresAtEpochMillis = approved.expiresAtEpochMillis,
                nonce = nonce
            )
        }.getOrNull()
    }
}
