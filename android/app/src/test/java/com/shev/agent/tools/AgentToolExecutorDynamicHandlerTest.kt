package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.core.McpInvocationExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolExecutorDynamicHandlerTest {
    private fun registry() = AgentToolRegistry().apply {
        register(
            AgentToolDescriptor(
                "mcp.context7.search",
                "Context7 search",
                "Remote read",
                setOf(AgentCapability.REMOTE_MCP_OPERATION),
                ToolInputSchema(listOf(ToolInputField("query", ToolInputType.STRING)))
            )
        )
    }

    private fun context() = McpInvocationExecutionContext(
        "invocation-000001", "task-1", 1, 0, "step-1", 0
    )

    private fun wrapper(
        block: suspend (String, AgentToolExecutionContext) -> AgentToolResult
    ) = McpAuthorizedContextualSuspendingTool(ContextualSuspendingAgentTool(block))

    @Test fun dynamicallyRegisteredRemoteHandlerExecutesOnlyWithDurableContext() = runBlocking {
        val registry = registry()
        val executor = AgentToolExecutor(registry, emptyMap())
        val expected = context()
        var called = false
        val handler = wrapper { input, executionContext ->
            called = executionContext.mcpInvocationContext == expected
            AgentToolResult(true, input)
        }
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
        val executor = AgentToolExecutor(registry(), emptyMap())
        val handler = wrapper { input, _ -> AgentToolResult(true, input) }
        assertTrue(executor.registerMcpHandler("mcp.context7.search", handler))
        assertFalse(executor.registerMcpHandler("mcp.context7.search", handler))
    }

    @Test fun deactivationRemovesDynamicHandlerAndFailsClosed() = runBlocking {
        val registry = registry()
        val executor = AgentToolExecutor(registry, emptyMap())
        val handler = wrapper { input, _ -> AgentToolResult(true, input) }
        assertTrue(executor.registerMcpHandler("mcp.context7.search", handler))
        assertTrue(executor.unregisterMcpHandler("mcp.context7.search"))
        val expected = context()
        val result = executor.executeSuspending(
            AgentToolRequest(
                "mcp.context7.search",
                AgentCapability.REMOTE_MCP_OPERATION,
                """{"query":"x"}"""
            ),
            AgentToolExecutionContext(
                grantedCapabilities = setOf(AgentCapability.REMOTE_MCP_OPERATION),
                mcpInvocationContext = expected,
                beforeMcpNetworkCall = { true }
            )
        )
        assertEquals(AgentToolExecutionStatus.ACCESS_DENIED, result.status)
    }

    @Test fun dynamicMcpHandlerCannotBeAttachedToNonMcpTool() {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "local.search",
                "Search",
                "Local",
                setOf(AgentCapability.ANALYZE_DATA),
                ToolInputSchema(listOf(ToolInputField("query", ToolInputType.STRING)))
            )
        )
        val executor = AgentToolExecutor(registry, emptyMap())
        val handler = wrapper { input, _ -> AgentToolResult(true, input) }
        assertFalse(executor.registerMcpHandler("local.search", handler))
    }
}
