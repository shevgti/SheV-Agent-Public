package com.shev.agent.creator

/** Versioned declarative specialist definition. A manifest never grants permissions or executes code. */
data class AgentManifest(
    val id: String, val name: String, val purpose: String, val role: String,
    val instructions: String, val triggers: List<String>, val inputSchema: String,
    val outputSchema: String, val allowedToolIds: List<String>,
    val memoryReadScopes: List<String>, val memoryWriteScopes: List<String>,
    val maxSteps: Int, val timeoutSeconds: Int, val maxModelCalls: Int,
    val networkPolicy: String, val sideEffectPolicy: String,
    val verificationStrategy: String, val evaluationCases: List<String>,
    val limitations: List<String>, val version: Int = 1, val status: String = STATUS_DRAFT,
    val description: String = "",
    val model: String = "inherit",
    val color: String = "magenta",
    val triggeringExamples: List<AgentTriggerExample> = emptyList(),
    val clarifyingQuestions: List<String> = emptyList(),
    val claudeCodeTools: List<String> = emptyList(),
    val process: String = "",
    val outputFormat: String = ""
) {
    companion object {
        const val STATUS_DRAFT = "DRAFT"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_DISABLED = "DISABLED"
    }
}

data class AgentTriggerExample(
    val context: String,
    val user: String,
    val assistant: String,
    val commentary: String,
    val invocation: String
)

enum class AgentManifestRejection {
    INVALID_ID, EMPTY_NAME, EMPTY_PURPOSE, EMPTY_ROLE, EMPTY_INSTRUCTIONS,
    INVALID_TRIGGER_COUNT, EMPTY_TRIGGER, EMPTY_SCHEMA, INVALID_LIMITS,
    UNKNOWN_TOOL, DUPLICATE_TOOL, INVALID_MEMORY_SCOPE, MISSING_VERIFICATION,
    MISSING_EVALUATION_CASES, INVALID_VERSION, INVALID_STATUS, UNSAFE_POLICY,
    INVALID_DESCRIPTION, INVALID_MODEL, INVALID_COLOR, INVALID_EXAMPLES
}
data class AgentManifestValidation(val accepted: Boolean, val rejection: AgentManifestRejection? = null, val detail: String? = null)
