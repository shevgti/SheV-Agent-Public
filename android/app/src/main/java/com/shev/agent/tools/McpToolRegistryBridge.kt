package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.core.McpServerProfile
import com.shev.agent.core.RemoteMcpClient
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Converts only a deliberately small JSON Schema subset into SheV's strict
 * input contract. Unknown keywords/types fail closed; no MCP schema is trusted
 * merely because the remote server advertises it.
 */
object McpToolSchemaAdapter {
    private val fieldKeywords = setOf(
        "type", "description", "title", "maxLength", "enum", "minimum", "maximum"
    )

    fun adapt(schema: ToolSchema): ToolInputSchema? {
        if (schema.defs?.isNotEmpty() == true) return null
        val properties = schema.properties ?: return null
        if (properties.isEmpty()) return null
        val required = schema.required.orEmpty().toSet()
        if (required.any { it !in properties.keys }) return null
        val fields = mutableListOf<ToolInputField>()
        for ((name, element) in properties) {
            if (element !is JsonObject || element.keys.any { it !in fieldKeywords }) return null
            val type = (element["type"] as? JsonPrimitive)?.contentOrNull ?: return null
            val enumValues = (element["enum"] as? JsonArray)?.map { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            }?.toSet().orEmpty()
            val field = when (type) {
                "string" -> {
                    if (element.containsKey("minimum") || element.containsKey("maximum")) return null
                    val maxLength = (element["maxLength"] as? JsonPrimitive)?.intOrNull ?: 2048
                    if (maxLength !in 1..8192) return null
                    ToolInputField(name, ToolInputType.STRING, name in required, maxLength, enumValues)
                }
                "integer" -> {
                    if (element.containsKey("maxLength") || enumValues.isNotEmpty()) return null
                    val min = (element["minimum"] as? JsonPrimitive)?.longOrNull
                    val max = (element["maximum"] as? JsonPrimitive)?.longOrNull
                    if (element.containsKey("minimum") && min == null) return null
                    if (element.containsKey("maximum") && max == null) return null
                    ToolInputField(
                        name, ToolInputType.INTEGER, name in required,
                        minInteger = min, maxInteger = max
                    )
                }
                "boolean" -> {
                    if (element.keys.any { it in setOf("maxLength", "minimum", "maximum", "enum") }) return null
                    ToolInputField(name, ToolInputType.BOOLEAN, name in required)
                }
                else -> return null
            }
            fields += field
        }
        return runCatching { ToolInputSchema(fields) }.getOrNull()
    }
}

private fun JsonElement.toNativeValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> entries.associate { (key, value) -> key to value.toNativeValue() }
    is JsonArray -> map { it.toNativeValue() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
}

/**
 * Projects explicitly approved remote tools into the native registry.
 * Every projected tool is READ_REMOTE_DATA and therefore requires an explicit
 * action approval under CapabilityPolicy. Remote annotations are ignored:
 * hints from an untrusted server are never authorization evidence.
 */
class McpToolRegistryBridge(
    private val profile: McpServerProfile,
    private val remoteClient: RemoteMcpClient
) {
    data class Registration(
        val registeredToolIds: List<String>,
        val rejectedToolNames: List<String>,
        val suspendingHandlers: Map<String, SuspendingAgentTool>
    )

    fun register(
        discovered: List<RemoteMcpClient.DiscoveredTool>,
        registry: AgentToolRegistry
    ): Registration {
        require(profile.enabled) { "MCP profile is disabled" }
        val handlers = linkedMapOf<String, SuspendingAgentTool>()
        val registered = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        discovered.take(RemoteMcpClient.MAX_DISCOVERED_TOOLS).forEach { tool ->
            if (!tool.name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")) ||
                tool.name !in profile.approvedToolNames) {
                rejected += tool.name
                return@forEach
            }
            val schema = McpToolSchemaAdapter.adapt(tool.inputSchema)
            if (schema == null) {
                rejected += tool.name
                return@forEach
            }
            val id = "${profile.id}.${tool.name.lowercase().replace(Regex("[^a-z0-9_.-]"), "-")}"
            if (registry.find(id) != null) {
                rejected += tool.name
                return@forEach
            }
            registry.register(
                AgentToolDescriptor(
                    id = id,
                    title = tool.name.take(100),
                    description = tool.description.ifBlank { "Remote MCP tool: ${tool.name}" }.take(1_000),
                    capabilities = setOf(AgentCapability.READ_REMOTE_DATA),
                    inputSchema = schema
                )
            )
            handlers[id] = SuspendingAgentTool { input ->
                val parsed = Json.parseToJsonElement(input) as? JsonObject
                    ?: error("Validated MCP input must be a JSON object")
                val args = parsed.entries.associate { (key, value) -> key to value.toNativeValue() }
                val output = remoteClient.callApprovedTool(
                    toolName = tool.name,
                    arguments = args,
                    // AgentToolExecutor has already enforced the capability grant
                    // and explicit per-action approval before invoking this handler.
                    authorizeThroughKernel = { _, _ -> true }
                )
                AgentToolResult(true, output)
            }
            registered += id
        }
        return Registration(registered, rejected, handlers)
    }
}
