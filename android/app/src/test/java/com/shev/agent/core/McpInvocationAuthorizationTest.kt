package com.shev.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class McpInvocationAuthorizationTest {
    private val profile = McpServerProfile(
        id = "test-server",
        displayName = "Test server",
        endpoint = "https://example.org/mcp",
        authentication = McpAuthenticationKind.NONE,
        trust = McpServerTrust.USER_HOSTED,
        enabled = true,
        approvedToolNames = setOf("search_docs")
    )

    @Test
    fun argumentDigestIsIndependentOfObjectKeyOrder() {
        val first = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            linkedMapOf("query" to "kotlin", "limit" to 5L)
        )
        val second = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            linkedMapOf("limit" to 5L, "query" to "kotlin")
        )
        assertEquals(first.argumentsSha256, second.argumentsSha256)
    }

    @Test
    fun argumentDigestChangesWhenAnyArgumentChanges() {
        val first = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            mapOf("query" to "kotlin")
        )
        val changed = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            mapOf("query" to "android")
        )
        assertNotEquals(first.argumentsSha256, changed.argumentsSha256)
    }

    @Test
    fun bindingIncludesProfileEndpointAndToolIdentity() {
        val original = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            mapOf("query" to "kotlin")
        )
        val otherProfile = McpInvocationBinding.create(
            profile.copy(id = "other-server"),
            "other-server.search_docs", "search_docs",
            mapOf("query" to "kotlin")
        )
        val otherTool = McpInvocationBinding.create(
            profile, "test-server.other_tool", "other_tool",
            mapOf("query" to "kotlin")
        )
        assertNotEquals(original, otherProfile)
        assertNotEquals(original, otherTool)
    }
}
