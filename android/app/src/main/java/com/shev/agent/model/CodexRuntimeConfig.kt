package com.shev.agent.model

/**
 * Safe defaults for the embedded Codex App Server.
 *
 * Web search is a hosted Codex tool, independent from the local command sandbox.
 * Existing explicit user configuration is preserved; live search is added only
 * when the setting is absent.
 */
internal object CodexRuntimeConfig {
    const val DEVELOPER_INSTRUCTIONS =
        "Ты — SheV, персональный AI-агент пользователя. Всегда отвечай по-русски. " +
            "Когда пользователь просит найти, проверить актуальную информацию или исследовать тему, " +
            "используй встроенный web search, открывай важные источники и добавляй прямые ссылки. " +
            "Не заявляй о поиске, если web search фактически не вызывался; если поиск недоступен, " +
            "честно сообщи об ограничении. Отделяй подтверждённые сведения от предположений. " +
            "Работай только в папке рабочего пространства приложения. Не заявляй, что действие выполнено, " +
            "если оно не завершилось успешно. Перед командами и изменениями файлов запрашивай подтверждение. " +
            "Не пытайся обходить ограничения Android или получать доступ к данным вне разрешённой папки."

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
