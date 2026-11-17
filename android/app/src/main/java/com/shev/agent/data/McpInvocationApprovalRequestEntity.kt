package com.shev.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable exact-action review request. Sensitive tool definition and arguments,
 * plus the one-use nonce after approval, are encrypted with LocalPlanCipher.
 */
@Entity(
    tableName = "mcp_invocation_approval_requests",
    indices = [
        Index(value = ["status", "expiresAtEpochMillis"]),
        Index(value = ["taskId", "taskRevision", "planRevision", "stepId", "stepRevision"])
    ]
)
data class McpInvocationApprovalRequestEntity(
    @PrimaryKey val invocationId: String,
    val taskId: String,
    val taskRevision: Long,
    val planRevision: Long,
    val stepId: String,
    val stepRevision: Long,
    val profileId: String,
    val endpoint: String,
    val nativeToolId: String,
    val remoteToolName: String,
    val toolDefinitionSha256: String,
    val argumentsSha256: String,
    val toolTitle: String,
    val toolDescription: String,
    val risk: String,
    val toolDefinitionCiphertext: String,
    val argumentsCiphertext: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val status: String = STATUS_PENDING,
    val decidedAtEpochMillis: Long? = null,
    val issuedAtEpochMillis: Long? = null,
    val authorizationNonceCiphertext: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_STALE = "STALE"
        const val STATUS_CONSUMED = "CONSUMED"
    }
}
