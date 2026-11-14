package com.shev.agent.creator

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser

object AgentManifestParser {
    fun parse(modelOutput: String): AgentManifest {
        val trimmed = modelOutput.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "Модель не вернула JSON-паспорт агента." }
        val json = trimmed.substring(start, end + 1)
        val parsed = runCatching { JsonParser.parseString(json) }
            .recoverCatching { JsonParser.parseString(escapeInvalidJsonStringEscapes(json)) }
            .getOrElse { throw IllegalArgumentException("Некорректный JSON паспорта: ${it.message}", it) }
        require(parsed.isJsonObject) { "Паспорт должен быть JSON-объектом." }
        val obj = parsed.asJsonObject

        // Accept native JSON Schema objects (preferred) and legacy JSON-encoded strings.
        normalizeSchemaField(obj, "inputSchema")
        normalizeSchemaField(obj, "outputSchema")

        if (!obj.has("status") || obj.get("status").isJsonNull) obj.addProperty("status", AgentManifest.STATUS_DRAFT)
        if (!obj.has("description") || obj.get("description").isJsonNull) obj.addProperty("description", "")
        if (!obj.has("model") || obj.get("model").isJsonNull) obj.addProperty("model", "inherit")
        if (!obj.has("color") || obj.get("color").isJsonNull) obj.addProperty("color", "magenta")
        if (!obj.has("triggeringExamples") || !obj.get("triggeringExamples").isJsonArray) obj.add("triggeringExamples", JsonArray())
        if (!obj.has("clarifyingQuestions") || !obj.get("clarifyingQuestions").isJsonArray) obj.add("clarifyingQuestions", JsonArray())
        if (!obj.has("claudeCodeTools") || !obj.get("claudeCodeTools").isJsonArray) obj.add("claudeCodeTools", JsonArray())
        if (!obj.has("process") || obj.get("process").isJsonNull) obj.addProperty("process", "")
        if (!obj.has("outputFormat") || obj.get("outputFormat").isJsonNull) obj.addProperty("outputFormat", "")
        return Gson().fromJson(obj, AgentManifest::class.java)
            ?: throw IllegalArgumentException("Пустой паспорт агента.")
    }

    private fun normalizeSchemaField(obj: com.google.gson.JsonObject, field: String) {
        val value = obj.get(field) ?: return
        if (value.isJsonObject) obj.addProperty(field, value.toString())
    }

    /** Repairs invalid backslash escapes inside JSON strings without changing valid escapes. */
    private fun escapeInvalidJsonStringEscapes(input: String): String {
        val out = StringBuilder(input.length + 16)
        var inString = false
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (!inString) {
                out.append(ch)
                if (ch == '"') inString = true
                i++
                continue
            }
            when {
                ch == '"' -> {
                    out.append(ch)
                    inString = false
                    i++
                }
                ch == '\\' -> {
                    if (i + 1 >= input.length) {
                        out.append("\\\\")
                        i++
                    } else {
                        val next = input[i + 1]
                        val simple = next in "\"\\/bfnrt"
                        val unicode = next == 'u' && i + 5 < input.length &&
                            input.substring(i + 2, i + 6).all { it in "0123456789abcdefABCDEF" }
                        if (simple || unicode) {
                            out.append(ch).append(next)
                            i += 2
                        } else {
                            out.append("\\\\").append(next)
                            i += 2
                        }
                    }
                }
                ch == '\n' -> { out.append("\\n"); i++ }
                ch == '\r' -> { out.append("\\r"); i++ }
                ch == '\t' -> { out.append("\\t"); i++ }
                ch.code < 0x20 -> { out.append("\\u%04x".format(ch.code)); i++ }
                else -> { out.append(ch); i++ }
            }
        }
        return out.toString()
    }
}
