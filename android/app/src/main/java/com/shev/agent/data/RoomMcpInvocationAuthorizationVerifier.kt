package com.shev.agent.data

import com.shev.agent.core.McpInvocationAuthorization
import com.shev.agent.core.McpInvocationAuthorizationVerifier
import com.shev.agent.core.McpInvocationBinding

/**
 * Consumes one exact MCP approval with a single conditional Room UPDATE.
 *
 * This verifier is intentionally not a token issuer. A trusted approval UI/kernel
 * flow must persist the evidence row after the user approves the rendered action.
 * Until that issuer and UI are connected, the MCP bridge remains fail-closed.
 */
class RoomMcpInvocationAuthorizationVerifier(
    private val dao: AgentDao
) : McpInvocationAuthorizationVerifier {
    override suspend fun consume(
        authorization: McpInvocationAuthorization,
        expectedBinding: McpInvocationBinding,
        nowMillis: Long
    ): Boolean {
        if (authorization.binding != expectedBinding) return false
        if (nowMillis < authorization.issuedAtEpochMillis ||
            nowMillis >= authorization.expiresAtEpochMillis
        ) return false

        val nonceDigest = runCatching {
            ApprovalEvidenceToken.digest(authorization.nonce)
        }.getOrNull() ?: return false

        val binding = authorization.binding
        return dao.consumeMcpInvocationAuthorization(
            nonceDigest = nonceDigest,
            invocationId = authorization.invocationId,
            taskId = authorization.taskId,
            taskRevision = authorization.taskRevision,
            planRevision = authorization.planRevision,
            stepId = authorization.stepId,
            stepRevision = authorization.stepRevision,
            profileId = binding.profileId,
            endpoint = binding.endpoint,
            nativeToolId = binding.nativeToolId,
            remoteToolName = binding.toolName,
            argumentsSha256 = binding.argumentsSha256,
            issuedAtEpochMillis = authorization.issuedAtEpochMillis,
            expiresAtEpochMillis = authorization.expiresAtEpochMillis,
            nowMillis = nowMillis
        ) == 1
    }
}
