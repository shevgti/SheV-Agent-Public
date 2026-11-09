package com.shev.agent

import com.shev.agent.tools.AgentToolDescriptor
import com.shev.agent.tools.AgentToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanValidatorTest {
    private fun registry(): AgentToolRegistry = AgentToolRegistry().apply {
        register(
            AgentToolDescriptor(
                id = "notes.read",
                title = "Чтение заметок",
                description = "Читает выбранные заметки",
                capabilities = setOf(AgentCapability.READ_LOCAL_DATA)
            )
        )
    }

    private fun step(id: String = "step-1") = AgentPlanStep(
        id = id,
        title = "Прочитать заметки",
        toolId = "notes.read",
        capability = AgentCapability.READ_LOCAL_DATA,
        input = "selected"
    )

    @Test fun acceptsPlanWithRegisteredToolAndDeclaredCapability() {
        val result = AgentPlanValidator(registry()).validate(
            AgentPlan("Изучить выбранные заметки", listOf(step()))
        )
        assertTrue(result.accepted)
    }

    @Test fun rejectsUnknownTool() {
        val result = AgentPlanValidator(registry()).validate(
            AgentPlan("План", listOf(step().copy(toolId = "unknown.tool")))
        )
        assertEquals(AgentPlanRejection.UNKNOWN_TOOL, result.rejection)
    }

    @Test fun rejectsCapabilityNotDeclaredByTool() {
        val result = AgentPlanValidator(registry()).validate(
            AgentPlan("План", listOf(step().copy(capability = AgentCapability.DELETE_OR_OVERWRITE_DATA)))
        )
        assertEquals(AgentPlanRejection.CAPABILITY_NOT_DECLARED, result.rejection)
    }

    @Test fun rejectsDuplicateStepIds() {
        val result = AgentPlanValidator(registry()).validate(
            AgentPlan("План", listOf(step("same"), step("same")))
        )
        assertEquals(AgentPlanRejection.INVALID_STEP_ID, result.rejection)
    }

    @Test fun rejectsEmptyAndOversizedPlans() {
        val validator = AgentPlanValidator(registry(), maxSteps = 1)
        assertEquals(
            AgentPlanRejection.EMPTY_PLAN,
            validator.validate(AgentPlan("План", emptyList())).rejection
        )
        assertEquals(
            AgentPlanRejection.TOO_MANY_STEPS,
            validator.validate(AgentPlan("План", listOf(step("a"), step("b")))).rejection
        )
    }

    @Test fun rejectsBlankSummaryAndInvalidStepTitle() {
        val validator = AgentPlanValidator(registry())
        assertEquals(
            AgentPlanRejection.EMPTY_SUMMARY,
            validator.validate(AgentPlan(" ", listOf(step()))).rejection
        )
        assertFalse(
            validator.validate(AgentPlan("План", listOf(step().copy(title = " ")))).accepted
        )
    }

    @Test fun rejectsOversizedToolInput() {
        val oversized = "x".repeat(8193)
        val result = AgentPlanValidator(registry()).validate(
            AgentPlan("План", listOf(step().copy(input = oversized)))
        )
        assertEquals(AgentPlanRejection.INPUT_TOO_LARGE, result.rejection)
    }
}
