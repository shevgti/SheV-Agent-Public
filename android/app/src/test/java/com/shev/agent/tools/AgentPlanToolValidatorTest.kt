package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.PlanStep
import com.shev.agent.core.ToolRisk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanToolValidatorTest {
    private fun registry(capability: AgentCapability = AgentCapability.ANALYZE_DATA) =
        AgentToolRegistry().apply {
            register(
                AgentToolDescriptor(
                    id = "local.search",
                    title = "Локальный поиск",
                    description = "Ищет локальные сведения",
                    capabilities = setOf(capability),
                    inputSchema = ToolInputSchema(
                        listOf(ToolInputField("query", ToolInputType.STRING, maxLength = 100))
                    )
                )
            )
        }

    private fun plan(
        toolId: String = "local.search",
        arguments: String = """{"query":"test"}""",
        risk: ToolRisk = ToolRisk.READ_ONLY
    ) = AgentPlan(
        taskId = "task-1",
        revision = 0L,
        summary = "Проверить план",
        steps = listOf(
            PlanStep(
                id = "step-1",
                toolId = toolId,
                argumentsJson = arguments,
                risk = risk,
                purpose = "Выполнить проверку",
                expectedOutcome = "Получен результат"
            )
        )
    )

    @Test fun registeredToolWithValidArgumentsAndRiskIsAccepted() {
        assertTrue(AgentPlanToolValidator.validate(plan(), registry()).accepted)
    }

    @Test fun unknownToolIsRejected() {
        assertFalse(
            AgentPlanToolValidator.validate(plan(toolId = "remote.unknown"), registry()).accepted
        )
    }

    @Test fun argumentsMustMatchRegisteredStrictSchema() {
        assertFalse(
            AgentPlanToolValidator.validate(
                plan(arguments = """{"query":42}"""),
                registry()
            ).accepted
        )
        assertFalse(
            AgentPlanToolValidator.validate(
                plan(arguments = """{"query":"ok","extra":true}"""),
                registry()
            ).accepted
        )
    }

    @Test fun orchestrationMetaToolsCannotBeIncludedInTaskPlans() {
        val metaRegistry = AgentToolRegistry().apply {
            register(
                AgentToolDescriptor(
                    id = "agent.save_task_plan",
                    title = "Сохранить план",
                    description = "Служебный инструмент оркестрации",
                    capabilities = setOf(AgentCapability.CREATE_DRAFT),
                    inputSchema = ToolInputSchema(
                        listOf(ToolInputField("query", ToolInputType.STRING))
                    ),
                    planExecutable = false
                )
            )
        }
        assertFalse(
            AgentPlanToolValidator.validate(
                plan(toolId = "agent.save_task_plan"),
                metaRegistry
            ).accepted
        )
    }

    @Test fun modelCannotUnderstateRiskOfRemoteOperation() {
        assertFalse(
            AgentPlanToolValidator.validate(
                plan(risk = ToolRisk.READ_ONLY),
                registry(AgentCapability.REMOTE_MCP_OPERATION)
            ).accepted
        )
        assertTrue(
            AgentPlanToolValidator.validate(
                plan(risk = ToolRisk.EXTERNAL_SIDE_EFFECT),
                registry(AgentCapability.REMOTE_MCP_OPERATION)
            ).accepted
        )
    }

    @Test fun projectFileChangesRequireAtLeastReversibleWriteRisk() {
        assertFalse(
            AgentPlanToolValidator.validate(
                plan(risk = ToolRisk.READ_ONLY),
                registry(AgentCapability.WRITE_PROJECT_FILES)
            ).accepted
        )
        assertTrue(
            AgentPlanToolValidator.validate(
                plan(risk = ToolRisk.REVERSIBLE_WRITE),
                registry(AgentCapability.WRITE_PROJECT_FILES)
            ).accepted
        )
    }
}
