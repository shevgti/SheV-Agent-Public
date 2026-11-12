package com.shev.agent.creator

import com.google.gson.Gson
import com.google.gson.JsonParser

object AgentManifestParser {
    fun parse(modelOutput: String): AgentManifest {
        val trimmed = modelOutput.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "Модель не вернула JSON-паспорт агента." }
        val json = trimmed.substring(start, end + 1)
        val parsed = JsonParser.parseString(json)
        require(parsed.isJsonObject) { "Паспорт должен быть JSON-объектом." }
        val obj = parsed.asJsonObject
        // Backward-compatible defaults for drafts created before the Anthropic file format was added.
        if (!obj.has("description") || obj.get("description").isJsonNull) obj.addProperty("description", "")
        if (!obj.has("model") || obj.get("model").isJsonNull) obj.addProperty("model", "inherit")
        if (!obj.has("color") || obj.get("color").isJsonNull) obj.addProperty("color", "magenta")
        if (!obj.has("triggeringExamples") || !obj.get("triggeringExamples").isJsonArray) obj.add("triggeringExamples", com.google.gson.JsonArray())
        if (!obj.has("clarifyingQuestions") || !obj.get("clarifyingQuestions").isJsonArray) obj.add("clarifyingQuestions", com.google.gson.JsonArray())
        if (!obj.has("claudeCodeTools") || !obj.get("claudeCodeTools").isJsonArray) obj.add("claudeCodeTools", com.google.gson.JsonArray())
        if (!obj.has("process") || obj.get("process").isJsonNull) obj.addProperty("process", "")
        if (!obj.has("outputFormat") || obj.get("outputFormat").isJsonNull) obj.addProperty("outputFormat", "")
        return Gson().fromJson(obj, AgentManifest::class.java)
            ?: throw IllegalArgumentException("Пустой паспорт агента.")
    }
}
