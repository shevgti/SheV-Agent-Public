package com.shev.agent

/**
 * Independently observed evidence for one approved plan step.
 * evidenceReference must identify an observation produced outside the model's own narrative.
 */
data class AgentPostconditionObservation(
    val stepId: String,
    val verified: Boolean,
    val evidenceReference: String
)

data class AgentPlanPostconditionResult(
    val verified: Boolean,
    val unverifiedStepIds: List<String>,
    val hasUnexpectedObservations: Boolean
)

/**
 * Deterministic gate between tool execution and workflow COMPLETE.
 *
 * This class does not manufacture observations or trust tool/model prose as proof.
 * The host must supply independently observed postconditions (e.g. a read-back,
 * repository state check, or an authoritative API result) for every executed step.
 */
object AgentPlanPostconditionVerifier {
    fun verify(
        execution: AgentPlanExecutionResult,
        observations: List<AgentPostconditionObservation>
    ): AgentPlanPostconditionResult {
        val expectedIds = execution.steps.map { it.stepId }
        val expectedSet = expectedIds.toSet()
        val counts = observations.groupingBy { it.stepId }.eachCount()
        val unexpected = observations.any { it.stepId !in expectedSet } ||
            counts.values.any { it > 1 }

        val byStep = observations.associateBy { it.stepId }
        val unverified = execution.steps.filter { step ->
            val observation = byStep[step.stepId]
            !step.success ||
                observation?.verified != true ||
                observation.evidenceReference.isBlank() ||
                counts[step.stepId] != 1
        }.map { it.stepId }

        val executionEligible = execution.status == AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION &&
            execution.steps.isNotEmpty() &&
            execution.steps.all { it.success }

        return AgentPlanPostconditionResult(
            verified = executionEligible && unverified.isEmpty() && !unexpected,
            unverifiedStepIds = unverified,
            hasUnexpectedObservations = unexpected
        )
    }
}
