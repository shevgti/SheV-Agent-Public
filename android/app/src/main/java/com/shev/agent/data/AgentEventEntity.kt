package com.shev.agent.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_events",
    indices = [Index(value = ["idempotencyKey"], unique = true)]
)
data class AgentEventEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val eventType: String,
    val message: String,
    val createdAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    val idempotencyKey: String? = null,
    val evidenceRef: String? = null
)
