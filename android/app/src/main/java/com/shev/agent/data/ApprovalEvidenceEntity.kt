package com.shev.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A short-lived, one-use confirmation issued only by an explicit user UI action.
 * The raw nonce is never stored; only its SHA-256 digest is persisted.
 */
@Entity(
    tableName = "approval_evidence",
    indices = [
        Index(value = ["taskId", "expectedRevision"]),
        Index(value = ["expiresAtEpochMillis"])
    ]
)
data class ApprovalEvidenceEntity(
    @PrimaryKey val evidenceHash: String,
    val taskId: String,
    val expectedRevision: Long,
    val operation: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val consumedAtEpochMillis: Long? = null
)


/** Generates opaque approval nonces and their non-reversible database representation. */
internal object ApprovalEvidenceToken {
    private val secureRandom = java.security.SecureRandom()

    fun create(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun digest(token: String): String {
        require(token.length == 43 && token.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            "Invalid approval token format."
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/** Room-backed verifier consumes the approval row with one conditional UPDATE. */
class RoomAgentTaskAuthorizationVerifier(
    private val dao: AgentDao
) : AgentTaskAuthorizationVerifier {
    override suspend fun consumeApprovalEvidence(
        taskId: String,
        expectedRevision: Long,
        evidenceRef: String,
        nowMillis: Long
    ): Boolean {
        val hash = runCatching { ApprovalEvidenceToken.digest(evidenceRef) }.getOrNull()
            ?: return false
        return dao.consumeApprovalEvidence(
            evidenceHash = hash,
            taskId = taskId,
            expectedRevision = expectedRevision,
            operation = APPROVE_OPERATION,
            nowMillis = nowMillis
        ) == 1
    }

    override suspend fun isCompletionEvidenceValid(
        taskId: String,
        expectedRevision: Long,
        evidenceRef: String
    ): Boolean = false

    private companion object {
        const val APPROVE_OPERATION = "APPROVE"
    }
}
