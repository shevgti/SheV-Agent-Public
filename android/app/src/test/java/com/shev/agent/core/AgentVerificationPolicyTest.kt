package com.shev.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVerificationPolicyTest {
    private val trusted = setOf("verifier.room", "verifier.tests")
    private fun plan(risk: ToolRisk) = AgentPlan(
        "task-1", 0, "План проверки",
        listOf(PlanStep("step-1", "local.check", "{}", risk = risk,
            purpose = "Проверить результат", expectedOutcome = "Постусловие подтверждено"))
    )
    private fun evidence(
        kind: AgentVerificationEvidenceKind,
        verifier: String = "verifier.room",
        sha: String? = null
    ) = AgentVerificationEvidence(
        "step-1", verifier, kind, "evidence://task-1/step-1", 100, sha
    )

    @Test fun readOnlyRequiresDeterministicStateOrTestEvidence() {
        assertTrue(AgentVerificationPolicy.evaluate(plan(ToolRisk.READ_ONLY),
            listOf(evidence(AgentVerificationEvidenceKind.TEST_RESULT)), trusted, 200).passed)
    }

    @Test fun trustedKernelExecutionReceiptVerifiesReadOnlyStepOnly() {
        val readOnly = AgentVerificationPolicy.evaluate(
            plan(ToolRisk.READ_ONLY),
            listOf(evidence(AgentVerificationEvidenceKind.KERNEL_EXECUTION_RECEIPT)),
            trusted,
            200
        )
        assertTrue(readOnly.passed)

        val write = AgentVerificationPolicy.evaluate(
            plan(ToolRisk.REVERSIBLE_WRITE),
            listOf(evidence(AgentVerificationEvidenceKind.KERNEL_EXECUTION_RECEIPT)),
            trusted,
            200
        )
        assertEquals(AgentVerificationFailure.INSUFFICIENT_EVIDENCE, write.failure)
    }

    @Test fun modelAssertionCannotVerifyTask() {
        val result = AgentVerificationPolicy.evaluate(plan(ToolRisk.READ_ONLY),
            listOf(evidence(AgentVerificationEvidenceKind.MODEL_ASSERTION)), trusted, 200)
        assertFalse(result.passed)
        assertEquals(AgentVerificationFailure.MODEL_ONLY_EVIDENCE, result.failure)
    }

    @Test fun externalSideEffectRequiresRemoteReceipt() {
        val result = AgentVerificationPolicy.evaluate(plan(ToolRisk.EXTERNAL_SIDE_EFFECT),
            listOf(evidence(AgentVerificationEvidenceKind.TEST_RESULT)), trusted, 200)
        assertEquals(AgentVerificationFailure.INSUFFICIENT_EVIDENCE, result.failure)
    }

    @Test fun criticalActionRequiresSignedReceiptWithHash() {
        val result = AgentVerificationPolicy.evaluate(plan(ToolRisk.CRITICAL),
            listOf(evidence(AgentVerificationEvidenceKind.SIGNED_RECEIPT, sha = "a".repeat(64))),
            trusted, 200)
        assertTrue(result.passed)
    }

    @Test fun untrustedVerifierIsRejected() {
        val result = AgentVerificationPolicy.evaluate(plan(ToolRisk.READ_ONLY),
            listOf(evidence(AgentVerificationEvidenceKind.TEST_RESULT, verifier = "model.output")),
            trusted, 200)
        assertEquals(AgentVerificationFailure.UNTRUSTED_VERIFIER, result.failure)
    }

    @Test fun evidenceFromFutureIsRejected() {
        val result = AgentVerificationPolicy.evaluate(plan(ToolRisk.READ_ONLY),
            listOf(evidence(AgentVerificationEvidenceKind.TEST_RESULT).copy(observedAtMillis = 300)),
            trusted, 200)
        assertEquals(AgentVerificationFailure.EVIDENCE_FROM_FUTURE, result.failure)
    }

    @Test fun everyStepMustHaveItsOwnEvidence() {
        val first = plan(ToolRisk.READ_ONLY).steps.single()
        val second = PlanStep("step-2", "local.check", "{}", risk = ToolRisk.READ_ONLY,
            purpose = "Вторая проверка", expectedOutcome = "Второе постусловие")
        val twoStepPlan = plan(ToolRisk.READ_ONLY).copy(steps = listOf(first, second))
        val result = AgentVerificationPolicy.evaluate(twoStepPlan,
            listOf(evidence(AgentVerificationEvidenceKind.TEST_RESULT)), trusted, 200)
        assertEquals(AgentVerificationFailure.MISSING_EVIDENCE, result.failure)
    }
}
