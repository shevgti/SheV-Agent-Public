package com.shev.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class McpServerProfileTest {
    @Test
    fun curatedProfilesAreDisabledByDefault() {
        assertEquals(2, CuratedMcpProfiles.entries.size)
        assertTrue(CuratedMcpProfiles.entries.none { it.enabled })
    }

    @Test
    fun rejectsPlainHttpEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            McpServerProfile(
                id = "unsafe",
                displayName = "Unsafe",
                endpoint = "http://example.com/mcp",
                authentication = McpAuthenticationKind.NONE,
                trust = McpServerTrust.USER_HOSTED
            )
        }
    }

    @Test
    fun rejectsCredentialsEmbeddedInUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            McpServerProfile(
                id = "unsafe",
                displayName = "Unsafe",
                endpoint = "https://user:password@example.com/mcp",
                authentication = McpAuthenticationKind.BEARER_TOKEN,
                trust = McpServerTrust.USER_HOSTED
            )
        }
    }

    @Test
    fun discoveredToolMustBeExplicitlyApproved() {
        val profile = CuratedMcpProfiles.entries.first().copy(
            enabled = true,
            approvedToolNames = setOf("resolve-library-id", "query-docs")
        )
        assertTrue(McpToolAllowlist.isAllowed(profile, "query-docs"))
        assertFalse(McpToolAllowlist.isAllowed(profile, "delete_everything"))
    }

    @Test
    fun disabledProfileCannotCallEvenApprovedTool() {
        val profile = CuratedMcpProfiles.entries.first().copy(
            enabled = false,
            approvedToolNames = setOf("query-docs")
        )
        assertFalse(McpToolAllowlist.isAllowed(profile, "query-docs"))
    }
}
