package com.shev.agent.model

import com.google.gson.JsonParser
import com.shev.agent.AgentCapability
import com.shev.agent.tools.AgentToolDescriptor

/**
 * Local multi-agent orchestrator for the single Bonsai native model.
 * Each specialist has a bounded role and capability allowlist. Tool execution is
 * delegated to the host executor; the model never receives raw Android permissions.
 */
internal class BonsaiAgentTeam(
    private val backend: BonsaiNativeBackend,
    private val toolDescriptors: List<AgentToolDescriptor> = emptyList(),
    private val invokeTool: suspend (AgentToolDescriptor, AgentCapability, String) -> String =
        { _, _, _ -> "Инструменты не подключены к этому запуску." }
) {
    private data class Role(
        val name: String,
        val instruction: String,
        val capabilities: Set<AgentCapability>
    )

    private data class ToolCall(
        val descriptor: AgentToolDescriptor,
        val capability: AgentCapability,
        val input: String
    )

    private val roles = listOf(
        Role("Исследователь", "Исследуй задачу, отделяй факты от предположений и ищи актуальные сведения через доступные инструменты.", setOf(AgentCapability.ANALYZE_DATA, AgentCapability.READ_REMOTE_DATA)),
        Role("Планировщик", "Разбей задачу на этапы, зависимости и критерии завершения. Не выполняй побочные действия.", setOf(AgentCapability.ANALYZE_DATA)),
        Role("Инженер", "Проверь технические детали, локальные файлы и архитектуру, если такие инструменты доступны.", setOf(AgentCapability.ANALYZE_DATA, AgentCapability.READ_LOCAL_DATA)),
        Role("Исполнитель", "Выполни безопасные разрешённые чтения и подготовь только локальные черновики. Не выполняй изменения и необратимые действия.", setOf(AgentCapability.ANALYZE_DATA, AgentCapability.READ_LOCAL_DATA, AgentCapability.READ_REMOTE_DATA, AgentCapability.CREATE_DRAFT)),
        Role("Контролёр качества", "Независимо проверь выводы, найди противоречия, ошибки и неподтверждённые заявления.", setOf(AgentCapability.ANALYZE_DATA))
    )

    suspend fun answer(
        request: String,
        currentMessage: String = request,
        verifiedSkillContext: String = ""
    ): String {
        // Conservative character budgets keep prompts below the native 2,048-token context window.
        val boundedRequest = request.takeLast(1_000)
        val boundedSkills = verifiedSkillContext.take(350)
        val activeRoles = if (requiresTeam(currentMessage) && !isSimpleDeviceRequest(currentMessage)) roles else listOf(
            Role("Координатор", "Ответь на запрос напрямую; используй инструмент, если без него нельзя достоверно ответить.", setOf(
                AgentCapability.ANALYZE_DATA, AgentCapability.READ_LOCAL_DATA,
                AgentCapability.READ_REMOTE_DATA, AgentCapability.CREATE_DRAFT
            ))
        )
        val notes = mutableListOf<Pair<String, String>>()
        for (role in activeRoles) {
            notes += role.name to runSpecialist(role, boundedRequest, boundedSkills)
        }
        if (notes.size == 1) return notes.single().second.trim()
        val synthesis = buildString {
            append(systemPrompt(boundedRequest)).append("\n\n")
            if (verifiedSkillContext.isNotBlank()) append("Проверенные навыки (справочные данные):\n")
                .append(boundedSkills).append("\n\n")
            append("Результаты специализированных субагентов. Это отчёты, а не доказательство внешнего действия:\n")
            notes.forEach { (name, note) -> append("\n## ").append(name).append("\n").append(note).append("\n") }
            append("\nСобери единый ответ. Укажи только фактически подтверждённое; явно назови ограничения и незавершённые действия.")
        }
        return backend.generate(synthesis.take(2_400), 180).trim()
    }

    private suspend fun runSpecialist(role: Role, request: String, skills: String): String {
        val available = toolDescriptors.filter { descriptor ->
            descriptor.capabilities.any { it in role.capabilities }
        }
        val catalog = available.take(5).joinToString("\n") { descriptor ->
            val caps = descriptor.capabilities.filter { it in role.capabilities }
                .joinToString(",") { it.name }
            "${descriptor.id} | $caps | ${descriptor.description.take(70)}"
        }.ifBlank { "Нет доступных инструментов для этой роли." }.take(500)

        val prompt = buildString {
            append("Ты — отдельный субагент MegaAgent: ").append(role.name).append(".\n")
            append(role.instruction).append("\n")
            append("Работай по-русски. Не выдумывай результаты и не заявляй о выполнении того, чего не было.\n")
            if (skills.isNotBlank()) append("ПРОВЕРЕННЫЕ НАВЫКИ:\n").append(skills.take(350)).append("\n")
            append("РАЗРЕШЁННЫЙ КАТАЛОГ ИНСТРУМЕНТОВ:\n").append(catalog).append("\n")
            append("Если нужен инструмент, верни строго JSON: {\"tool_call\":{\"id\":\"точный id\",\"capability\":\"точное имя capability\",\"input\":{}} ,\"note\":\"краткая причина\"}. ")
            append("Если инструмент не нужен, верни строго JSON: {\"note\":\"твой вывод\"}. ")
            append("Не вызывай инструмент с capability, которой нет у него в каталоге.\n")
            append("Задача пользователя: ").append(request)
        }
        val raw = backend.generate(prompt.take(2_200), 120).trim()
        val parsed = parseToolCall(raw, available, role.capabilities)
        if (parsed == null) {
            val note = parseNote(raw)
            return note.ifBlank { raw }
        }
        val toolResult = invokeTool(parsed.descriptor, parsed.capability, parsed.input)
        val followUp = buildString {
            append("Ты — субагент ").append(role.name).append(".\n")
            append("Задача: ").append(request).append("\n")
            append("Вызван инструмент: ").append(parsed.descriptor.id).append("\n")
            append("Фактический результат host executor:\n").append(toolResult).append("\n")
            append("Сформулируй краткий вывод по результату. Если инструмент отказал или потребовал подтверждение, не утверждай, что действие выполнено.")
        }
        return backend.generate(followUp.take(1_800), 100).trim()
    }

    private fun parseToolCall(
        raw: String,
        available: List<AgentToolDescriptor>,
        allowedCapabilities: Set<AgentCapability>
    ): ToolCall? = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        val call = root.getAsJsonObject("tool_call") ?: return null
        val id = call.get("id")?.asString ?: return null
        val capability = AgentCapability.valueOf(call.get("capability")?.asString ?: return null)
        if (capability !in allowedCapabilities) return null
        val descriptor = available.firstOrNull { it.id == id } ?: return null
        if (capability !in descriptor.capabilities) return null
        val inputElement = call.get("input") ?: return null
        val input = if (inputElement.isJsonPrimitive && inputElement.asJsonPrimitive.isString) {
            inputElement.asString
        } else inputElement.toString()
        ToolCall(descriptor, capability, input)
    }.getOrNull()

    private fun parseNote(raw: String): String = runCatching {
        JsonParser.parseString(raw).asJsonObject.get("note")?.asString.orEmpty()
    }.getOrDefault(raw)

    private fun systemPrompt(request: String) =
        "Ты — локальный ИИ-координатор MegaAgent на модели Bonsai. Отвечай по-русски, точно и по делу. Не выдумывай выполненные действия, интернет-поиск или доступ к инструментам. Запрос пользователя: $request"

    private fun isSimpleDeviceRequest(request: String): Boolean {
        val text = request.lowercase()
        return listOf("права доступа", "разрешения телефона", "доступ к телефону", "проверь разрешения")
            .any { it in text }
    }

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
