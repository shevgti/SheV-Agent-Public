package com.shev.agent.tools

import com.shev.agent.AgentCapability

/**
 * Контракт подключаемого инструмента. Реализация инструмента не получает доступ
 * автоматически: вызов должен проходить через будущий исполнитель и CapabilityPolicy.
 */
data class AgentToolDescriptor(
    val id: String,
    val title: String,
    val description: String,
    val capabilities: Set<AgentCapability>,
    val inputSchema: ToolInputSchema? = null
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]{1,63}"))) { "Некорректный идентификатор инструмента: $id" }
        require(title.isNotBlank()) { "Название инструмента не должно быть пустым" }
        require(description.isNotBlank()) { "Описание инструмента не должно быть пустым" }
        require(capabilities.isNotEmpty()) { "Инструмент должен объявлять хотя бы одну возможность" }
    }
}

/**
 * Минимальный реестр доступных адаптеров.
 * Он только описывает и находит инструменты — ничего не запускает и не выдаёт разрешений.
 */
class AgentToolRegistry {
    private val tools = linkedMapOf<String, AgentToolDescriptor>()

    fun register(descriptor: AgentToolDescriptor) {
        require(!tools.containsKey(descriptor.id)) {
            "Инструмент с идентификатором '${descriptor.id}' уже зарегистрирован"
        }
        tools[descriptor.id] = descriptor
    }

    fun find(id: String): AgentToolDescriptor? = tools[id]

    fun list(): List<AgentToolDescriptor> = tools.values.toList()

    fun findSupporting(capability: AgentCapability): List<AgentToolDescriptor> =
        tools.values.filter { capability in it.capabilities }
}
