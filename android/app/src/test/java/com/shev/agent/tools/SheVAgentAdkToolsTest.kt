package com.shev.agent.tools

import com.shev.agent.AgentCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SheVAgentAdkToolsTest {
    @Test fun adkAdapterRejectsHostToolOutsideAgentAllowlistBeforeHandlerRuns() {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                SheVAgentAdkTools.TOOL_ID,
                "Каталог инструментов",
                "Показывает доступные инструменты",
                setOf(AgentCapability.ANALYZE_DATA),
                ToolInputSchema(listOf(ToolInputField("query", ToolInputType.STRING, required = false)))
            )
        )
        var called = false
        val executor = AgentToolExecutor(
            registry,
            mapOf(SheVAgentAdkTools.TOOL_ID to AgentTool {
                called = true
                AgentToolResult(true, "should not run")
            })
        )
        val adapter = SheVAgentAdkTools(
            executor = executor,
            executionContext = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA)),
            agentAllowedToolIds = emptySet()
        )

        val result = adapter.inspectAvailableTools()

        assertEquals("denied", result["status"])
        assertEquals("AGENT_ALLOWLIST_DENIED", result["reason"])
        assertFalse(called)
    }
}
