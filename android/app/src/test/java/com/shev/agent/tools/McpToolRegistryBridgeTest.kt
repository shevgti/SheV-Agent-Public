package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.core.McpAuthenticationKind
import com.shev.agent.core.McpInvocationAuthorizationProvider
import com.shev.agent.core.McpInvocationAuthorizationVerifier
import com.shev.agent.core.McpServerProfile
import com.shev.agent.core.McpServerTrust
import com.shev.agent.core.RemoteMcpClient
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class McpToolRegistryBridgeTest {
    @Test fun onlyExplicitlyApprovedToolsAreProjected() {
        val profile = McpServerProfile(
            id = "test-server",
            displayName = "Test server",
            endpoint = "https://example.org/mcp",
            authentication = McpAuthenticationKind.NONE,
            trust = McpServerTrust.USER_HOSTED,
            enabled = true,
            approvedToolNames = setOf("search_docs")
        )
        val client = RemoteMcpClient(profile) { null }
        val registry = AgentToolRegistry()
        val schema = ToolSchema(
            properties = Json.parseToJsonElement(
                """{"query":{"type":"string","maxLength":100}}"""
            ) as JsonObject,
            required = listOf("query")
        )
        val result = Mc.shevBridge(profile, client, registry, schema)
        assertEquals(listOf("test-server.search_docs"), result.registeredToolIds)
        assertEquals(listOf("delete_everything"), result.rejectedToolNames)
        val descriptor = registry.find("test-server.search_docs")
        assertNotNull(descriptor)
        assertEquals(setOf(AgentCapability.REMOTE_MCP_OPERATION), descriptor!!.capabilities)
        assertTrue(result.suspendingHandlers.containsKey("test-server.search_docs"))
    }

    @Test fun disabledProfileCannotRegisterTools() {
        val profile = McpServerProfile(
            id = "test-server",
            displayName = "Test server",
            endpoint = "https://example.org/mcp",
            authentication = McpAuthenticationKind.NONE,
            trust = McpServerTrust.USER_HOSTED,
            enabled = false,
            approvedToolNames = setOf("search_docs")
        )
        val client = RemoteMcpClient(profile) { null }
        val bridge = newBridge(profile, client)
        try {
            bridge.register(emptyList(), AgentToolRegistry())
            fail("Expected disabled profile to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun newBridge(
        profile: McpServerProfile,
        client: RemoteMcpClient
    ) = McpToolRegistryBridge(
        profile = profile,
        remoteClient = client,
        authorizationProvider = McpInvocationAuthorizationProvider { null },
        authorizationVerifier = McpInvocationAuthorizationVerifier { _, _, _ -> false }
    )

    private object Mc {
        fun shevBridge(
            profile: McpServerProfile,
            client: RemoteMcpClient,
            registry: AgentToolRegistry,
            schema: ToolSchema
        ) = McpToolRegistryBridge(profile, client).register(
            listOf(
                RemoteMcpClient.DiscoveredTool("search_docs", "Search docs", schema),
                RemoteMcpClient.DiscoveredTool("delete_everything", "Unapproved tool", schema)
            ),
            registry
        )
    }
}
