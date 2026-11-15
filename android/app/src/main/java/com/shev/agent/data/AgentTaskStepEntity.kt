package com.shev.agent.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "agent_task_steps",
    primaryKeys = ["taskId", "stepId"],
    indices = [Index(value = ["taskId", "position"], unique = true)]
)
data class AgentTaskStepEntity(
    val taskId: String,
    val stepId: String,
    val position: Int,
    val title: String,
    val toolId: String,
    val planHash: String,
    val status: String,
    val output: String = "",
    val errorCode: String? = null,
    val startedAtEpochMillis: Long? = null,
    val finishedAtEpochMillis: Long? = null
) {
    companion object {
        const val PENDING = "PENDING"
        const val RUNNING = "RUNNING"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
        const val INTERRUPTED = "INTERRUPTED"
    }
}
