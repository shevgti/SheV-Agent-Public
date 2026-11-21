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
        assertEquals(null, result.unverifiedStepIds)
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
