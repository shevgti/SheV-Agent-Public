package com.shev.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable evidence for one approved MCP invocation.
 *
 * The raw nonce is never stored. The complete immutable action binding is stored
 * so a token cannot be replayed for another task, step, endpoint, tool or arguments.
 */
@Entity(
    tableName = "mcp_invocation_authorizations",
    indices = [
        Index(value = ["invocationId"], unique = true),
        Index(value = ["taskId", "taskRevision", "stepId", "stepRevision"]),
        Index(value = ["expiresAtEpochMillis"])
    ]
)
data class McpInvocationAuthorizationEntity(
    @PrimaryKey val nonceDigest: String,
    val invocationId: String,
    val taskId: String,
    val taskRevision: Long,
    val planRevision: Long,
    val stepId: String,
    val stepRevision: Long,
    val profileId: String,
    val endpoint: String,
    val nativeToolId: String,
    val remoteToolName: String,
    val argumentsSha256: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val consumedAtEpochMillis: Long? = null
)
