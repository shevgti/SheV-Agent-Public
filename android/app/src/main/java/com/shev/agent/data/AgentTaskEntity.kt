package com.shev.agent.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val phase: String,
    val requiresApproval: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    val resumePhase: String? = null
)
