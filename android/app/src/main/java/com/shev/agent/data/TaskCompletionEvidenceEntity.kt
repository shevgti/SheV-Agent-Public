package com.shev.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One-use completion evidence created by a trusted verifier.
 * USER_REVIEW means the person explicitly reviewed the persisted step results.
 */
@Entity(
    tableName = "task_completion_evidence",
    indices = [
        Index(value = ["taskId", "expectedRevision"]),
        Index(value = ["expiresAtEpochMillis"])
    ]
)
data class TaskCompletionEvidenceEntity(
    @PrimaryKey val evidenceHash: String,
    val taskId: String,
    val expectedRevision: Long,
    val planHash: String,
    val verifierType: String,
    val verificationNote: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val consumedAtEpochMillis: Long? = null
) {
    companion object {
        const val USER_REVIEW = "USER_REVIEW"
        const val AUTOMATED_POSTCONDITION = "AUTOMATED_POSTCONDITION"
    }
}
