package com.shev.agent.creator

import com.shev.agent.AgentCapability
import com.shev.agent.CapabilityRisk

data class AgentRunDecision(
    val allowed: Boolean,
    val reason: String? = null
)

/** Revalidates an approved manifest against the exact host tools that have live handlers. */
object AgentRunPolicy {
    fun evaluate(
        manifest: AgentManifest,
        toolCapabilities: Map<String, Set<AgentCapability>> = emptyMap()
    ): AgentRunDecision {
        if (manifest.status != AgentManifest.STATUS_APPROVED) {
            return AgentRunDecision(false, "Сначала одобри паспорт агента.")
        }
        val validation = AgentManifestValidator(toolCapabilities.keys).validate(
            manifest.copy(status = AgentManifest.STATUS_DRAFT)
        )
        if (!validation.accepted) {
            return AgentRunDecision(
                false,
                "Паспорт не прошёл повторную проверку: ${validation.rejection ?: "ошибка"}.${validation.detail?.let { " $it" }.orEmpty()}"
            )
        }
        val selectedCapabilities = manifest.allowedToolIds.flatMap { toolCapabilities[it].orEmpty() }.toSet()
        if (manifest.networkPolicy == "OFFLINE" && AgentCapability.READ_REMOTE_DATA in selectedCapabilities) {
            return AgentRunDecision(false, "Паспорт OFFLINE не может запрашивать сетевые инструменты.")
        }
        if (selectedCapabilities.any { it.risk == CapabilityRisk.HIGH || it.risk == CapabilityRisk.CRITICAL }) {
            return AgentRunDecision(false, "Специалистам разрешены только инструменты чтения и создания локальных черновиков.")
        }
        return AgentRunDecision(true)
    }
}
