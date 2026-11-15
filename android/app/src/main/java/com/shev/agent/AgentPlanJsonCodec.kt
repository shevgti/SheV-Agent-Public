package com.shev.agent

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest

/**
 * Strict JSON boundary for plans returned by a model.
 * Only the declared fields are accepted; plans remain inert data until explicit approval.
 */
object AgentPlanJsonCodec {
    private val gson = Gson()
    private val rootKeys = setOf("summary", "steps")
    private val stepKeys = setOf("id", "title", "toolId", "capability", "input")

    fun decode(json: String): AgentPlan? = runCatching {
        require(json.isNotBlank() && json.length <= MAX_PLAN_JSON_CHARS)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.keySet() == rootKeys)
        val summary = root.get("summary").takeIf { it.isJsonPrimitive }?.asString
            ?: error("Plan summary is missing.")
        require(summary.isNotBlank() && summary.length <= MAX_SUMMARY_CHARS)
        val sourceSteps = root.get("steps").asJsonArray
        require(sourceSteps.size() in 1..MAX_STEPS)
        val steps = sourceSteps.map { element ->
            val item = element.asJsonObject
            require(item.keySet() == stepKeys)
            val id = item.get("id").takeIf { it.isJsonPrimitive }?.asString ?: error("Step id is missing.")
            val title = item.get("title").takeIf { it.isJsonPrimitive }?.asString ?: error("Step title is missing.")
            val toolId = item.get("toolId").takeIf { it.isJsonPrimitive }?.asString ?: error("Tool id is missing.")
            val capabilityName = item.get("capability").takeIf { it.isJsonPrimitive }?.asString
                ?: error("Capability is missing.")
            val input = item.get("input").takeIf { it.isJsonPrimitive }?.asString ?: error("Tool input is missing.")
            require(id.matches(Regex("[a-zA-Z0-9_-]{1,48}")))
            require(title.isNotBlank() && title.length <= MAX_STEP_TITLE_CHARS)
            require(toolId.isNotBlank() && toolId.length <= MAX_TOOL_ID_CHARS)
            require(input.length <= MAX_STEP_INPUT_CHARS)
            AgentPlanStep(
                id = id,
                title = title,
                toolId = toolId,
                capability = AgentCapability.valueOf(capabilityName),
                input = input
            )
        }
        require(steps.map { it.id }.toSet().size == steps.size)
        AgentPlan(summary, steps)
    }.getOrNull()

    fun canonicalJson(plan: AgentPlan): String = gson.toJson(plan)

    fun canonicalize(json: String): String? = decode(json)?.let(::canonicalJson)

    fun sha256(canonicalJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    const val MAX_PLAN_JSON_CHARS = 16_384
    const val MAX_SUMMARY_CHARS = 500
    const val MAX_STEP_TITLE_CHARS = 160
    const val MAX_TOOL_ID_CHARS = 128
    const val MAX_STEP_INPUT_CHARS = 8_192
    const val MAX_STEPS = 8
}
