package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.CapabilityRisk
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.ToolRisk

data class AgentPlanValidationResult(
    val accepted: Boolean,
    val reason: String? = null,
    val stepId: String? = null
)

/** Validates model-proposed plans against the exact local registry before persistence. */
object AgentPlanToolValidator {
    fun validate(plan: AgentPlan, registry: AgentToolRegistry): AgentPlanValidationResult {
        for (step in plan.steps) {
            val descriptor = registry.find(step.toolId)
                ?: return AgentPlanValidationResult(false, "Шаг использует незарегистрированный инструмент.", step.id)
            val schema = descriptor.inputSchema
                ?: return AgentPlanValidationResult(false, "У инструмента нет строгой схемы аргументов.", step.id)
            if (!schema.validate(step.argumentsJson).accepted) {
                return AgentPlanValidationResult(false, "Аргументы шага не соответствуют схеме инструмента.", step.id)
            }
            val requiredRisk = descriptor.capabilities.maxOf(::minimumRiskFor)
            if (step.risk.ordinal < requiredRisk.ordinal) {
                return AgentPlanValidationResult(false, "Уровень риска шага занижен относительно возможностей инструмента.", step.id)
            }
        }
        return AgentPlanValidationResult(true)
    }

    private fun minimumRiskFor(capability: AgentCapability): ToolRisk = when (capability.risk) {
        CapabilityRisk.LOW,
        CapabilityRisk.MEDIUM -> ToolRisk.READ_ONLY
        CapabilityRisk.HIGH -> when (capability) {
            AgentCapability.WRITE_PROJECT_FILES -> ToolRisk.REVERSIBLE_WRITE
            else -> ToolRisk.EXTERNAL_SIDE_EFFECT
        }
        CapabilityRisk.CRITICAL -> ToolRisk.CRITICAL
    }
}
