package com.shev.agent.data

import androidx.room.withTransaction
import com.shev.agent.AgentPhase
import com.shev.agent.core.McpInvocationBinding
import com.shev.agent.core.McpInvocationExecutionContext
import java.util.UUID

data class McpInvocationApprovalDraft(
    val binding: McpInvocationBinding,
    val context: McpInvocationExecutionContext,
    val toolTitle: String,
    val toolDescription: String,
    val risk: String,
    val toolDefinitionJson: String,
    val argumentsJson: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
)

enum class McpInvocationApprovalResult {
    CREATED,
    ALREADY_EXISTS,
    APPROVED,
    REJECTED,
    STALE,
    EXPIRED,
    NOT_FOUND,
    CONFLICT,
    INVALID_REQUEST
}

/**
 * Room is the source of truth for exact remote-action approval.
 *
 * The request is persisted before the UI can approve it. Approval atomically
 * checks the current RUNNING task/plan/step revisions, stores nonce digest
 * evidence, and persists the encrypted nonce needed by the waiting provider.
 */
class RoomMcpInvocationApprovalRepository(
    private val database: SheVAgentDatabase
) {
    suspend fun createPending(draft: McpInvocationApprovalDraft): McpInvocationApprovalResult {
        if (!valid(draft)) return McpInvocationApprovalResult.INVALID_REQUEST
        return database.withTransaction {
            val dao = database.agentDao()
            val existing = dao.getMcpInvocationApprovalRequest(draft.context.invocationId)
            if (existing != null) {
                val sameBinding = existing.taskId == draft.context.taskId &&
                    existing.taskRevision == draft.context.taskRevision &&
                    existing.planRevision == draft.context.planRevision &&
                    existing.stepId == draft.context.stepId &&
                    existing.stepRevision == draft.context.stepRevision &&
                    existing.profileId == draft.binding.profileId &&
                    existing.endpoint == draft.binding.endpoint &&
                    existing.nativeToolId == draft.binding.nativeToolId &&
                    existing.remoteToolName == draft.binding.toolName &&
                    existing.toolDefinitionSha256 == draft.binding.toolDefinitionSha256 &&
                    existing.argumentsSha256 == draft.binding.argumentsSha256
                val sameContent = runCatching {
                    LocalPlanCipher.decrypt(existing.toolDefinitionCiphertext) == draft.toolDefinitionJson &&
                        LocalPlanCipher.decrypt(existing.argumentsCiphertext) == draft.argumentsJson
                }.getOrDefault(false)
                return@withTransaction if (sameBinding && sameContent &&
                    existing.status in setOf(
                        McpInvocationApprovalRequestEntity.STATUS_PENDING,
                        McpInvocationApprovalRequestEntity.STATUS_APPROVED
                    )
                ) McpInvocationApprovalResult.ALREADY_EXISTS else McpInvocationApprovalResult.CONFLICT
            }

            if (!isCurrentRunningStep(
                    draft.context.taskId,
                    draft.context.taskRevision,
                    draft.context.planRevision,
                    draft.context.stepId,
                    draft.context.stepRevision
                )
            ) return@withTransaction McpInvocationApprovalResult.STALE

            dao.expireMcpInvocationApprovalRequests(draft.createdAtEpochMillis)
            dao.insertMcpInvocationApprovalRequest(
                McpInvocationApprovalRequestEntity(
                    invocationId = draft.context.invocationId,
                    taskId = draft.context.taskId,
                    taskRevision = draft.context.taskRevision,
                    planRevision = draft.context.planRevision,
                    stepId = draft.context.stepId,
                    stepRevision = draft.context.stepRevision,
                    profileId = draft.binding.profileId,
                    endpoint = draft.binding.endpoint,
                    nativeToolId = draft.binding.nativeToolId,
                    remoteToolName = draft.binding.toolName,
                    toolDefinitionSha256 = draft.binding.toolDefinitionSha256,
                    argumentsSha256 = draft.binding.argumentsSha256,
                    toolTitle = draft.toolTitle.take(120),
                    toolDescription = draft.toolDescription.take(2_000),
                    risk = draft.risk.take(40),
                    toolDefinitionCiphertext = LocalPlanCipher.encrypt(draft.toolDefinitionJson),
                    argumentsCiphertext = LocalPlanCipher.encrypt(draft.argumentsJson),
                    createdAtEpochMillis = draft.createdAtEpochMillis,
                    expiresAtEpochMillis = draft.expiresAtEpochMillis
                )
            )
            McpInvocationApprovalResult.CREATED
        }
    }

    suspend fun approve(invocationId: String, nowMillis: Long): McpInvocationApprovalResult {
        if (invocationId.isBlank() || nowMillis < 0L) return McpInvocationApprovalResult.INVALID_REQUEST
        return database.withTransaction {
            val dao = database.agentDao()
            val request = dao.getMcpInvocationApprovalRequest(invocationId)
                ?: return@withTransaction McpInvocationApprovalResult.NOT_FOUND
            if (request.status != McpInvocationApprovalRequestEntity.STATUS_PENDING) {
                return@withTransaction when (request.status) {
                    McpInvocationApprovalRequestEntity.STATUS_APPROVED -> McpInvocationApprovalResult.APPROVED
                    McpInvocationApprovalRequestEntity.STATUS_REJECTED -> McpInvocationApprovalResult.REJECTED
                    McpInvocationApprovalRequestEntity.STATUS_EXPIRED -> McpInvocationApprovalResult.EXPIRED
                    else -> McpInvocationApprovalResult.STALE
                }
            }
            if (request.expiresAtEpochMillis <= nowMillis) {
                dao.expireMcpInvocationApprovalRequests(nowMillis)
                return@withTransaction McpInvocationApprovalResult.EXPIRED
            }
            if (!isCurrentRunningStep(
                    request.taskId, request.taskRevision, request.planRevision,
                    request.stepId, request.stepRevision
                )
            ) {
                return@withTransaction McpInvocationApprovalResult.STALE
            }

            val nonce = ApprovalEvidenceToken.create()
            val issuedAt = nowMillis
            val authorizationExpiry = minOf(
                request.expiresAtEpochMillis,
                issuedAt + MAX_AUTHORIZATION_TTL_MILLIS
            )
            val digest = ApprovalEvidenceToken.digest(nonce)
            dao.insertMcpInvocationAuthorizationEvidence(
                McpInvocationAuthorizationEntity(
                    nonceDigest = digest,
                    invocationId = request.invocationId,
                    taskId = request.taskId,
                    taskRevision = request.taskRevision,
                    planRevision = request.planRevision,
                    stepId = request.stepId,
                    stepRevision = request.stepRevision,
                    profileId = request.profileId,
                    endpoint = request.endpoint,
                    nativeToolId = request.nativeToolId,
                    remoteToolName = request.remoteToolName,
                    toolDefinitionSha256 = request.toolDefinitionSha256,
                    argumentsSha256 = request.argumentsSha256,
                    issuedAtEpochMillis = issuedAt,
                    expiresAtEpochMillis = authorizationExpiry
                )
            )
            val updated = dao.approveMcpInvocationApprovalRequest(
                invocationId = request.invocationId,
                taskId = request.taskId,
                taskRevision = request.taskRevision,
                planRevision = request.planRevision,
                stepId = request.stepId,
                stepRevision = request.stepRevision,
                nowMillis = nowMillis,
                issuedAtMillis = issuedAt,
                authorizationExpiresAtMillis = authorizationExpiry,
                nonceCiphertext = LocalPlanCipher.encrypt(nonce)
            )
            check(updated == 1) { "MCP approval became stale during its transaction." }
            McpInvocationApprovalResult.APPROVED
        }
    }

    suspend fun reject(invocationId: String, nowMillis: Long): McpInvocationApprovalResult {
        if (invocationId.isBlank() || nowMillis < 0L) return McpInvocationApprovalResult.INVALID_REQUEST
        return database.withTransaction {
            val dao = database.agentDao()
            val request = dao.getMcpInvocationApprovalRequest(invocationId)
                ?: return@withTransaction McpInvocationApprovalResult.NOT_FOUND
            if (request.status != McpInvocationApprovalRequestEntity.STATUS_PENDING) {
                return@withTransaction when (request.status) {
                    McpInvocationApprovalRequestEntity.STATUS_REJECTED -> McpInvocationApprovalResult.REJECTED
                    McpInvocationApprovalRequestEntity.STATUS_APPROVED -> McpInvocationApprovalResult.APPROVED
                    McpInvocationApprovalRequestEntity.STATUS_EXPIRED -> McpInvocationApprovalResult.EXPIRED
                    else -> McpInvocationApprovalResult.STALE
                }
            }
            if (request.expiresAtEpochMillis <= nowMillis) {
                dao.expireMcpInvocationApprovalRequests(nowMillis)
                return@withTransaction McpInvocationApprovalResult.EXPIRED
            }
            if (!isCurrentRunningStep(
                    request.taskId, request.taskRevision, request.planRevision,
                    request.stepId, request.stepRevision
                )
            ) return@withTransaction McpInvocationApprovalResult.STALE

            val updated = dao.rejectMcpInvocationApprovalRequest(
                invocationId = request.invocationId,
                taskId = request.taskId,
                taskRevision = request.taskRevision,
                planRevision = request.planRevision,
                stepId = request.stepId,
                stepRevision = request.stepRevision,
                nowMillis = nowMillis
            )
            if (updated == 1) McpInvocationApprovalResult.REJECTED else McpInvocationApprovalResult.STALE
        }
    }

    suspend fun readSensitiveReviewContent(invocationId: String): Pair<String, String>? {
        val request = database.agentDao().getMcpInvocationApprovalRequest(invocationId) ?: return null
        if (request.status != McpInvocationApprovalRequestEntity.STATUS_PENDING) return null
        return runCatching {
            LocalPlanCipher.decrypt(request.toolDefinitionCiphertext) to
                LocalPlanCipher.decrypt(request.argumentsCiphertext)
        }.getOrNull()
    }

    private suspend fun isCurrentRunningStep(
        taskId: String,
        taskRevision: Long,
        planRevision: Long,
        stepId: String,
        stepRevision: Long
    ): Boolean {
        val dao = database.agentDao()
        val task = dao.getTask(taskId) ?: return false
        if (task.phase != AgentPhase.RUNNING.name || task.revision != taskRevision) return false
        val plan = dao.getAgentPlan(taskId) ?: return false
        if (plan.planRevision != planRevision) return false
        val step = dao.getAgentPlanSteps(taskId).firstOrNull { it.stepId == stepId } ?: return false
        return step.planRevision == planRevision && step.stepRevision == stepRevision
    }

    private fun valid(draft: McpInvocationApprovalDraft): Boolean {
        val context = draft.context
        val binding = draft.binding
        return context.invocationId.matches(Regex("[A-Za-z0-9_-]{16,80}")) &&
            context.taskId.isNotBlank() && context.taskRevision >= 0L &&
            context.planRevision >= 0L && context.stepId.isNotBlank() && context.stepRevision >= 0L &&
            binding.profileId.isNotBlank() && binding.endpoint.isNotBlank() &&
            binding.nativeToolId.isNotBlank() && binding.toolName.isNotBlank() &&
            draft.toolTitle.isNotBlank() && draft.risk.isNotBlank() &&
            draft.toolDefinitionJson.length in 1..32_000 &&
            draft.argumentsJson.length in 1..32_000 &&
            draft.createdAtEpochMillis > 0L &&
            draft.expiresAtEpochMillis > draft.createdAtEpochMillis &&
            draft.expiresAtEpochMillis - draft.createdAtEpochMillis <= MAX_REQUEST_TTL_MILLIS
    }

    private companion object {
        const val MAX_REQUEST_TTL_MILLIS = 10 * 60 * 1_000L
        const val MAX_AUTHORIZATION_TTL_MILLIS = 120_000L
    }
}
