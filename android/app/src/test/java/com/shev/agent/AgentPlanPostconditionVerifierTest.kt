package com.shev.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanPostconditionVerifierTest {
    private fun execution() = AgentPlanExecutionResult(
        status = AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION,
        steps = listOf(
            AgentPlanStepExecution("step-1", "research.lookup", true, "found"),
            AgentPlanStepExecution("step-2", "research.open", true, "opened")
        )
    )

    @Test fun allSuccessfulStepsNeedMatchingIndependentEvidence() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(
                AgentPostconditionObservation("step-1", true, "api:https://example.org", AgentPostconditionEvidenceSource.AUTHORITATIVE_API),
                AgentPostconditionObservation("step-2", true, "host-readback:page title observed", AgentPostconditionEvidenceSource.HOST_READ_BACK)
            )
        )
        assertTrue(result.verified)
        assertEquals(emptyList<String>(), result.unverifiedStepIds)
    }

    @Test fun missingObservationCannotVerifyCompletion() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(AgentPostconditionObservation("step-1", true, "host-readback:source checked", AgentPostconditionEvidenceSource.HOST_READ_BACK))
        )
        assertFalse(result.verified)
        assertEquals(listOf("step-2"), result.unverifiedStepIds)
    }

    @Test fun failedObservationCannotVerifyCompletion() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(
                AgentPostconditionObservation("step-1", true, "host-readback:source checked", AgentPostconditionEvidenceSource.HOST_READ_BACK),
                AgentPostconditionObservation("step-2", false, "host-readback:page unavailable", AgentPostconditionEvidenceSource.HOST_READ_BACK)
            )
        )
        assertFalse(result.verified)
        assertEquals(listOf("step-2"), result.unverifiedStepIds)
    }

    @Test fun modelExecutionOutputAloneIsNotPostconditionEvidence() {
        val result = AgentPlanPostconditionVerifier.verify(execution(), emptyList())
        assertFalse(result.verified)
        assertEquals(listOf("step-1", "step-2"), result.unverifiedStepIds)
    }

    @Test fun failedExecutionCannotBeVerifiedEvenWithPositiveObservations() {
        val failed = execution().copy(status = AgentPlanExecutionStatus.STEP_FAILED)
        val result = AgentPlanPostconditionVerifier.verify(
            failed,
            listOf(
                AgentPostconditionObservation("step-1", true, "host-readback:checked", AgentPostconditionEvidenceSource.HOST_READ_BACK),
                AgentPostconditionObservation("step-2", true, "host-readback:checked", AgentPostconditionEvidenceSource.HOST_READ_BACK)
            )
        )
        assertFalse(result.verified)
    }


    @Test fun duplicateStepIdsInExecutionCannotBeVerifiedByOneObservation() {
        val duplicated = execution().copy(
            steps = listOf(
                AgentPlanStepExecution("step-1", "research.lookup", true, "first"),
                AgentPlanStepExecution("step-1", "research.lookup", true, "second")
            )
        )
        val result = AgentPlanPostconditionVerifier.verify(
            duplicated,
            listOf(AgentPostconditionObservation("step-1", true, "host-readback:read-back", AgentPostconditionEvidenceSource.HOST_READ_BACK))
        )
        assertFalse(result.verified)
        assertTrue(result.hasInvalidExecutionSteps)
    }

    @Test fun blankStepIdInExecutionCannotBeVerified() {
        val invalid = execution().copy(
            steps = listOf(AgentPlanStepExecution("", "research.lookup", true, "output"))
        )
        val result = AgentPlanPostconditionVerifier.verify(
            invalid,
            listOf(AgentPostconditionObservation("", true, "host-readback:read-back", AgentPostconditionEvidenceSource.HOST_READ_BACK))
        )
        assertFalse(result.verified)
        assertTrue(result.hasInvalidExecutionSteps)
    }

    @Test fun duplicateObservationsForSameStepAreRejected() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(
                AgentPostconditionObservation("step-1", true, "host-readback:first read-back", AgentPostconditionEvidenceSource.HOST_READ_BACK),
                AgentPostconditionObservation("step-1", true, "host-readback:second read-back", AgentPostconditionEvidenceSource.HOST_READ_BACK),
                AgentPostconditionObservation("step-2", true, "host-readback:read-back", AgentPostconditionEvidenceSource.HOST_READ_BACK)
            )
        )
        assertFalse(result.verified)
        assertTrue(result.hasUnexpectedObservations)
    }

    @Test fun emptyExecutionCannotBeVerified() {
        val empty = execution().copy(steps = emptyList())
        val result = AgentPlanPostconditionVerifier.verify(empty, emptyList())
        assertFalse(result.verified)
    }

    @Test fun blankEvidenceReferenceCannotVerifyStep() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(
                AgentPostconditionObservation("step-1", true, " ", AgentPostconditionEvidenceSource.HOST_READ_BACK),
                AgentPostconditionObservation("step-2", true, "host-readback:read-back", AgentPostconditionEvidenceSource.HOST_READ_BACK)
            )
        )
        assertFalse(result.verified)
        assertEquals(listOf("step-1"), result.unverifiedStepIds)
    }

    @Test fun everyNonExecutableStatusIsRejected() {
        val nonExecutableStatuses = AgentPlanExecutionStatus.values()
            .filter { it != AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION }
        nonExecutableStatuses.forEach { status ->
            val result = AgentPlanPostconditionVerifier.verify(
                execution().copy(status = status),
                listOf(
                    AgentPostconditionObservation("step-1", true, "host-readback:read-back", AgentPostconditionEvidenceSource.HOST_READ_BACK),
                    AgentPostconditionObservation("step-2", true, "host-readback:read-back", AgentPostconditionEvidenceSource.HOST_READ_BACK)
                )
            )
            assertFalse("Status $status must not verify", result.verified)
        }
    }

    @Test fun modelNarrativeCannotBeUsedAsIndependentEvidence() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(
                AgentPostconditionObservation("step-1", true, "the model says it worked", AgentPostconditionEvidenceSource.MODEL_NARRATIVE),
                AgentPostconditionObservation("step-2", true, "the model says it worked", AgentPostconditionEvidenceSource.MODEL_NARRATIVE)
            )
        )
        assertFalse(result.verified)
        assertEquals(listOf("step-1", "step-2"), result.unverifiedStepIds)
    }

    @Test fun observationsForUnknownStepsAreRejected() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(
                AgentPostconditionObservation("step-1", true, "host-readback:checked", AgentPostconditionEvidenceSource.HOST_READ_BACK),
                AgentPostconditionObservation("step-2", true, "host-readback:checked", AgentPostconditionEvidenceSource.HOST_READ_BACK),
                AgentPostconditionObservation("invented", true, "not valid", AgentPostconditionEvidenceSource.HOST_READ_BACK)
            )
        )
        assertFalse(result.verified)
        assertTrue(result.hasUnexpectedObservations)
    }
}
