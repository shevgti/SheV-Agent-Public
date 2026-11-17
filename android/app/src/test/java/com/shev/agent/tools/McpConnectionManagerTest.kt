package com.shev.agent.tools

import com.shev.agent.core.CuratedMcpProfiles
import com.shev.agent.data.McpProfileSource
import com.shev.agent.core.McpInvocationAuthorizationProvider
import com.shev.agent.core.McpInvocationAuthorizationVerifier
import com.shev.agent.security.McpCredentialSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectionManagerTest {
    @Test fun disabledProfileIsRejectedBeforeCredentialOrNetworkAccess() = runBlocking {
        val profile = CuratedMcpProfiles.entries.first { it.id == "context7" }
            .copy(enabled = false, approvedToolNames = setOf("resolve-library-id"))
        var credentialLookups = 0
        val credentials = McpCredentialSource { _ ->
            credentialLookups += 1
            null
        }
        val registry = AgentToolRegistry()
        val executor = AgentToolExecutor(registry, emptyMap())
        val manager = McpConnectionManager(
            registry = registry,
            executor = executor,
            credentials = credentials,
            profiles = McpProfileSource { id -> if (id == "context7") profile else null },
            authorizationProvider = McpInvocationAuthorizationProvider { _ -> null },
            authorizationVerifier = McpInvocationAuthorizationVerifier { _, _, _ -> false }
        )

        val result = manager.connect("context7")
        assertEquals(McpConnectionStatus.PROFILE_DISABLED, result.status)
        assertEquals(0, credentialLookups)
        assertTrue(manager.connectedProfileIds().isEmpty())
        assertFalse(manager.disconnect("context7"))
    }

    @Test fun unknownProfileIsRejectedBeforeCredentialLookup() = runBlocking {
        var credentialLookups = 0
        val registry = AgentToolRegistry()
        val manager = McpConnectionManager(
            registry = registry,
            executor = AgentToolExecutor(registry, emptyMap()),
            credentials = McpCredentialSource { _ -> credentialLookups += 1; null },
            profiles = McpProfileSource { null },
            authorizationProvider = McpInvocationAuthorizationProvider { _ -> null },
            authorizationVerifier = McpInvocationAuthorizationVerifier { _, _, _ -> false }
        )
        assertEquals(McpConnectionStatus.PROFILE_NOT_FOUND, manager.connect("missing").status)
        assertEquals(0, credentialLookups)
    }

}
