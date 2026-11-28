package com.shev.agent.model

/**
 * Five bounded specialist passes over one native model handle. Calls are sequential:
 * the phone never allocates five model copies or concurrent KV contexts.
 * Specialists are reasoning roles, not privileged tool identities.
 */
internal class BonsaiAgentTeam(private val backend: BonsaiNativeBackend) {
    private data class Role(val name: String, val instruction: String)

    private val roles = listOf(
        Role("Исследователь", "Выдели подтверждённые сведения, неизвестное и то, что нельзя утверждать без источников."),
        Role("Планировщик", "Составь короткий выполнимый план с зависимостями и критериями готовности."),
        Role("Инженер", "Предложи конкретное техническое решение, учитывая ограничения Android и локального устройства."),
        Role("Исполнитель", "Сформулируй проверяемые действия и ожидаемые результаты. Не заявляй, что действия уже выполнены."),
        Role("Контролёр качества", "Найди ошибки, риски, противоречия и непроверенные утверждения; предложи проверки.")
    )

    fun answer(request: String): String {
        if (!requiresTeam(request)) return backend.generate(systemPrompt(request), 512).trim()
        val notes = roles.map { role ->
            val prompt = buildString {
                append("Ты — специалист MegaAgent: ").append(role.name).append(". ")
                append(role.instruction).append(" Отвечай кратко по-русски. Не выдумывай инструменты, поиск или выполненные действия.\n")
                append("Задача пользователя: ").append(request)
            }
            role.name to backend.generate(prompt, 128).trim()
        }
        val synthesis = buildString {
            append(systemPrompt(request)).append("\n\n")
            append("Результаты пяти специалистов (это аналитические заметки, не подтверждение внешних действий):\n")
            notes.forEach { (name, note) -> append("\n").append(name).append(": ").append(note).append("\n") }
            append("\nСобери единый ответ. Укажи только реально проверенные сведения; если информации не хватает, прямо обозначь ограничение.")
        }
        return backend.generate(synthesis, 640).trim()
    }

    private fun systemPrompt(request: String) =
        "Ты — локальный ИИ-помощник MegaAgent. Отвечай по-русски, точно и по делу. Не выдумывай выполненные действия, интернет-поиск или доступ к инструментам. Запрос пользователя: $request"

    private fun requiresTeam(request: String): Boolean {
        val text = request.lowercase()
        val complexIntent = listOf(
            "разработ", "исслед", "сравн", "проверь", "исправ", "создай", "спроект",
            "архитект", "составь план", "проанализ", "найди и", "сделай приложение",
            "проект", "несколько этап"
        ).any { it in text }
        return complexIntent || request.length >= 320
    }
}
