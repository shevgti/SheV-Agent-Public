package com.shev.agent.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.security.MessageDigest

/**
 * Immutable identity of one exact MCP side effect.
 *
 * The digest is computed from recursively key-sorted JSON arguments. Endpoint and
 * profile identity are included so a token cannot be moved to another server.
 */
data class McpInvocationBinding(
    val profileId: String,
    val endpoint: String,
    val nativeToolId: String,
    val toolName: String,
    val argumentsSha256: String
) {
    init {
        require(profileId.isNotBlank() && endpoint.isNotBlank())
        require(nativeToolId.isNotBlank() && toolName.isNotBlank())
        require(argumentsSha256.matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        fun create(
            profile: McpServerProfile,
            nativeToolId: String,
            toolName: String,
            arguments: Map<String, Any?>
        ): McpInvocationBinding = McpInvocationBinding(
            profileId = profile.id,
            endpoint = profile.endpoint,
            nativeToolId = nativeToolId,
            toolName = toolName,
            argumentsSha256 = sha256(canonicalJson(arguments))
        )

        private fun canonicalJson(value: Any?): String =
            GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(normalize(value))

        private fun normalize(value: Any?): JsonElement = when (value) {
            null -> JsonNull.INSTANCE
            is JsonElement -> normalizeElement(value)
            is Map<*, *> -> JsonObject().apply {
                value.entries
                    .mapNotNull { entry -> (entry.key as? String)?.let { it to entry.value } }
                    .sortedBy { it.first }
                    .forEach { (key, nested) -> add(key, normalize(nested)) }
            }
            is Iterable<*> -> JsonArray().apply { value.forEach { add(normalize(it)) } }
            is Array<*> -> JsonArray().apply { value.forEach { add(normalize(it)) } }
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> throw IllegalArgumentException("Unsupported MCP argument value type")
        }

        private fun normalizeElement(value: JsonElement): JsonElement = when {
            value.isJsonNull -> JsonNull.INSTANCE
            value.isJsonObject -> normalize(value.asJsonObject.entrySet().associate { it.key to it.value })
            value.isJsonArray -> normalize(value.asJsonArray.map(::normalizeElement))
            value.asJsonPrimitive.isString -> JsonPrimitive(value.asString)
            value.asJsonPrimitive.isBoolean -> JsonPrimitive(value.asBoolean)
            else -> JsonPrimitive(value.asJsonPrimitive.asNumber)
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/**
 * Opaque, short-lived approval issued by the trusted task/kernel layer only after
 * the user approves the exact rendered action. The nonce itself is never persisted.
 */
data class McpInvocationAuthorization(
    val invocationId: String,
    val taskId: String,
    val taskRevision: Long,
    val stepId: String,
    val stepRevision: Long,
    val binding: McpInvocationBinding,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val nonce: String
) {
    init {
        require(invocationId.matches(Regex("[A-Za-z0-9_-]{16,80}")))
        require(taskId.isNotBlank() && taskRevision >= 0L)
        require(stepId.matches(Regex("[A-Za-z0-9_-]{1,64}")) && stepRevision >= 0L)
        require(issuedAtEpochMillis >= 0L && expiresAtEpochMillis > issuedAtEpochMillis)
        require(expiresAtEpochMillis - issuedAtEpochMillis <= MAX_TTL_MILLIS)
        require(nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    companion object {
        const val MAX_TTL_MILLIS = 120_000L
    }
}

/** Trusted provider must derive task/step identity from persisted kernel state. */
fun interface McpInvocationAuthorizationProvider {
    suspend fun issue(binding: McpInvocationBinding): McpInvocationAuthorization?
}

/**
 * Must validate the persisted nonce/binding and atomically mark it consumed.
 * Implementations must fail closed; a successful check without atomic consumption
 * is not a valid implementation.
 */
fun interface McpInvocationAuthorizationVerifier {
    suspend fun consume(
        authorization: McpInvocationAuthorization,
        expectedBinding: McpInvocationBinding,
        nowMillis: Long
    ): Boolean
}
