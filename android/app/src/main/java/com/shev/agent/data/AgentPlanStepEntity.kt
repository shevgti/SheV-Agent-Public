package com.shev.agent.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Per-step revision and canonical fingerprint within the task's current plan. */
@Entity(
    tableName = "agent_plan_steps",
    primaryKeys = ["taskId", "stepId"],
    foreignKeys = [
        ForeignKey(
            entity = AgentPlanEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId", "planRevision"])]
)
data class AgentPlanStepEntity(
    val taskId: String,
    val stepId: String,
    val planRevision: Long,
    val stepRevision: Long,
    val canonicalStepJson: String
)
