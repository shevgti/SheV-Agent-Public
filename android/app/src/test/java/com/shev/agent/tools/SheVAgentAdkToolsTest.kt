package com.shev.agent.tools

import com.shev.agent.AgentCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SheVAgentAdkToolsTest {
    @Test fun scopedAdapterExposesOnlyRegisteredIdsAndDeniesOtherCalls() {
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
        registry.register(
            AgentToolDescriptor(
                SheVAgentAdkTools.CURRENT_TIME_TOOL_ID,
                "Время",
                "Читает время устройства",
                setOf(AgentCapability.ANALYZE_DATA),
                ToolInputSchema(listOf(ToolInputField("query", ToolInputType.STRING, required = true, maxLength = 8)))
            )
        )
        val executor = AgentToolExecutor(
            registry,
            mapOf(
                SheVAgentAdkTools.TOOL_ID to AgentTool { AgentToolResult(true, "catalog") },
                SheVAgentAdkTools.CURRENT_TIME_TOOL_ID to AgentTool { AgentToolResult(true, "time") }
            )
        )
        val host = SheVAgentAdkTools(
            executor,
            AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))
        )
        assertEquals(setOf(SheVAgentAdkTools.TOOL_ID, SheVAgentAdkTools.CURRENT_TIME_TOOL_ID), host.availableToolIds())

        val scoped = host.scopedTo(setOf(SheVAgentAdkTools.TOOL_ID))
        assertEquals("ok", scoped.inspectAvailableTools()["status"])
        assertEquals("unavailable", scoped.getCurrentLocalTime("now")["status"])
    }

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

    @Test fun adapterSnapshotsAllowlistSoLaterMutationCannotGrantNewTools() {
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
                AgentToolResult(true, "unexpected")
            })
        )
        val mutableGrants = mutableSetOf<String>()
        val adapter = SheVAgentAdkTools(
            executor = executor,
            executionContext = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA)),
            agentAllowedToolIds = mutableGrants
        )
        // Adding an ID after adapter creation must not grant a new tool to this session.
        mutableGrants.add(SheVAgentAdkTools.TOOL_ID)

        val result = adapter.inspectAvailableTools()

        assertEquals("denied", result["status"])
        assertEquals("AGENT_ALLOWLIST_DENIED", result["reason"])
        assertFalse(called)
    }

}
