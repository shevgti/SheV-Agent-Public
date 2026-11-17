package com.shev.agent.tools

import com.shev.agent.core.CuratedMcpProfiles
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
        var credentialLookups = 0
        val credentials = McpCredentialSource {
            credentialLookups += 1
            null
        }
        val registry = AgentToolRegistry()
        val executor = AgentToolExecutor(registry, emptyMap())
        val manager = McpConnectionManager(
            registry = registry,
            executor = executor,
            credentials = credentials,
            authorizationProvider = McpInvocationAuthorizationProvider { null },
            authorizationVerifier = McpInvocationAuthorizationVerifier { _, _, _ -> false }
        )
        val profile = CuratedMcpProfiles.entries.first { it.id == "context7" }
            .copy(enabled = false, approvedToolNames = setOf("resolve-library-id"))

        val result = manager.connect(profile)
        assertEquals(McpConnectionStatus.PROFILE_DISABLED, result.status)
        assertEquals(0, credentialLookups)
        assertTrue(manager.connectedProfileIds().isEmpty())
        assertFalse(manager.disconnect("context7"))
    }
}
