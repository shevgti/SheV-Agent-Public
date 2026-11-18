package com.shev.agent.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class McpToolSchemaAdapterTest {
    private fun schema(value: String) = ToolSchema(
        properties = Json.parseToJsonElement(value) as JsonObject,
        required = listOf("query")
    )

    @Test fun mapsSupportedStringIntegerAndBooleanFields() {
        val adapted = McpToolSchemaAdapter.adapt(
            ToolSchema(
                properties = Json.parseToJsonElement(
                    """{"query":{"type":"string","maxLength":300},"limit":{"type":"integer","minimum":1,"maximum":20},"fresh":{"type":"boolean"}}"""
                ) as JsonObject,
                required = listOf("query")
            )
        )
        assertNotNull(adapted)
        assertTrue(adapted!!.validate("""{"query":"MCP","limit":10,"fresh":true}""").accepted)
        assertFalse(adapted.validate("""{"query":"MCP","limit":21,"fresh":true}""").accepted)
        assertFalse(adapted.validate("""{"query":"MCP","limit":10,"fresh":"yes"}""").accepted)
    }

    @Test fun mapsStringEnums() {
        val adapted = McpToolSchemaAdapter.adapt(
            ToolSchema(
                properties = Json.parseToJsonElement(
                    """{"query":{"type":"string","enum":["docs","api"]}}"""
                ) as JsonObject,
                required = listOf("query")
            )
        )
        assertNotNull(adapted)
        assertTrue(adapted!!.validate("""{"query":"docs"}""").accepted)
        assertFalse(adapted.validate("""{"query":"shell"}""").accepted)
    }

    @Test fun rejectsNestedObjectsArraysAndUnknownKeywords() {
        val nested = schema("""{"query":{"type":"object","properties":{"x":{"type":"string"}}}}""")
        val array = schema("""{"query":{"type":"array","items":{"type":"string"}}}""")
        val pattern = schema("""{"query":{"type":"string","pattern":"^safe"}}""")
        assertNull(McpToolSchemaAdapter.adapt(nested))
        assertNull(McpToolSchemaAdapter.adapt(array))
        assertNull(McpToolSchemaAdapter.adapt(pattern))
    }

    @Test fun rejectsReferencesAndEmptyProperties() {
        assertNull(McpToolSchemaAdapter.adapt(
            ToolSchema(
                properties = Json.parseToJsonElement("""{"query":{"type":"string"}}""") as JsonObject,
                defs = Json.parseToJsonElement("""{"Query":{"type":"string"}}""") as JsonObject
            )
        ))
        assertNull(McpToolSchemaAdapter.adapt(ToolSchema(properties = JsonObject(emptyMap()))))
    }

    @Test fun rejectsRequiredNamesNotInProperties() {
        val result = McpToolSchemaAdapter.adapt(
            ToolSchema(
                properties = Json.parseToJsonElement("""{"query":{"type":"string"}}""") as JsonObject,
                required = listOf("missing")
            )
        )
        assertNull(result)
    }
}
