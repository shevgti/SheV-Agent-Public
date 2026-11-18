package com.shev.agent.core

/**
 * Declarative, versioned agent skill.
 *
 * A skill may guide the kernel/model on how to approach a task, but it cannot
 * add capabilities, register tools, alter system policy, or execute code.
 * Required tools must already exist in the native registry and pass policy.
 */
enum class AgentSkillTrust { BUILT_IN, USER_AUTHORED, REVIEWED_COMMUNITY }

data class AgentSkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val triggerPhrases: List<String>,
    val requiredToolIds: Set<String> = emptySet(),
    val instructionText: String,
    val sourceUri: String? = null,
    val sha256: String? = null,
    val trust: AgentSkillTrust,
    val enabled: Boolean = false
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        require(name.isNotBlank() && name.length <= 100)
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+([+-][A-Za-z0-9.-]+)?")))
        require(description.isNotBlank() && description.length <= 1_000)
        require(triggerPhrases.size <= 50)
        require(triggerPhrases.all { it.isNotBlank() && it.length <= 120 })
        require(requiredToolIds.size <= 50)
        require(requiredToolIds.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,63}")) })
        require(instructionText.isNotBlank() && instructionText.length <= 16_000)
        require(sourceUri == null || sourceUri.startsWith("https://"))
        require(sha256 == null || sha256.matches(Regex("[a-fA-F0-9]{64}")))
    }
}

/** Skill availability is constrained by the native tool registry and explicit enablement. */
object AgentSkillPolicy {
    fun canActivate(skill: AgentSkillManifest, registeredToolIds: Set<String>): Boolean =
        skill.enabled && skill.requiredToolIds.all(registeredToolIds::contains)
}
