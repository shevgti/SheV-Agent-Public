package com.shev.agent.creator

import com.shev.agent.AgentCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunPolicyTest {
    private fun manifest(
        tools: List<String>,
        networkPolicy: String = "OFFLINE"
    ) = AgentManifest(
        id = "approved-specialist",
        name = "Проверенный специалист",
        purpose = "Выполнять ограниченную задачу с проверяемым результатом",
        role = "Исследователь",
        instructions = "Работай по инструкции и не выдумывай выполненные действия.",
        triggers = listOf("Проверь данные", "Подготовь отчёт"),
        inputSchema = """{"type":"object","properties":{},"required":[]}""",
        outputSchema = """{"type":"object","properties":{},"required":[]}""",
        allowedToolIds = tools,
        memoryReadScopes = emptyList(),
        memoryWriteScopes = emptyList(),
        maxSteps = 4,
        timeoutSeconds = 60,
        maxModelCalls = 2,
        networkPolicy = networkPolicy,
        sideEffectPolicy = "DRAFT_ONLY",
        verificationStrategy = "Проверь результат по источникам и явно укажи ограничения.",
        evaluationCases = listOf("Обычная задача", "Недостаточно входных данных"),
        limitations = listOf("Нет произвольного доступа к файлам"),
        status = AgentManifest.STATUS_APPROVED,
        description = "Use this agent when a constrained research task is needed. It checks sources and reports limitations.",
        triggeringExamples = listOf(
            AgentTriggerExample("Нужна проверка", "Проверь данные", "Проверю доступные сведения.", "Нужен специалист.", "Использую специалиста."),
            AgentTriggerExample("Нужен отчёт", "Подготовь отчёт", "Соберу проверяемый отчёт.", "Подходит роль исследователя.", "Использую специалиста.")
        )
    )

    @Test fun allowsApprovedSpecialistWithRegisteredLowRiskTool() {
        val decision = AgentRunPolicy.evaluate(
            manifest(listOf("local.time")),
            mapOf("local.time" to setOf(AgentCapability.ANALYZE_DATA))
        )
        assertTrue(decision.allowed)
    }

    @Test fun rejectsToolMissingFromLiveHandlerCatalog() {
        val decision = AgentRunPolicy.evaluate(
            manifest(listOf("weather.current")),
            mapOf("local.time" to setOf(AgentCapability.ANALYZE_DATA))
        )
        assertFalse(decision.allowed)
    }

    @Test fun rejectsRemoteToolForOfflineSpecialist() {
        val decision = AgentRunPolicy.evaluate(
            manifest(listOf("weather.current"), networkPolicy = "OFFLINE"),
            mapOf("weather.current" to setOf(AgentCapability.READ_REMOTE_DATA))
        )
        assertFalse(decision.allowed)
    }

    @Test fun allowsRemoteReadOnlyToolOnlyWhenManifestDeclaresReadOnlyNetwork() {
        val decision = AgentRunPolicy.evaluate(
            manifest(listOf("weather.current"), networkPolicy = "READ_ONLY"),
            mapOf("weather.current" to setOf(AgentCapability.READ_REMOTE_DATA))
        )
        assertTrue(decision.allowed)
    }

    @Test fun rejectsHighRiskToolsEvenWhenRegistered() {
        val decision = AgentRunPolicy.evaluate(
            manifest(listOf("project.write")),
            mapOf("project.write" to setOf(AgentCapability.WRITE_PROJECT_FILES))
        )
        assertFalse(decision.allowed)
    }
}
