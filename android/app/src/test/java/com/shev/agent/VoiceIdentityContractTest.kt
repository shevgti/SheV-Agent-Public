package com.shev.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIdentityContractTest {
    private val challenge = VoiceVerificationChallenge(
        requestId = "approval-17",
        challengeId = "challenge-abc",
        issuedAtMillis = 1_000,
        expiresAtMillis = 10_000
    )

    private fun evidence(
        requestId: String = challenge.requestId,
        challengeId: String = challenge.challengeId,
        speakerMatched: Boolean = true,
        livenessPassed: Boolean = true,
        confidence: Double = 0.97,
        verifiedAtMillis: Long = 2_000,
        validUntilMillis: Long = 5_000
    ) = VoiceVerificationEvidence(
        requestId, challengeId, speakerMatched, livenessPassed,
        confidence, verifiedAtMillis, validUntilMillis
    )

    @Test fun acceptsFreshOwnerMatchBoundToCurrentApproval() {
        assertEquals(
            VoiceAuthorizationDecision.ALLOW,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge, evidence(), nowMillis = 3_000
            )
        )
    }

    @Test fun rejectsWhenVoiceProfileIsNotReady() {
        listOf(
            VoiceProfileState.NOT_ENROLLED,
            VoiceProfileState.LOCKED,
            VoiceProfileState.UNAVAILABLE,
            VoiceProfileState.DISABLED
        ).forEach { state ->
            assertEquals(
                state.name,
                VoiceAuthorizationDecision.PROFILE_NOT_READY,
                VoiceAuthorizationPolicy.evaluate(state, challenge, evidence(), 3_000)
            )
        }
    }

    @Test fun rejectsExpiredOrNotYetValidChallenge() {
        assertEquals(
            VoiceAuthorizationDecision.CHALLENGE_EXPIRED,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge, evidence(), nowMillis = 10_000
            )
        )
        assertEquals(
            VoiceAuthorizationDecision.CHALLENGE_NOT_YET_VALID,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge, evidence(), nowMillis = 500
            )
        )
    }

    @Test fun rejectsEvidenceFromAnotherRequestOrChallenge() {
        assertEquals(
            VoiceAuthorizationDecision.REQUEST_MISMATCH,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge,
                evidence(requestId = "different-request"), 3_000
            )
        )
        assertEquals(
            VoiceAuthorizationDecision.CHALLENGE_MISMATCH,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge,
                evidence(challengeId = "replayed-challenge"), 3_000
            )
        )
    }

    @Test fun rejectsSpeakerMismatchAndMissingLiveness() {
        assertEquals(
            VoiceAuthorizationDecision.SPEAKER_NOT_MATCHED,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge,
                evidence(speakerMatched = false), 3_000
            )
        )
        assertEquals(
            VoiceAuthorizationDecision.LIVENESS_NOT_CONFIRMED,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge,
                evidence(livenessPassed = false), 3_000
            )
        )
    }

    @Test fun rejectsLowOrInvalidConfidence() {
        assertEquals(
            VoiceAuthorizationDecision.CONFIDENCE_TOO_LOW,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge,
                evidence(confidence = 0.89), 3_000
            )
        )
        assertEquals(
            VoiceAuthorizationDecision.INVALID_CONFIDENCE,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge,
                evidence(confidence = Double.NaN), 3_000
            )
        )
    }

    @Test fun rejectsFutureAndExpiredEvidence() {
        assertEquals(
            VoiceAuthorizationDecision.EVIDENCE_FROM_FUTURE,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge,
                evidence(verifiedAtMillis = 3_001), 3_000
            )
        )
        assertEquals(
            VoiceAuthorizationDecision.EVIDENCE_EXPIRED,
            VoiceAuthorizationPolicy.evaluate(
                VoiceProfileState.READY, challenge,
                evidence(validUntilMillis = 3_000), 3_000
            )
        )
    }

    @Test fun voiceCommandMustBelongToCurrentPendingApproval() {
        val command = VoicePermissionCommand(
            "approval-17", VoicePermissionIntent.ALLOW_ONCE
        )
        assertEquals(
            VoicePermissionCommandDecision.ACCEPT,
            VoicePermissionCommandPolicy.evaluate(
                command, "approval-17", 8_000, 3_000, ownerVerifiedForThisRequest = true
            )
        )
        assertEquals(
            VoicePermissionCommandDecision.OWNER_NOT_VERIFIED,
            VoicePermissionCommandPolicy.evaluate(
                command, "approval-17", 8_000, 3_000, ownerVerifiedForThisRequest = false
            )
        )
        assertEquals(
            VoicePermissionCommandDecision.REQUEST_MISMATCH,
            VoicePermissionCommandPolicy.evaluate(
                command, "another-approval", 8_000, 3_000, ownerVerifiedForThisRequest = true
            )
        )
        assertEquals(
            VoicePermissionCommandDecision.REQUEST_EXPIRED,
            VoicePermissionCommandPolicy.evaluate(
                command, "approval-17", 3_000, 3_000, ownerVerifiedForThisRequest = true
            )
        )
        assertEquals(
            VoicePermissionCommandDecision.NO_PENDING_APPROVAL,
            VoicePermissionCommandPolicy.evaluate(
                command, null, null, 3_000, ownerVerifiedForThisRequest = true
            )
        )
    }

    @Test fun persistentApprovalIsAnExplicitDistinctVoiceIntent() {
        assertEquals(
            VoicePermissionIntent.ALLOW_PERSISTENT,
            VoicePermissionCommand(
                "approval-17", VoicePermissionIntent.ALLOW_PERSISTENT
            ).intent
        )
    }
}
