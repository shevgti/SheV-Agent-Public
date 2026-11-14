package com.shev.agent.model

/**
 * Safe defaults for the embedded Codex App Server.
 *
 * Web search is a hosted Codex tool, independent from the local command sandbox.
 * Existing explicit user configuration is preserved; live search is added only
 * when the setting is absent.
 */
internal object CodexRuntimeConfig {
    private val webSearchSetting = Regex("(?m)^\\s*web_search\\s*=.*$")

    fun withLiveWebSearchDefault(existing: String?): String {
        val current = existing.orEmpty()
        if (webSearchSetting.containsMatchIn(current)) return current

        val base = if (current.isBlank()) {
            "approval_policy = \"on-request\"\n" +
                "sandbox_mode = \"read-only\"\n" +
                "model_reasoning_effort = \"medium\"\n"
        } else {
            current.trimEnd() + "\n"
        }
        return base + "web_search = \"live\"\n"
    }
}
