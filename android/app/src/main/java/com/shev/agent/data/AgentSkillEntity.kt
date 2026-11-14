package com.shev.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Verified reusable knowledge; this stores instructions/evidence, never executable code or secrets. */
@Entity(
    tableName = "agent_skills",
    indices = [Index(value = ["updatedAtEpochMillis"]), Index(value = ["status"])]
)
data class AgentSkillEntity(
    @PrimaryKey val skillId: String,
    val title: String,
    val summary: String,
    val instructions: String,
    val keywords: String,
    val sourceUrls: String,
    val status: String,
    val verificationNotes: String,
    val version: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
) {
    companion object {
        const val STATUS_DRAFT = "DRAFT"
        const val STATUS_VERIFIED = "VERIFIED"
        const val STATUS_DISABLED = "DISABLED"
    }
}
