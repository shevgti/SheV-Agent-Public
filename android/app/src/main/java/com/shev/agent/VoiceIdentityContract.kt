package com.shev.agent

/**
 * Контракт проверки голоса владельца для подтверждения чувствительных действий.
 *
 * Это только fail-closed policy boundary: распознавание речи, enrollment,
 * anti-spoof/liveness engine и защищённое хранилище голосового шаблона
 * должны быть подключены отдельными реализациями. Сам по себе этот файл
 * не распознаёт голос и не доказывает личность пользователя.
 */
enum class VoiceProfileState {
    NOT_ENROLLED,
    READY,
    LOCKED,
    UNAVAILABLE,
    DISABLED
}

data class VoiceVerificationChallenge(
    val requestId: String,
    val challengeId: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long
)

data class VoiceVerificationEvidence(
    val requestId: String,
    val challengeId: String,
    val speakerMatched: Boolean,
    val livenessPassed: Boolean,
    val confidence: Double,
    val verifiedAtMillis: Long,
    val validUntilMillis: Long
)

enum class VoiceAuthorizationDecision {
    ALLOW,
    PROFILE_NOT_READY,
    INVALID_CHALLENGE,
    CHALLENGE_NOT_YET_VALID,
    CHALLENGE_EXPIRED,
    REQUEST_MISMATCH,
    CHALLENGE_MISMATCH,
    SPEAKER_NOT_MATCHED,
    LIVENESS_NOT_CONFIRMED,
    INVALID_CONFIDENCE,
    CONFIDENCE_TOO_LOW,
    EVIDENCE_FROM_FUTURE,
    EVIDENCE_EXPIRED
}

object VoiceAuthorizationPolicy {
    const val DEFAULT_MINIMUM_CONFIDENCE = 0.90

    fun evaluate(
        profileState: VoiceProfileState,
        challenge: VoiceVerificationChallenge,
        evidence: VoiceVerificationEvidence,
        nowMillis: Long,
        minimumConfidence: Double = DEFAULT_MINIMUM_CONFIDENCE
    ): VoiceAuthorizationDecision {
        if (profileState != VoiceProfileState.READY) {
            return VoiceAuthorizationDecision.PROFILE_NOT_READY
        }
        if (challenge.requestId.isBlank() ||
            challenge.challengeId.isBlank() ||
            challenge.issuedAtMillis < 0 ||
            challenge.expiresAtMillis <= challenge.issuedAtMillis ||
            !minimumConfidence.isFinite() ||
            minimumConfidence !in 0.0..1.0
        ) {
            return VoiceAuthorizationDecision.INVALID_CHALLENGE
        }
        if (nowMillis < challenge.issuedAtMillis) {
            return VoiceAuthorizationDecision.CHALLENGE_NOT_YET_VALID
        }
        if (nowMillis >= challenge.expiresAtMillis) {
            return VoiceAuthorizationDecision.CHALLENGE_EXPIRED
        }
        if (evidence.requestId != challenge.requestId) {
            return VoiceAuthorizationDecision.REQUEST_MISMATCH
        }
        if (evidence.challengeId != challenge.challengeId) {
            return VoiceAuthorizationDecision.CHALLENGE_MISMATCH
        }
        if (!evidence.speakerMatched) {
            return VoiceAuthorizationDecision.SPEAKER_NOT_MATCHED
        }
        if (!evidence.livenessPassed) {
            return VoiceAuthorizationDecision.LIVENESS_NOT_CONFIRMED
        }
        if (!evidence.confidence.isFinite() || evidence.confidence !in 0.0..1.0) {
            return VoiceAuthorizationDecision.INVALID_CONFIDENCE
        }
        if (evidence.confidence < minimumConfidence) {
            return VoiceAuthorizationDecision.CONFIDENCE_TOO_LOW
        }
        if (evidence.verifiedAtMillis < challenge.issuedAtMillis ||
            evidence.verifiedAtMillis > nowMillis
        ) {
            return VoiceAuthorizationDecision.EVIDENCE_FROM_FUTURE
        }
        if (evidence.validUntilMillis <= nowMillis ||
            evidence.validUntilMillis <= evidence.verifiedAtMillis
        ) {
            return VoiceAuthorizationDecision.EVIDENCE_EXPIRED
        }
        return VoiceAuthorizationDecision.ALLOW
    }
}

enum class VoicePermissionIntent {
    ALLOW_ONCE,
    ALLOW_PERSISTENT,
    DENY,
    REVOKE
}

data class VoicePermissionCommand(
    val approvalRequestId: String,
    val intent: VoicePermissionIntent
)

enum class VoicePermissionCommandDecision {
    ACCEPT,
    OWNER_NOT_VERIFIED,
    NO_PENDING_APPROVAL,
    REQUEST_MISMATCH,
    REQUEST_EXPIRED
}

/**
 * Привязывает распознанную голосовую фразу к одному активному запросу.
 * Слова «разрешаю» недостаточно: вызывающая сторона должна передать
 * подтверждённую личность владельца и ID ожидающего запроса.
 */
object VoicePermissionCommandPolicy {
    fun evaluate(
        command: VoicePermissionCommand,
        pendingApprovalRequestId: String?,
        pendingExpiresAtMillis: Long?,
        nowMillis: Long,
        ownerVerifiedForThisRequest: Boolean
    ): VoicePermissionCommandDecision {
        if (!ownerVerifiedForThisRequest) {
            return VoicePermissionCommandDecision.OWNER_NOT_VERIFIED
        }
        val pendingId = pendingApprovalRequestId
            ?.takeIf { it.isNotBlank() }
            ?: return VoicePermissionCommandDecision.NO_PENDING_APPROVAL
        if (command.approvalRequestId != pendingId) {
            return VoicePermissionCommandDecision.REQUEST_MISMATCH
        }
        val expiresAt = pendingExpiresAtMillis
            ?: return VoicePermissionCommandDecision.NO_PENDING_APPROVAL
        if (nowMillis >= expiresAt) {
            return VoicePermissionCommandDecision.REQUEST_EXPIRED
        }
        return VoicePermissionCommandDecision.ACCEPT
    }
}
