package com.shev.agent

import com.shev.agent.tools.AgentToolRegistry

/**
 * Structured, non-executable plan proposed by the model.
 * A plan is data only; it never invokes tools by itself.
 */
data class AgentPlanStep(
    val id: String,
    val title: String,
    val toolId: String,
    val capability: AgentCapability,
    val input: String
)

data class AgentPlan(
    val summary: String,
    val steps: List<AgentPlanStep>
)

enum class AgentPlanRejection {
    EMPTY_SUMMARY,
    EMPTY_PLAN,
    TOO_MANY_STEPS,
    INVALID_STEP_ID,
    EMPTY_STEP_TITLE,
    UNKNOWN_TOOL,
    CAPABILITY_NOT_DECLARED,
    INPUT_SCHEMA_MISSING,
    INVALID_TOOL_INPUT,
    INPUT_TOO_LARGE
}

data class AgentPlanValidation(
    val accepted: Boolean,
    val rejection: AgentPlanRejection? = null,
    val stepId: String? = null
)

/**
 * Validates only the shape and tool declarations of a proposed plan.
 * User grants and per-action approvals are checked again by AgentToolExecutor
 * at execution time; passing this validator never authorizes execution.
 */
class AgentPlanValidator(
    private val registry: AgentToolRegistry,
    private val maxSteps: Int = 8
) {
    init {
        require(maxSteps in 1..32) { "Лимит шагов должен быть от 1 до 32" }
    }

    fun validate(plan: AgentPlan): AgentPlanValidation {
        if (plan.summary.isBlank()) {
            return AgentPlanValidation(false, AgentPlanRejection.EMPTY_SUMMARY)
        }
        if (plan.steps.isEmpty()) {
            return AgentPlanValidation(false, AgentPlanRejection.EMPTY_PLAN)
        }
        if (plan.steps.size > maxSteps) {
            return AgentPlanValidation(false, AgentPlanRejection.TOO_MANY_STEPS)
        }

        val seen = mutableSetOf<String>()
        for (step in plan.steps) {
            if (!step.id.matches(Regex("[a-zA-Z0-9_-]{1,48}")) || !seen.add(step.id)) {
                return AgentPlanValidation(false, AgentPlanRejection.INVALID_STEP_ID, step.id)
            }
            if (step.title.isBlank()) {
                return AgentPlanValidation(false, AgentPlanRejection.EMPTY_STEP_TITLE, step.id)
            }
            if (step.input.length > 8192) {
                return AgentPlanValidation(false, AgentPlanRejection.INPUT_TOO_LARGE, step.id)
            }
            val descriptor = registry.find(step.toolId)
                ?: return AgentPlanValidation(false, AgentPlanRejection.UNKNOWN_TOOL, step.id)
            if (step.capability !in descriptor.capabilities) {
                return AgentPlanValidation(false, AgentPlanRejection.CAPABILITY_NOT_DECLARED, step.id)
            }
            val schema = descriptor.inputSchema
                ?: return AgentPlanValidation(false, AgentPlanRejection.INPUT_SCHEMA_MISSING, step.id)
            if (!schema.validate(step.input).accepted) {
                return AgentPlanValidation(false, AgentPlanRejection.INVALID_TOOL_INPUT, step.id)
            }
        }
        return AgentPlanValidation(true)
    }
}
