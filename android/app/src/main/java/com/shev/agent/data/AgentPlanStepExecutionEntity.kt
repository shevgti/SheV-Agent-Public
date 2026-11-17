package com.shev.agent.data

import androidx.room.Entity
import androidx.room.Index

/**
 * Durable execution state for one exact persisted plan step.
 * Result payloads are encrypted; invocationId remains stable across process restart.
 */
@Entity(
    tableName = "agent_plan_step_executions",
    primaryKeys = ["taskId", "stepId"],
    indices = [
        Index(value = ["taskId", "planRevision", "status"]),
        Index(value = ["invocationId"], unique = true),
        Index(value = ["status", "updatedAtEpochMillis"])
    ]
)
data class AgentPlanStepExecutionEntity(
    val taskId: String,
    val stepId: String,
    val planRevision: Long,
    val stepRevision: Long,
    val invocationId: String,
    val attempt: Int,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val dispatchStartedAtEpochMillis: Long? = null,
    val dispatchedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val resultCiphertext: String? = null,
    val errorCode: String? = null
) {
    companion object {
        const val PENDING = "PENDING"
        const val WAITING_APPROVAL = "WAITING_APPROVAL"
        const val DISPATCHING = "DISPATCHING"
        const val DISPATCHED = "DISPATCHED"
        const val SUCCEEDED = "SUCCEEDED"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    }
}
