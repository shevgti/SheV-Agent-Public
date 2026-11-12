package com.shev.agent.creator

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** Renders the portable Claude Code subagent file format; native host permissions are never exported. */
object AgentMarkdownRenderer {
    private val exampleJson = GsonBuilder().disableHtmlEscaping().create()
    fun validate(manifest: AgentManifest): AgentManifestValidation {
        if (!manifest.id.matches(Regex("[a-z0-9][a-z0-9-]{1,48}[a-z0-9]")) || manifest.id.length !in 3..50)
            return reject(AgentManifestRejection.INVALID_ID, "Agent name must be 3–50 lowercase letters, numbers or hyphens.")
        if (manifest.name.isBlank() || manifest.purpose.isBlank() || manifest.role.isBlank() ||
            manifest.instructions.isBlank() || manifest.process.isBlank() || manifest.outputFormat.isBlank()
        ) return reject(AgentManifestRejection.EMPTY_INSTRUCTIONS)
        if (manifest.model !in setOf("inherit", "sonnet", "opus", "haiku"))
            return reject(AgentManifestRejection.INVALID_MODEL, "Unsupported Claude Code model.")
        if (manifest.claudeCodeTools.distinct().size != manifest.claudeCodeTools.size ||
            manifest.claudeCodeTools.any { it !in setOf("Read", "Write") }
        ) return reject(AgentManifestRejection.UNKNOWN_TOOL, "Claude Code tools must be selected from the explicit Read/Write allowlist.")
        if (manifest.color !in setOf("blue", "cyan", "green", "yellow", "magenta", "red"))
            return reject(AgentManifestRejection.INVALID_COLOR, "Unsupported agent color.")
        val description = descriptionFor(manifest)
        if (!description.startsWith("Use this agent when") ||
            !description.contains("When to invoke") ||
            description.length !in 10..5_000
        ) return reject(AgentManifestRejection.INVALID_DESCRIPTION)
        if (manifest.triggers.size !in 2..4 || manifest.triggers.any { it.isBlank() } ||
            manifest.triggeringExamples.size !in 2..4 ||
            manifest.triggeringExamples.any {
                it.context.isBlank() || it.user.isBlank() || it.assistant.isBlank() ||
                    it.commentary.isBlank() || it.invocation.isBlank()
            }
        ) return reject(AgentManifestRejection.INVALID_EXAMPLES, "Provide 2–4 complete worked triggering examples.")
        val exampleCharacters = manifest.triggeringExamples.sumOf {
            it.context.length + it.user.length + it.assistant.length + it.commentary.length + it.invocation.length
        }
        if (description.length + exampleCharacters + 120 * manifest.triggeringExamples.size > 5_000)
            return reject(AgentManifestRejection.INVALID_DESCRIPTION, "Description and worked examples exceed 5,000 characters.")
        if (manifest.instructions.length !in 20..10_000 ||
            manifest.process.length > 8_000 || manifest.outputFormat.length > 5_000
        ) return reject(AgentManifestRejection.EMPTY_INSTRUCTIONS, "Agent instructions, process or output format are empty or too long.")
        return AgentManifestValidation(true)
    }

    fun render(manifest: AgentManifest): String {
        val validation = validate(manifest)
        require(validation.accepted) {
            validation.detail ?: validation.rejection?.name ?: "Invalid agent definition."
        }
        val description = buildString {
            append(xmlText(descriptionFor(manifest).trim()))
            append("\n\nExamples:")
            manifest.triggeringExamples.forEach { example ->
                append("\n<example>")
                append("\nContext: ").append(xmlText(example.context))
                append("\nuser: ").append(xmlText(exampleJson.toJson(example.user)))
                append("\nassistant: ").append(xmlText(exampleJson.toJson(example.assistant)))
                append("\n<commentary>")
                append("\n").append(xmlText(example.commentary))
                append("\n</commentary>")
                append("\nassistant: ").append(xmlText(exampleJson.toJson(example.invocation)))
                append("\n</example>")
            }
        }
        return buildString {
            append("---\n")
            append("name: ").append(manifest.id).append("\n")
            append("description: |\n")
            description.lines().forEach { append("  ").append(it).append("\n") }
            append("model: ").append(manifest.model).append("\n")
            append("color: ").append(manifest.color).append("\n")
            if (manifest.claudeCodeTools.isNotEmpty()) {
                append("tools: [").append(manifest.claudeCodeTools.joinToString(", ") { Gson().toJson(it) }).append("]\n")
            }
            append("---\n\n")
            append("You are an expert ").append(manifest.role)
                .append(" specializing in ").append(manifest.purpose).append(".\n\n")
            append("## When to invoke\n\n")
            manifest.triggers.forEachIndexed { index, trigger ->
                append("- **Scenario ").append(index + 1).append(".** ").append(trigger.trim()).append("\n")
            }
            append("\n## Your Core Responsibilities\n\n")
            append(manifest.instructions.trim()).append("\n\n")
            append("## Process\n\n")
            append(manifest.process.trim()).append("\n\n")
            append("## Quality Standards\n\n")
            append("- Follow the defined scope; do not invent facts, data, sources or capabilities.\n")
            append("- Verify important outputs against supplied evidence and state uncertainty.\n")
            append("- Respect user approval requirements and never claim an action succeeded without confirmation.\n")
            append("- If required information is missing, ask focused clarifying questions before proceeding.\n\n")
            append("## Output Format\n\n")
            append(manifest.outputFormat.trim()).append("\n\n")
            append("Purpose: ").append(manifest.purpose).append("\n\n")
            append("Verification: ").append(manifest.verificationStrategy.trim()).append("\n\n")
            append("## Edge Cases and Limitations\n\n")
            manifest.limitations.forEach { append("- ").append(it.trim()).append("\n") }
            append("\n## Evaluation Scenarios\n\n")
            manifest.evaluationCases.forEachIndexed { index, scenario ->
                append(index + 1).append(". ").append(scenario.trim()).append("\n")
            }
        }
    }

    private fun xmlText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun descriptionFor(manifest: AgentManifest): String =
        manifest.description.ifBlank {
            "Use this agent when a task requires " + manifest.purpose.lowercase() +
                ". Typical triggers include " + manifest.triggers.joinToString(", ") +
                ". See \"When to invoke\" in the agent body for worked scenarios."
        }

    private fun reject(reason: AgentManifestRejection, detail: String? = null) =
        AgentManifestValidation(false, reason, detail)
}
