package com.shev.agent.core

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fingerprint of the advertised remote tool contract. The extra metadata is the
 * SDK tool representation so changes to fields outside the currently adapted
 * input-schema subset also force re-approval where the SDK exposes them.
 */
object McpToolDefinitionFingerprint {
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
        val canonical = normalize(definition).toString()
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun normalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.entries.sortedBy { it.key }.associate { (key, nested) -> key to normalize(nested) }
        )
        is JsonArray -> JsonArray(value.map(::normalize))
        else -> value
    }
}
