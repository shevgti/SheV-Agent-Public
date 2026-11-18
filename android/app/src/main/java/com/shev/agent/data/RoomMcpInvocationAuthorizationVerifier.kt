package com.shev.agent.data

import androidx.room.withTransaction
import com.shev.agent.core.McpInvocationAuthorization
import com.shev.agent.core.McpInvocationAuthorizationVerifier
import com.shev.agent.core.McpInvocationBinding

/**
 * Consumes one exact MCP approval and closes its review request in the same
 * Room transaction. The nonce digest is the only unencrypted authorization
 * material retained after consumption.
 */
class RoomMcpInvocationAuthorizationVerifier(
    private val database: SheVAgentDatabase
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
        return database.withTransaction {
            val dao = database.agentDao()
            val consumed = dao.consumeMcpInvocationAuthorization(
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
                toolDefinitionSha256 = binding.toolDefinitionSha256,
                argumentsSha256 = binding.argumentsSha256,
                issuedAtEpochMillis = authorization.issuedAtEpochMillis,
                expiresAtEpochMillis = authorization.expiresAtEpochMillis,
                nowMillis = nowMillis
            )
            if (consumed != 1) return@withTransaction false
            check(
                dao.markMcpInvocationApprovalRequestConsumed(
                    invocationId = authorization.invocationId,
                    nowMillis = nowMillis
                ) == 1
            ) { "MCP authorization was consumed without its approval request." }
            true
        }
    }
}
