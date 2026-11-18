package com.shev.agent.core

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fingerprint of the advertised remote tool contract. The SDK serializer covers
 * the complete serializable Tool definition, while normalization makes JSON
 * object key order and the set-like JSON Schema "required" array deterministic.
 */
object McpToolDefinitionFingerprint {
    private val sdkJson = Json { encodeDefaults = true }

    /** Includes output schema, annotations, icons, execution and _meta when exposed by the SDK. */
    fun canonicalSdkToolJson(tool: Tool): String {
        val serialized = sdkJson.encodeToJsonElement(Tool.serializer(), tool)
        return normalize(serialized).toString()
    }

    fun computeSdkTool(tool: Tool): String =
        sha256(canonicalSdkToolJson(tool))

    fun compute(
        name: String,
        description: String,
        inputSchema: ToolSchema,
        additionalMetadata: String = ""
    ): String {
        val schema = JsonObject(
            mapOf(
                "properties" to (inputSchema.properties?.let(::normalize) ?: JsonObject(emptyMap())),
                "required" to JsonArray(inputSchema.required.orEmpty().sorted().map { JsonPrimitive(it) }),
                "defs" to (inputSchema.defs?.let(::normalize) ?: JsonObject(emptyMap()))
            )
        )
        val definition = JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "description" to JsonPrimitive(description),
                "inputSchema" to schema,
                "sdkMetadata" to JsonPrimitive(additionalMetadata)
            )
        )
        return sha256(normalize(definition).toString())
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun normalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.entries.sortedBy { it.key }.associate { (key, nested) ->
                val normalized = normalize(nested)
                // JSON Schema "required" is a set of property names, not an ordered list.
                val canonical = if (key == "required" && normalized is JsonArray) {
                    JsonArray(normalized.sortedBy { it.toString() })
                } else {
                    normalized
                }
                key to canonical
            }
        )
        is JsonArray -> JsonArray(value.map(::normalize))
        else -> value
    }
}
