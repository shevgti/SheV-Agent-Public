package com.shev.agent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "improvements")
data class ImprovementEntity(
    @PrimaryKey val id: String,
    val summary: String,
    val diffPreview: String,
    val stage: String,
    val diffReviewed: Boolean,
    val testReportId: String?,
    val testResult: String?,
    val approvalGranted: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val expectedHeadSha: String? = null
)
