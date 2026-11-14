package com.shev.agent.creator

data class AgentRunDecision(
    val allowed: Boolean,
    val reason: String? = null
)

/** Revalidates an approved manifest against the exact host tools that have live handlers. */
object AgentRunPolicy {
    fun evaluate(
        manifest: AgentManifest,
        registeredToolIds: Set<String> = emptySet()
    ): AgentRunDecision {
        if (manifest.status != AgentManifest.STATUS_APPROVED) {
            return AgentRunDecision(false, "Сначала одобри паспорт агента.")
        }
        val validation = AgentManifestValidator(registeredToolIds).validate(
            manifest.copy(status = AgentManifest.STATUS_DRAFT)
        )
        if (!validation.accepted) {
            return AgentRunDecision(
                false,
                "Паспорт не прошёл повторную проверку: ${validation.rejection ?: "ошибка"}.${validation.detail?.let { " $it" }.orEmpty()}"
            )
        }
        return AgentRunDecision(true)
    }
}
