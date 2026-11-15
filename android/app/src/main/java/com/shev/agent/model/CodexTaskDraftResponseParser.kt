package com.shev.agent.model

import com.google.gson.JsonParser
import com.shev.agent.AgentPlanJsonCodec

internal data class ParsedCodexTaskDraft(
    val title: String,
    val planJson: String
)

/** Strict parser and explicit-intent gate for ChatGPT/Codex task drafting. */
internal object CodexTaskDraftResponseParser {
    private val taskIntent = Regex(
        """(?iu)(?<![\p{L}\p{N}_])(?:сохрани|сохранить|создай|создать|добавь|добавить|поставь|поставить|оформи|оформить|сделай|сделать|запиши|записать)(?![\p{L}\p{N}_]).{0,64}(?<![\p{L}\p{N}_])(?:поручение|задачу|задание)(?![\p{L}\p{N}_])"""
    )
    private val negatedIntent = Regex(
        """(?iu)(?<![\p{L}\p{N}_])(?:не надо|не нужно|не хочу|не просил(?:а|и)?)(?![\p{L}\p{N}_]).{0,64}(?<![\p{L}\p{N}_])(?:сохранять|создавать|добавлять|поручение|задачу|задание)(?![\p{L}\p{N}_])"""
    )
    private val educationalIntent = Regex(
        """(?iu)(?<![\p{L}\p{N}_])(?:как|подскажи,?\s*как|расскажи,?\s*как|покажи,?\s*как)\s+(?:сохранить|создать|добавить|поставить|оформить)(?![\p{L}\p{N}_])"""
    )

    fun isExplicitTaskDraftRequest(message: String): Boolean =
        taskIntent.containsMatchIn(message) &&
            !negatedIntent.containsMatchIn(message) &&
            !educationalIntent.containsMatchIn(message)

    fun parse(response: String): ParsedCodexTaskDraft? = runCatching {
        val normalized = response.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = normalized.indexOf('{')
        val end = normalized.lastIndexOf('}')
        require(start >= 0 && end > start)
        val root = JsonParser.parseString(normalized.substring(start, end + 1)).asJsonObject
        require(root.keySet() == setOf("title", "plan"))
        val titleElement = root.get("title")
        require(titleElement.isJsonPrimitive && titleElement.asJsonPrimitive.isString)
        val title = titleElement.asString.trim()
        require(title.isNotBlank() && title.length <= 2_000)
        val planJson = AgentPlanJsonCodec.canonicalize(root.get("plan").toString())
            ?: error("План не соответствует строгой схеме.")
        ParsedCodexTaskDraft(title, planJson)
    }.getOrNull()
}
