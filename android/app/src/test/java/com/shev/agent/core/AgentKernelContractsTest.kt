package com.shev.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentKernelContractsTest {
    private fun step(id: String, dependencies: Set<String> = emptySet()) = PlanStep(
        id = id,
        toolId = "memory.search",
        argumentsJson = "{}",
        dependsOn = dependencies,
        risk = ToolRisk.READ_ONLY,
        purpose = "Найти контекст",
        expectedOutcome = "Получены совпадения"
    )

    @Test
    fun acceptsDagAndPreservesDependencies() {
        val plan = AgentPlan(
            taskId = "task-1",
            revision = 0,
            summary = "Найти сведения и подготовить ответ",
            steps = listOf(step("a"), step("b", setOf("a")))
        )
        assertEquals(setOf("a"), plan.steps[1].dependsOn)
    }

    @Test
    fun rejectsCyclicPlan() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentPlan(
                taskId = "task-2",
                revision = 0,
                summary = "Цикл",
                steps = listOf(step("a", setOf("b")), step("b", setOf("a")))
            )
        }
    }

    @Test
    fun rejectsUnknownDependency() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentPlan(
                taskId = "task-3",
                revision = 0,
                summary = "Неизвестная зависимость",
                steps = listOf(step("a", setOf("missing")))
            )
        }
    }

    @Test
    fun rejectsDuplicateStepIds() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentPlan(
                taskId = "task-4",
                revision = 0,
                summary = "Повтор ID",
                steps = listOf(step("a"), step("a"))
            )
        }
    }

    @Test
    fun rejectsExpiredRequestDeadline() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentRequest(text = "Проверь задачу", source = RequestSource.CHAT,
                createdAtMillis = 100, deadlineAtMillis = 100)
        }
    }

    @Test
    fun memoryConfidenceMustBeBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryRecord(kind = MemoryKind.SEMANTIC, content = "Факт",
                sourceRef = "chat:1", confidence = 1.5f, createdAtMillis = 100)
        }
    }

    @Test
    fun budgetRejectsUnboundedStepCount() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskBudget(maxSteps = 101)
        }
    }
}
