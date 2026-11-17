package com.shev.agent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Canonical, validated DAG plan. One current plan is retained per task. */
@Entity(tableName = "agent_plans")
data class AgentPlanEntity(
    @PrimaryKey val taskId: String,
    val planRevision: Long,
    val canonicalPlanJson: String,
    val savedAtEpochMillis: Long
)
