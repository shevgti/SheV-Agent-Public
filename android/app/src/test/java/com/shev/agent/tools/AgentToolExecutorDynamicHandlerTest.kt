package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.core.McpInvocationExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolExecutorDynamicHandlerTest {
    @Test fun dynamicallyRegisteredRemoteHandlerExecutesOnlyThroughDurableGate() = runBlocking {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "mcp.context7.search",
                "Context7 search",
                "Remote read",
                setOf(AgentCapability.REMOTE_MCP_OPERATION),
                ToolInputSchema(listOf(ToolInputField("query", ToolInputType.STRING)))
            )
        )
        val executor = AgentToolExecutor(registry, emptyMap())
        val expected = McpInvocationExecutionContext(
            "invocation-000001", "task-1", 1, 0, "step-1", 0
        )
        var called = false
        val handler = McpAuthorizedContextualSuspendingTool(ContextualSuspendingAgentTool { input, context ->
            called = context.mcpInvocationContext == expected
            AgentToolResult(true, input)
        })
        assertTrue(executor.registerMcpHandler("mcp.context7.search", handler))
        val result = executor.executeSuspending(
            AgentToolRequest(
                "mcp.context7.search",
                AgentCapability.REMOTE_MCP_OPERATION,
                """{"query":"kotlin"}"""
            ),
            AgentToolExecutionContext(
                grantedCapabilities = setOf(AgentCapability.REMOTE_MCP_OPERATION),
                mcpInvocationContext = expected,
                beforeMcpNetworkCall = { it == expected }
            )
        )
        assertEquals(AgentToolExecutionStatus.COMPLETED, result.status)
        assertTrue(called)
    }

    @Test fun duplicateDynamicHandlerRegistrationIsRejected() {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "mcp.context7.search", "Search", "Remote",
                setOf(AgentCapability.REMOTE_MCP_OPERATION),
                ToolInputSchema(listOf(ToolInputField("query", ToolInputType.STRING)))
            )
        )
        val executor = AgentToolExecutor(registry, emptyMap())
        val handler = McpAuthorizedContextualSuspendingTool(ContextualSuspendingAgentTool { input, _ -> AgentToolResult(true, input) }
        assertTrue(executor.registerMcpHandler("mcp.context7.search", handler))
        assertFalse(executor.registerMcpHandler("mcp.context7.search", handler))
    }

    @Test fun deactivationRemovesDynamicHandlerAndFailsClosed() = runBlocking {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "mcp.context7.search", "Search", "Remote",
                setOf(AgentCapability.REMOTE_MCP_OPERATION),
                ToolInputSchema(listOf(ToolInputField("query", ToolInputType.STRING)))
            )
        )
        val executor = AgentToolExecutor(registry, emptyMap())
        val handler = McpAuthorizedContextualSuspendingTool(ContextualSuspendingAgentTool { input, _ -> AgentToolResult(true, input) }
        executor.registerMcpHandler("mcp.context7.search", handler)
        assertTrue(executor.unregisterMcpHandler("mcp.context7.search"))
        val context = McpInvocationExecutionContext("invocation-000001", "task-1", 1, 0, "step-1", 0)
        val result = executor.executeSuspending(
            AgentToolRequest("mcp.context7.search", AgentCapability.REMOTE_MCP_OPERATION, """{"query":"x"}"""),
            AgentToolExecutionContext(
                grantedCapabilities = setOf(AgentCapability.REMOTE_MCP_OPERATION),
                mcpInvocationContext = context,
                beforeMcpNetworkCall = { true }
            )
        )
        assertEquals(AgentToolExecutionStatus.ACCESS_DENIED, result.status)
    }

    @Test fun dynamicMcpHandlerCannotBeAttachedToNonMcpTool() {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "local.search", "Search", "Local",
                setOf(AgentCapability.ANALYZE_DATA),
                ToolInputSchema(listOf(ToolInputField("query", ToolInputType.STRING)))
            )
        )
        val executor = AgentToolExecutor(registry, emptyMap())
        assertFalse(executor.registerMcpHandler(
            "local.search",
            ContextualSuspendingAgentTool { input, _ -> AgentToolResult(true, input) }
        ))
    }
}
