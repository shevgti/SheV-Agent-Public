package com.shev.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSpecialistPolicyTest {
    private val budget = TaskBudget(4, 1, 60_000, 4, 4_000)
    private val lane = AgentSpecialistLanePolicy(
        "research", setOf("local.search", "local.read"), ToolRisk.READ_ONLY, budget
    )
    private val assignment = AgentSpecialistAssignment(
        "worker.research", "research", "Найти подтверждённые сведения", budget
    )
    private fun plan(
        toolId: String = "local.search",
        risk: ToolRisk = ToolRisk.READ_ONLY,
        planBudget: TaskBudget = budget
    ) = AgentPlan(
        "task-1", 0, "План исследования",
        listOf(PlanStep("step-1", toolId, """{"query":"test"}""", risk = risk,
            purpose = "Поиск", expectedOutcome = "Найден результат")),
        planBudget
    )

    @Test fun allowlistedReadOnlyPlanIsAccepted() {
        assertTrue(AgentSpecialistPolicy.validate(assignment, lane, plan()).accepted)
    }

    @Test fun unknownToolIsRejected() {
        assertEquals(SpecialistPolicyFailure.TOOL_NOT_ALLOWLISTED,
            AgentSpecialistPolicy.validate(assignment, lane, plan(toolId = "remote.write")).failure)
    }

    @Test fun riskCannotExceedLaneCeiling() {
        assertEquals(SpecialistPolicyFailure.RISK_EXCEEDS_LANE,
            AgentSpecialistPolicy.validate(assignment, lane, plan(risk = ToolRisk.EXTERNAL_SIDE_EFFECT)).failure)
    }

    @Test fun assignmentCannotChangeLaneIdentity() {
        assertEquals(SpecialistPolicyFailure.LANE_MISMATCH,
            AgentSpecialistPolicy.validate(assignment.copy(laneId = "implementation"), lane, plan()).failure)
    }

    @Test fun specialistCannotSelfDelegateWhenLaneDisallowsIt() {
        assertEquals(SpecialistPolicyFailure.DELEGATION_NOT_ALLOWED,
            AgentSpecialistPolicy.validate(assignment.copy(mayDelegate = true), lane, plan()).failure)
    }

    @Test fun budgetCannotExceedLaneLimit() {
        val excessive = TaskBudget(5, 1, 60_000, 4, 4_000)
        assertEquals(SpecialistPolicyFailure.BUDGET_EXCEEDS_LANE,
            AgentSpecialistPolicy.validate(assignment.copy(budget = excessive), lane,
                plan(planBudget = excessive)).failure)
    }

    @Test fun externalSideEffectRequiresIndependentReview() {
        val reviewLane = lane.copy(id = "ops", maxRisk = ToolRisk.CRITICAL, requiresIndependentReview = true)
        assertEquals(SpecialistPolicyFailure.REVIEW_REQUIRED,
            AgentSpecialistPolicy.validate(assignment.copy(laneId = "ops"), reviewLane,
                plan(risk = ToolRisk.EXTERNAL_SIDE_EFFECT)).failure)
    }
}
