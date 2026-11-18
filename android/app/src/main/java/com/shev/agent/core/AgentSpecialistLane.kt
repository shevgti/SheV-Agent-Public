package com.shev.agent.core

/** A specialist lane constrains proposals; it never grants capabilities or bypasses the kernel. */
data class AgentSpecialistLanePolicy(
    val id: String,
    val allowedToolIds: Set<String>,
    val maxRisk: ToolRisk,
    val budget: TaskBudget,
    val requiresIndependentReview: Boolean = true,
    val canDelegate: Boolean = false
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        require(allowedToolIds.size <= 128)
        require(allowedToolIds.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,63}")) })
    }
}

/** Assignment metadata for the future durable specialist scheduler. */
data class AgentSpecialistAssignment(
    val specialistId: String,
    val laneId: String,
    val goal: String,
    val budget: TaskBudget,
    val mayDelegate: Boolean = false
) {
    init {
        require(specialistId.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        require(laneId.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        require(goal.isNotBlank() && goal.length <= 2_000)
    }
}

enum class SpecialistPolicyFailure {
    LANE_MISMATCH, DELEGATION_NOT_ALLOWED, BUDGET_EXCEEDS_LANE,
    TOOL_NOT_ALLOWLISTED, RISK_EXCEEDS_LANE, REVIEW_REQUIRED
}

data class SpecialistPolicyResult(
    val accepted: Boolean,
    val failure: SpecialistPolicyFailure? = null,
    val stepId: String? = null
)

/** Pure deterministic gate. Actual calls still pass through AgentToolExecutor and CapabilityPolicy. */
object AgentSpecialistPolicy {
    fun validate(
        assignment: AgentSpecialistAssignment,
        lane: AgentSpecialistLanePolicy,
        plan: AgentPlan
    ): SpecialistPolicyResult {
        if (assignment.laneId != lane.id) {
            return SpecialistPolicyResult(false, SpecialistPolicyFailure.LANE_MISMATCH)
        }
        if (assignment.mayDelegate && !lane.canDelegate) {
            return SpecialistPolicyResult(false, SpecialistPolicyFailure.DELEGATION_NOT_ALLOWED)
        }
        if (!assignment.budget.fitsWithin(lane.budget) ||
            !plan.budget.fitsWithin(assignment.budget) ||
            plan.steps.size > lane.budget.maxSteps
        ) {
            return SpecialistPolicyResult(false, SpecialistPolicyFailure.BUDGET_EXCEEDS_LANE)
        }
        for (step in plan.steps) {
            if (step.toolId !in lane.allowedToolIds) {
                return SpecialistPolicyResult(false, SpecialistPolicyFailure.TOOL_NOT_ALLOWLISTED, step.id)
            }
            if (step.risk.ordinal > lane.maxRisk.ordinal) {
                return SpecialistPolicyResult(false, SpecialistPolicyFailure.RISK_EXCEEDS_LANE, step.id)
            }
            if (lane.requiresIndependentReview &&
                step.risk.ordinal >= ToolRisk.EXTERNAL_SIDE_EFFECT.ordinal
            ) {
                return SpecialistPolicyResult(false, SpecialistPolicyFailure.REVIEW_REQUIRED, step.id)
            }
        }
        return SpecialistPolicyResult(true)
    }

    private fun TaskBudget.fitsWithin(limit: TaskBudget): Boolean =
        maxSteps <= limit.maxSteps &&
            maxRetriesPerStep <= limit.maxRetriesPerStep &&
            maxWallTimeMillis <= limit.maxWallTimeMillis &&
            maxModelCalls <= limit.maxModelCalls &&
            maxOutputCharsPerStep <= limit.maxOutputCharsPerStep
}
