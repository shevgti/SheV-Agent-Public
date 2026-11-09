package com.shev.agent.data

import androidx.room.withTransaction

sealed interface ConversationSummaryRead {
    data class Ready(
        val plaintext: String,
        val throughMessageId: String?,
        val version: Long,
        val updatedAtEpochMillis: Long
    ) : ConversationSummaryRead

    data object Missing : ConversationSummaryRead
    data object Unreadable : ConversationSummaryRead
}

/**
 * Stores rebuildable rolling context encrypted with the same Android Keystore
 * cipher as transcript messages. Original transcript rows are never modified.
 */
class ConversationStateRepository(
    private val database: SheVAgentDatabase
) {
    suspend fun saveSummary(
        summary: String,
        throughMessageId: String?,
        version: Long,
        updatedAtEpochMillis: Long,
        conversationId: String = PRIMARY_CONVERSATION_ID
    ): Boolean {
        if (summary.isBlank() || summary.length > MAX_SUMMARY_CHARS ||
            version <= 0L || updatedAtEpochMillis < 0L ||
            conversationId.isBlank()
        ) return false

        val encrypted = LocalMessageCipher.encrypt(summary)
        return database.withTransaction {
            val dao = database.agentDao()
            val previous = dao.getConversationState(conversationId)
            if (previous != null && version <= previous.summaryVersion) {
                return@withTransaction false
            }
            dao.saveConversationState(
                ConversationStateEntity(
                    conversationId = conversationId,
                    summaryCiphertext = encrypted,
                    summaryThroughMessageId = throughMessageId,
                    summaryVersion = version,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    status = "ready"
                )
            )
            true
        }
    }

    suspend fun readSummary(
        conversationId: String = PRIMARY_CONVERSATION_ID
    ): ConversationSummaryRead {
        val stored = database.agentDao().getConversationState(conversationId)
            ?: return ConversationSummaryRead.Missing
        val plaintext = runCatching {
            LocalMessageCipher.decrypt(stored.summaryCiphertext)
        }.getOrNull() ?: return ConversationSummaryRead.Unreadable
        return ConversationSummaryRead.Ready(
            plaintext = plaintext,
            throughMessageId = stored.summaryThroughMessageId,
            version = stored.summaryVersion,
            updatedAtEpochMillis = stored.updatedAtEpochMillis
        )
    }

    companion object {
        const val PRIMARY_CONVERSATION_ID = "main"
        const val MAX_SUMMARY_CHARS = 24_000
    }
}
