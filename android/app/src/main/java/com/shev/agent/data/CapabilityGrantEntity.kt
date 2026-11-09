package com.shev.agent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent user-issued capability grant. Store only metadata; never store voice audio here.
 */
@Entity(tableName = "capability_grants")
data class CapabilityGrantEntity(
    @PrimaryKey val grantId: String,
    val capability: String,
    val scopeType: String,
    val scopeValue: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val revokedAtEpochMillis: Long? = null
)
