package com.shev.agent.creator

data class AgentRunDecision(
    val allowed: Boolean,
    val reason: String? = null
)

/**
 * First runtime slice: approved, offline, tool-free specialists only.
 * Tool-backed execution remains disabled until per-agent allowlists are enforced by the host executor.
 */
object AgentRunPolicy {
    fun evaluate(manifest: AgentManifest): AgentRunDecision {
        if (manifest.status != AgentManifest.STATUS_APPROVED) {
            return AgentRunDecision(false, "Сначала одобри паспорт агента.")
        }
        if (manifest.allowedToolIds.isNotEmpty()) {
            return AgentRunDecision(false, "У этого специалиста запрошены инструменты. Их безопасная привязка ещё не подключена.")
        }
        val validation = AgentManifestValidator(emptySet()).validate(
            manifest.copy(status = AgentManifest.STATUS_DRAFT)
        )
        if (!validation.accepted) {
            return AgentRunDecision(
                false,
                "Паспорт не прошёл повторную проверку: ${validation.rejection ?: "ошибка"}."
            )
        }
        return AgentRunDecision(true)
    }
}
