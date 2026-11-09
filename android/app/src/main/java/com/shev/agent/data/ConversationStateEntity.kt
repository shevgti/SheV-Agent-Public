package com.shev.agent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Encrypted rolling context for the single primary conversation.
 * The transcript remains authoritative; this row is only a rebuildable checkpoint.
 */
@Entity(tableName = "conversation_state")
data class ConversationStateEntity(
    @PrimaryKey val conversationId: String,
    val summaryCiphertext: String,
    val summaryThroughMessageId: String?,
    val summaryVersion: Long,
    val updatedAtEpochMillis: Long,
    val status: String
)
