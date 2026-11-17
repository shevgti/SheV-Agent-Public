package com.shev.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalGraphValidatorTest {
    private val budget = AgentGoalBudget(
        maxMilestones = 4,
        maxTasks = 8,
        maxTotalSteps = 24,
        maxStepsPerTask = 6,
        maxWallTimePerTaskMillis = 60_000,
        maxTotalWallTimeMillis = 240_000,
        maxModelCallsPerTask = 4,
        maxTotalModelCalls = 16
    )
    private fun graph() = AgentGoalGraph(
        goalId = "goal-1",
        title = "Подготовить релиз",
        milestones = listOf(
            AgentGoalMilestone("design", "Подготовка"),
            AgentGoalMilestone("build", "Сборка", setOf("design"))
        ),
        tasks = listOf(
            AgentGoalTask("prepare", "design", budget = TaskBudget(3, 1, 30_000, 2, 4_000)),
            AgentGoalTask("compile", "build", setOf("prepare"), TaskBudget(3, 1, 30_000, 2, 4_000))
        ),
        budget = budget
    )

    @Test fun validGoalMilestoneTaskHierarchyIsAccepted() {
        assertTrue(AgentGoalGraphValidator.validate(graph()).accepted)
    }

    @Test fun milestoneCycleIsRejected() {
        val value = graph().copy(milestones = listOf(
            AgentGoalMilestone("design", "Подготовка", setOf("build")),
            AgentGoalMilestone("build", "Сборка", setOf("design"))
        ))
        assertEquals(AgentGoalGraphFailure.MILESTONE_CYCLE,
            AgentGoalGraphValidator.validate(value).failure)
    }

    @Test fun taskCycleIsRejected() {
        val value = graph().copy(tasks = listOf(
            AgentGoalTask("prepare", "design", setOf("compile"), TaskBudget(3, 1, 30_000, 2, 4_000)),
            AgentGoalTask("compile", "build", setOf("prepare"), TaskBudget(3, 1, 30_000, 2, 4_000))
        ))
        assertEquals(AgentGoalGraphFailure.TASK_CYCLE,
            AgentGoalGraphValidator.validate(value).failure)
    }

    @Test fun taskCannotDependOnFutureMilestone() {
        val value = graph().copy(tasks = listOf(
            AgentGoalTask("prepare", "design", setOf("compile"), TaskBudget(3, 1, 30_000, 2, 4_000)),
            AgentGoalTask("compile", "build", budget = TaskBudget(3, 1, 30_000, 2, 4_000))
        ))
        assertEquals(AgentGoalGraphFailure.CROSS_MILESTONE_DEPENDENCY,
            AgentGoalGraphValidator.validate(value).failure)
    }

    @Test fun unknownTaskDependencyIsRejected() {
        val value = graph().copy(tasks = listOf(
            AgentGoalTask("prepare", "design", setOf("missing"), TaskBudget(3, 1, 30_000, 2, 4_000)),
            AgentGoalTask("compile", "build", budget = TaskBudget(3, 1, 30_000, 2, 4_000))
        ))
        assertEquals(AgentGoalGraphFailure.UNKNOWN_TASK_DEPENDENCY,
            AgentGoalGraphValidator.validate(value).failure)
    }

    @Test fun perTaskBudgetCannotExceedGoalEnvelope() {
        val value = graph().copy(tasks = listOf(
            AgentGoalTask("prepare", "design", budget = TaskBudget(7, 1, 30_000, 2, 4_000)),
            AgentGoalTask("compile", "build", setOf("prepare"), TaskBudget(3, 1, 30_000, 2, 4_000))
        ))
        assertEquals(AgentGoalGraphFailure.TASK_BUDGET_EXCEEDED,
            AgentGoalGraphValidator.validate(value).failure)
    }

    @Test fun aggregateBudgetCannotExceedGoalEnvelope() {
        val taskBudget = TaskBudget(6, 1, 60_000, 4, 4_000)
        val value = graph().copy(tasks = listOf(
            AgentGoalTask("prepare", "design", budget = taskBudget),
            AgentGoalTask("prepare2", "design", setOf("prepare"), taskBudget),
            AgentGoalTask("compile", "build", setOf("prepare2"), taskBudget),
            AgentGoalTask("package", "build", setOf("compile"), taskBudget),
            AgentGoalTask("verify", "build", setOf("package"), taskBudget)
        ))
        assertEquals(AgentGoalGraphFailure.GOAL_BUDGET_EXCEEDED,
            AgentGoalGraphValidator.validate(value).failure)
    }

    @Test fun duplicateMilestoneIdsAreRejected() {
        val value = graph().copy(milestones = listOf(
            AgentGoalMilestone("design", "Подготовка"),
            AgentGoalMilestone("design", "Повтор")
        ))
        assertEquals(AgentGoalGraphFailure.DUPLICATE_ID,
            AgentGoalGraphValidator.validate(value).failure)
    }
}
