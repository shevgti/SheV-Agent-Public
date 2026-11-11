package com.shev.agent.creator

import com.google.gson.JsonParser

class AgentManifestValidator(private val knownToolIds: Set<String>) {
    fun validate(
        manifest: AgentManifest,
        existingAgentIds: Set<String> = emptySet()
    ): AgentManifestValidation {
        if (!manifest.id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) ||
            manifest.id.length !in 2..64 || manifest.id in existingAgentIds
        ) return reject(AgentManifestRejection.INVALID_ID, "ID должен быть уникальным kebab-case.")
        if (manifest.name.isBlank() || manifest.name.length > 120)
            return reject(AgentManifestRejection.EMPTY_NAME)
        if (manifest.purpose.isBlank() || manifest.purpose.length > 1_200)
            return reject(AgentManifestRejection.EMPTY_PURPOSE)
        if (manifest.role.isBlank() || manifest.role.length > 240)
            return reject(AgentManifestRejection.EMPTY_ROLE)
        if (manifest.instructions.isBlank() || manifest.instructions.length > 16_000)
            return reject(AgentManifestRejection.EMPTY_INSTRUCTIONS)
        if (manifest.triggers.size !in 2..4)
            return reject(AgentManifestRejection.INVALID_TRIGGER_COUNT)
        if (manifest.triggers.any { it.isBlank() || it.length > 240 } ||
            manifest.triggers.distinct().size != manifest.triggers.size
        ) return reject(AgentManifestRejection.EMPTY_TRIGGER)
        if (manifest.inputSchema.length > 8_000 || manifest.outputSchema.length > 8_000 ||
            !isJsonSchemaObject(manifest.inputSchema) || !isJsonSchemaObject(manifest.outputSchema)
        ) return reject(AgentManifestRejection.EMPTY_SCHEMA, "Обе схемы должны быть JSON Schema object.")
        if (manifest.maxSteps !in 1..16 || manifest.timeoutSeconds !in 10..600 ||
            manifest.maxModelCalls !in 1..8 || manifest.allowedToolIds.size > 16
        ) return reject(AgentManifestRejection.INVALID_LIMITS)
        if (manifest.allowedToolIds.distinct().size != manifest.allowedToolIds.size)
            return reject(AgentManifestRejection.DUPLICATE_TOOL)
        val unknown = manifest.allowedToolIds.filterNot { it in knownToolIds }
        if (unknown.isNotEmpty())
            return reject(AgentManifestRejection.UNKNOWN_TOOL, unknown.joinToString())
        if (manifest.memoryReadScopes.isNotEmpty() || manifest.memoryWriteScopes.isNotEmpty())
            return reject(AgentManifestRejection.INVALID_MEMORY_SCOPE, "Доступ к памяти агентов пока не подключён.")
        if (manifest.networkPolicy != "OFFLINE" || manifest.sideEffectPolicy != "DRAFT_ONLY")
            return reject(AgentManifestRejection.UNSAFE_POLICY, "Разрешены только OFFLINE и DRAFT_ONLY.")
        if (manifest.verificationStrategy.isBlank())
            return reject(AgentManifestRejection.MISSING_VERIFICATION)
        if (manifest.evaluationCases.size !in 2..8 ||
            manifest.evaluationCases.any { it.isBlank() || it.length > 500 }
        ) return reject(AgentManifestRejection.MISSING_EVALUATION_CASES)
        if (manifest.version < 1) return reject(AgentManifestRejection.INVALID_VERSION)
        if (manifest.status != AgentManifest.STATUS_DRAFT)
            return reject(AgentManifestRejection.INVALID_STATUS, "Новый агент может быть только черновиком.")
        val fileValidation = AgentMarkdownRenderer.validate(manifest)
        if (!fileValidation.accepted) {
            return AgentManifestValidation(false, fileValidation.rejection, fileValidation.detail)
        }
        return AgentManifestValidation(true)
    }

    private fun isJsonSchemaObject(raw: String): Boolean = runCatching {
        val schema = JsonParser.parseString(raw)
        if (!schema.isJsonObject) return@runCatching false
        val obj = schema.asJsonObject
        if (obj.get("type")?.asString != "object") return@runCatching false
        val properties = obj.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return@runCatching false
        val required = obj.get("required") ?: return@runCatching true
        required.isJsonArray && required.asJsonArray.all {
            it.isJsonPrimitive && it.asJsonPrimitive.isString && properties.has(it.asString)
        }
    }.getOrDefault(false)

    private fun reject(reason: AgentManifestRejection, detail: String? = null) =
        AgentManifestValidation(false, reason, detail)
}
