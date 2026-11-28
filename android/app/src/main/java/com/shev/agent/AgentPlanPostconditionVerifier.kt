package com.shev.agent

/**
 * Independently observed evidence for one approved plan step.
 * evidenceReference must identify an observation produced outside the model's own narrative.
 */
enum class AgentPostconditionEvidenceSource { HOST_READ_BACK, AUTHORITATIVE_API, LOCAL_STATE_INSPECTION, MODEL_NARRATIVE, USER_ASSERTION }

data class AgentPostconditionObservation(
    val stepId: String,
    val verified: Boolean,
    val evidenceReference: String,
    val source: AgentPostconditionEvidenceSource
)

data class AgentPlanPostconditionResult(
    val verified: Boolean,
    val unverifiedStepIds: List<String>,
    val hasUnexpectedObservations: Boolean,
    val hasInvalidExecutionSteps: Boolean
)

/**
 * Deterministic gate between tool execution and workflow COMPLETE.
 *
 * This class does not manufacture observations or trust tool/model prose as proof.
 * The host must supply independently observed postconditions (e.g. a read-back,
 * repository state check, or an authoritative API result) for every executed step.
 */
object AgentPlanPostconditionVerifier {
    private fun hasValidEvidenceReference(observation: AgentPostconditionObservation): Boolean {
        val requiredPrefix = when (observation.source) {
            AgentPostconditionEvidenceSource.HOST_READ_BACK -> "host-readback:"
            AgentPostconditionEvidenceSource.AUTHORITATIVE_API -> "api:"
            AgentPostconditionEvidenceSource.LOCAL_STATE_INSPECTION -> "local-state:"
            AgentPostconditionEvidenceSource.MODEL_NARRATIVE,
            AgentPostconditionEvidenceSource.USER_ASSERTION -> return false
        }
        val reference = observation.evidenceReference.trim()
        return reference.startsWith(requiredPrefix) && reference.length > requiredPrefix.length
    }

    fun verify(
        execution: AgentPlanExecutionResult,
        observations: List<AgentPostconditionObservation>
    ): AgentPlanPostconditionResult {
        val expectedIds = execution.steps.map { it.stepId }
        val expectedSet = expectedIds.toSet()
        val invalidExecutionSteps = expectedIds.any { it.isBlank() } || expectedSet.size != expectedIds.size
        val counts = observations.groupingBy { it.stepId }.eachCount()
        val unexpected = observations.any { it.stepId !in expectedSet } ||
            counts.values.any { it > 1 }

        val byStep = observations.associateBy { it.stepId }
        val unverified = execution.steps.filter { step ->
            val observation = byStep[step.stepId]
            !step.success ||
                observation == null ||
                !observation.verified ||
                !hasValidEvidenceReference(observation) ||
                observation.source == AgentPostconditionEvidenceSource.MODEL_NARRATIVE ||
                observation.source == AgentPostconditionEvidenceSource.USER_ASSERTION ||
                counts[step.stepId] != 1
        }.map { it.stepId }

        val executionEligible = execution.status == AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION &&
            execution.steps.isNotEmpty() &&
            execution.steps.all { it.success }

        return AgentPlanPostconditionResult(
            verified = executionEligible && unverified.isEmpty() && !unexpected && !invalidExecutionSteps,
            unverifiedStepIds = unverified,
            hasUnexpectedObservations = unexpected,
            hasInvalidExecutionSteps = invalidExecutionSteps
        )
    }
}
