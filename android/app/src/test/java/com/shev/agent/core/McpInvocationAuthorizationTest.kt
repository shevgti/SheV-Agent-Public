package com.shev.agent.core

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    fun toolDefinitionFingerprintChangesWhenInputSchemaChanges() {
        val firstSchema = ToolSchema(
            properties = Json.parseToJsonElement(
                """{"query":{"type":"string","maxLength":100}}"""
            ) as JsonObject,
            required = listOf("query")
        )
        val changedSchema = ToolSchema(
            properties = Json.parseToJsonElement(
                """{"query":{"type":"string","maxLength":200}}"""
            ) as JsonObject,
            required = listOf("query")
        )
        val first = McpToolDefinitionFingerprint.compute(
            "search_docs", "Search documentation", firstSchema, "sdk-tool-v1"
        )
        val changed = McpToolDefinitionFingerprint.compute(
            "search_docs", "Search documentation", changedSchema, "sdk-tool-v1"
        )
        assertNotEquals(first, changed)
    }

    @Test
    fun argumentDigestIsIndependentOfObjectKeyOrder() {
        val first = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            "d".repeat(64),
            linkedMapOf("query" to "kotlin", "limit" to 5L)
        )
        val second = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            "d".repeat(64),
            linkedMapOf("limit" to 5L, "query" to "kotlin")
        )
        assertEquals(first.argumentsSha256, second.argumentsSha256)
    }

    @Test
    fun argumentDigestChangesWhenAnyArgumentChanges() {
        val first = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            "d".repeat(64),
            mapOf("query" to "kotlin")
        )
        val changed = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            "d".repeat(64),
            mapOf("query" to "android")
        )
        assertNotEquals(first.argumentsSha256, changed.argumentsSha256)
    }

    @Test
    fun sdkFingerprintIncludesOutputSchemaAndAnnotations() {
        val inputSchema = ToolSchema(
            properties = Json.parseToJsonElement("""{"query":{"type":"string"}}""") as JsonObject,
            required = listOf("query")
        )
        val outputA = ToolSchema(
            properties = Json.parseToJsonElement("""{"count":{"type":"integer"}}""") as JsonObject
        )
        val outputB = ToolSchema(
            properties = Json.parseToJsonElement("""{"items":{"type":"array"}}""") as JsonObject
        )
        val original = Tool(
            name = "search_docs",
            inputSchema = inputSchema,
            description = "Search docs",
            outputSchema = outputA,
            annotations = ToolAnnotations(readOnlyHint = true)
        )
        val changed = original.copy(
            outputSchema = outputB,
            annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true)
        )
        assertNotEquals(
            McpToolDefinitionFingerprint.computeSdkTool(original),
            McpToolDefinitionFingerprint.computeSdkTool(changed)
        )
    }

    @Test
    fun bindingChangesWhenRemoteToolDefinitionChanges() {
        val original = McpInvocationBinding.create(
            profile,
            "test-server.search_docs",
            "search_docs",
            "d".repeat(64),
            mapOf("query" to "kotlin")
        )
        val changedDefinition = McpInvocationBinding.create(
            profile,
            "test-server.search_docs",
            "search_docs",
            "e".repeat(64),
            mapOf("query" to "kotlin")
        )
        assertNotEquals(original, changedDefinition)
        assertNotEquals(original.toolDefinitionSha256, changedDefinition.toolDefinitionSha256)
    }

    @Test
    fun bindingIncludesProfileEndpointAndToolIdentity() {
        val original = McpInvocationBinding.create(
            profile, "test-server.search_docs", "search_docs",
            "d".repeat(64),
            mapOf("query" to "kotlin")
        )
        val otherProfile = McpInvocationBinding.create(
            profile.copy(id = "other-server"),
            "other-server.search_docs", "search_docs",
            "d".repeat(64),
            mapOf("query" to "kotlin")
        )
        val otherTool = McpInvocationBinding.create(
            profile, "test-server.other_tool", "other_tool",
            "d".repeat(64),
            mapOf("query" to "kotlin")
        )
        assertNotEquals(original, otherProfile)
        assertNotEquals(original, otherTool)
    }
}
