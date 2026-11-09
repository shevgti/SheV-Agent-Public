package com.shev.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Locally persisted conversation transcript. No cloud sync or telemetry. */
@Entity(
    tableName = "conversation_messages",
    indices = [Index(value = ["createdAtEpochMillis"])]
)
data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    val role: String,
    val content: String,
    val status: String,
    val createdAtEpochMillis: Long
)
