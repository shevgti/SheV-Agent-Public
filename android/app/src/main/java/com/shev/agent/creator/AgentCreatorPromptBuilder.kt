package com.shev.agent.creator

import com.google.gson.Gson

/** Minimal output contract for small on-device models. Host code fills optional metadata. */
object AgentCreatorPromptBuilder {
    fun build(
        userRequest: String,
        knownToolIds: Set<String>,
        existingAgents: List<String> = emptyList(),
        forceComplete: Boolean = false
    ): String {
        require(userRequest.isNotBlank()) { "Опиши задачу будущего агента." }
        require(userRequest.length <= MAX_REQUEST_CHARS) { "Описание слишком длинное (максимум 2000 символов)." }
        val tools = knownToolIds.sorted().take(MAX_TOOL_IDS).joinToString(", ").ifBlank { "(нет)" }
        val existing = existingAgents.take(MAX_EXISTING_AGENTS)
            .joinToString("; ") { it.take(70) }.ifBlank { "(нет)" }
        val clarification = if (forceComplete) {
            "Не задавай вопросов. clarifyingQuestions верни как []. Выбери безопасные значения по умолчанию."
        } else {
            "Если критически не хватает данных, задай максимум один короткий вопрос в clarifyingQuestions; иначе []."
        }
        val requestJson = Gson().toJson(userRequest.trim())
        return """
            Создай один паспорт специализированного агента SheV. Отвечай ТОЛЬКО одним компактным JSON-объектом в одну строку. Без Markdown.
            Пиши по-русски. Короткие значения, без повторов. $clarification
            Агент — только черновик. Не выдавай разрешения: allowedToolIds=[], claudeCodeTools=[], memoryReadScopes=[], memoryWriteScopes=[], networkPolicy="OFFLINE", sideEffectPolicy="DRAFT_ONLY".
            Не генерируй description и triggeringExamples: используй "" и []. Не генерируй process и outputFormat: используй "".
            id — lowercase-kebab-case. model="inherit". color="blue".
            HOST TOOLS (не используй, список только для контекста): $tools
            УЖЕ СОЗДАНЫ: $existing
            ЗАДАЧА: $requestJson
            Верни все ключи строго с указанными типами. Не добавляй другие поля. Схемы — простые JSON-объекты без pattern/format/regex.
            {"clarifyingQuestions":[],"id":"weather-helper","name":"Помощник погоды","purpose":"Краткая цель","role":"Роль","description":"","model":"inherit","color":"blue","instructions":"Краткие правила работы и ограничения.","process":"","outputFormat":"","triggers":["Сценарий 1","Сценарий 2"],"triggeringExamples":[],"inputSchema":{"type":"object","properties":{},"required":[]},"outputSchema":{"type":"object","properties":{},"required":[]},"allowedToolIds":[],"claudeCodeTools":[],"memoryReadScopes":[],"memoryWriteScopes":[],"maxSteps":4,"timeoutSeconds":60,"maxModelCalls":2,"networkPolicy":"OFFLINE","sideEffectPolicy":"DRAFT_ONLY","verificationStrategy":"Как проверить результат.","evaluationCases":["Обычный запрос","Неполные данные"],"limitations":["Основное ограничение"]}
            Лимиты: maxSteps 1..16, timeoutSeconds 10..600, maxModelCalls 1..8.
        """.trimIndent()
    }

    const val MAX_REQUEST_CHARS = 2_000
    private const val MAX_TOOL_IDS = 32
    private const val MAX_EXISTING_AGENTS = 8
}
