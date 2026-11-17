package com.shev.agent.tools

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.shev.agent.AgentCapability
import com.shev.agent.core.McpServerProfile
import com.shev.agent.core.RemoteMcpClient
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Converts only a deliberately small JSON Schema subset into SheV's strict
 * input contract. Unknown keywords/types fail closed; no MCP schema is trusted
 * merely because the remote server advertises it.
 */
object McpToolSchemaAdapter {
    private val rootKeywords = setOf("properties", "required")
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
            if (name !in properties.keys || element !is JsonObject) return null
            if (element.keys.any { it !in fieldKeywords }) return null
            val type = (element["type"] as? JsonPrimitive)?.contentOrNull ?: return null
            val isRequired = name in required
            val enumValues = (element["enum"] as? JsonArray)?.map { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            }?.toSet().orEmpty()
            val field = when (type) {
                "string" -> {
                    if (element.keys.any { it in setOf("minimum", "maximum") }) return null
                    val maxLength = (element["maxLength"] as? JsonPrimitive)?.intOrNull ?: 2048
                    if (maxLength !in 1..8192) return null
                    ToolInputField(
                        name = name,
                        type = ToolInputType.STRING,
                        required = isRequired,
                        maxLength = maxLength,
                        allowedValues = enumValues
                    )
                }
                "integer" -> {
                    if (element.containsKey("maxLength") || enumValues.isNotEmpty()) return null
                    val min = (element["minimum"] as? JsonPrimitive)?.longOrNull
                    val max = (element["maximum"] as? JsonPrimitive)?.longOrNull
                    if (element.containsKey("minimum") && min == null) return null
                    if (element.containsKey("maximum") && max == null) return null
                    ToolInputField(
                        name = name,
                        type = ToolInputType.INTEGER,
                        required = isRequired,
                        minInteger = min,
                        maxInteger = max
                    )
                }
                "boolean" -> {
                    if (element.keys.any { it in setOf("maxLength", "minimum", "maximum", "enum") }) return null
                    ToolInputField(name, ToolInputType.BOOLEAN, required = isRequired)
                }
                else -> return null
            }
            fields += field
        }
        return runCatching { ToolInputSchema(fields) }.getOrNull()
    }
}

/**
 * Projects reviewed remote tools into the existing native tool registry.
 * Every projected tool is READ_REMOTE_DATA and therefore requires an explicit
 * per-action approval under CapabilityPolicy. MCP annotations are intentionally
 * ignored: they are hints from an untrusted server, not authorization evidence.
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
            if (!tool.name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}"))) {
                rejected += tool.name
                return@forEach
            }
            if (tool.name !in profile.approvedToolNames) {
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
                val parsed = JsonParser.parseString(input).asJsonObject
                val args = parsed.entrySet().associate { (key, value) ->
                    key to Gson().fromJson<Any?>(value, Any::class.java)
                }
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
