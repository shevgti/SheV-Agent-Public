package com.shev.agent.core

/** Model-authored statements are never sufficient proof of task completion. */
enum class AgentVerificationEvidenceKind {
    LOCAL_STATE_SNAPSHOT, TEST_RESULT, KERNEL_EXECUTION_RECEIPT, FILE_HASH, REMOTE_RECEIPT, SIGNED_RECEIPT, MODEL_ASSERTION
}

data class AgentVerificationEvidence(
    val stepId: String,
    val verifierId: String,
    val kind: AgentVerificationEvidenceKind,
    val evidenceRef: String,
    val observedAtMillis: Long,
    val sha256: String? = null
) {
    init {
        require(stepId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(verifierId.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        require(evidenceRef.isNotBlank() && evidenceRef.length <= 512)
        require(observedAtMillis > 0)
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}")))
        if (kind == AgentVerificationEvidenceKind.FILE_HASH ||
            kind == AgentVerificationEvidenceKind.SIGNED_RECEIPT
        ) require(sha256 != null) { "Hash-backed evidence requires SHA-256" }
    }
}

enum class AgentVerificationFailure {
    NO_STEPS, UNKNOWN_STEP, UNTRUSTED_VERIFIER, MODEL_ONLY_EVIDENCE,
    MISSING_EVIDENCE, INSUFFICIENT_EVIDENCE, EVIDENCE_FROM_FUTURE
}

data class AgentVerificationDecision(
    val passed: Boolean,
    val failure: AgentVerificationFailure? = null,
    val stepId: String? = null,
    val acceptedEvidenceRefs: List<String> = emptyList()
)

/** Deterministic evidence gate. Trusted verifier IDs come from app-owned configuration. */
object AgentVerificationPolicy {
    fun evaluate(
        plan: AgentPlan,
        evidence: List<AgentVerificationEvidence>,
        trustedVerifierIds: Set<String>,
        nowMillis: Long
    ): AgentVerificationDecision {
        if (plan.steps.isEmpty()) return AgentVerificationDecision(false, AgentVerificationFailure.NO_STEPS)
        val knownStepIds = plan.steps.map { it.id }.toSet()
        val unknown = evidence.firstOrNull { it.stepId !in knownStepIds }
        if (unknown != null) {
            return AgentVerificationDecision(false, AgentVerificationFailure.UNKNOWN_STEP, unknown.stepId)
        }
        for (item in evidence) {
            if (item.verifierId !in trustedVerifierIds) {
                return AgentVerificationDecision(false, AgentVerificationFailure.UNTRUSTED_VERIFIER, item.stepId)
            }
            if (item.observedAtMillis > nowMillis) {
                return AgentVerificationDecision(false, AgentVerificationFailure.EVIDENCE_FROM_FUTURE, item.stepId)
            }
        }
        val acceptedRefs = mutableListOf<String>()
        for (step in plan.steps) {
            val stepEvidence = evidence.filter { it.stepId == step.id }
            if (stepEvidence.isEmpty()) {
                return AgentVerificationDecision(false, AgentVerificationFailure.MISSING_EVIDENCE, step.id)
            }
            if (stepEvidence.all { it.kind == AgentVerificationEvidenceKind.MODEL_ASSERTION }) {
                return AgentVerificationDecision(false, AgentVerificationFailure.MODEL_ONLY_EVIDENCE, step.id)
            }
            val requiredKinds = when (step.risk) {
                ToolRisk.READ_ONLY -> setOf(
                    AgentVerificationEvidenceKind.LOCAL_STATE_SNAPSHOT,
                    AgentVerificationEvidenceKind.TEST_RESULT,
                    AgentVerificationEvidenceKind.KERNEL_EXECUTION_RECEIPT
                )
                ToolRisk.REVERSIBLE_WRITE -> setOf(
                    AgentVerificationEvidenceKind.FILE_HASH,
                    AgentVerificationEvidenceKind.LOCAL_STATE_SNAPSHOT,
                    AgentVerificationEvidenceKind.TEST_RESULT
                )
                ToolRisk.EXTERNAL_SIDE_EFFECT -> setOf(AgentVerificationEvidenceKind.REMOTE_RECEIPT)
                ToolRisk.CRITICAL -> setOf(AgentVerificationEvidenceKind.SIGNED_RECEIPT)
            }
            val qualifying = stepEvidence.filter { it.kind in requiredKinds }
            if (qualifying.isEmpty()) {
                return AgentVerificationDecision(false, AgentVerificationFailure.INSUFFICIENT_EVIDENCE, step.id)
            }
            acceptedRefs += qualifying.map { it.evidenceRef }
        }
        return AgentVerificationDecision(true, acceptedEvidenceRefs = acceptedRefs.distinct().take(32))
    }
}
