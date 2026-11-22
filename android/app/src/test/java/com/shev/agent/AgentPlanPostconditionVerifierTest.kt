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
                AgentPostconditionObservation("step-1", true, "https://example.org"),
                AgentPostconditionObservation("step-2", true, "page title observed")
            )
        )
        assertTrue(result.verified)
        assertEquals(emptyList<String>(), result.unverifiedStepIds)
    }

    @Test fun missingObservationCannotVerifyCompletion() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(AgentPostconditionObservation("step-1", true, "source checked"))
        )
        assertFalse(result.verified)
        assertEquals(listOf("step-2"), result.unverifiedStepIds)
    }

    @Test fun failedObservationCannotVerifyCompletion() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(
                AgentPostconditionObservation("step-1", true, "source checked"),
                AgentPostconditionObservation("step-2", false, "page unavailable")
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
                AgentPostconditionObservation("step-1", true, "checked"),
                AgentPostconditionObservation("step-2", true, "checked")
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
            listOf(AgentPostconditionObservation("step-1", true, "read-back"))
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
            listOf(AgentPostconditionObservation("", true, "read-back"))
        )
        assertFalse(result.verified)
        assertTrue(result.hasInvalidExecutionSteps)
    }

    @Test fun duplicateObservationsForSameStepAreRejected() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(
                AgentPostconditionObservation("step-1", true, "first read-back"),
                AgentPostconditionObservation("step-1", true, "second read-back"),
                AgentPostconditionObservation("step-2", true, "read-back")
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
                AgentPostconditionObservation("step-1", true, " "),
                AgentPostconditionObservation("step-2", true, "read-back")
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
                    AgentPostconditionObservation("step-1", true, "read-back"),
                    AgentPostconditionObservation("step-2", true, "read-back")
                )
            )
            assertFalse("Status $status must not verify", result.verified)
        }
    }

    @Test fun observationsForUnknownStepsAreRejected() {
        val result = AgentPlanPostconditionVerifier.verify(
            execution(),
            listOf(
                AgentPostconditionObservation("step-1", true, "checked"),
                AgentPostconditionObservation("step-2", true, "checked"),
                AgentPostconditionObservation("invented", true, "not valid")
            )
        )
        assertFalse(result.verified)
        assertTrue(result.hasUnexpectedObservations)
    }
}
