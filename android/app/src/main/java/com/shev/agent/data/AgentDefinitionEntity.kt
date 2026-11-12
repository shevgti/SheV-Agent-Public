package com.shev.agent.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agent_definitions", indices = [Index(value = ["updatedAtEpochMillis"])])
data class AgentDefinitionEntity(
    @PrimaryKey val agentId: String,
    val name: String,
    val purpose: String,
    val manifestJson: String,
    @ColumnInfo(defaultValue = "''") val agentMarkdown: String = "",
    val status: String,
    val version: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)
