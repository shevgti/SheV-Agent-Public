package com.shev.agent.tools

import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.shev.agent.AgentCapability
import com.shev.agent.data.AgentSkillLibrary
import com.google.gson.Gson

/**
 * Minimal ADK adapter. Every model-invoked function is routed through the existing
 * SheV Agent executor; ADK annotations never grant capabilities by themselves.
 */
class SheVAgentAdkTools(
    private val executor: AgentToolExecutor,
    private val executionContext: AgentToolExecutionContext,
    /** Non-null for generated specialists; null is reserved for existing host-owned callers. */
    agentAllowedToolIds: Set<String>? = null,
    private val skillLibrary: AgentSkillLibrary? = null
) {
    // Freeze the approved IDs when this adapter/session is created. A mutable caller-owned set
    // must not be able to add permissions while the specialist is running.
    private val approvedAgentToolIds: Set<String>? = agentAllowedToolIds?.toSet()

    fun availableToolDescriptors(): List<AgentToolDescriptor> = executor.availableTools()

    fun availableToolIds(): Set<String> = availableToolDescriptors().mapTo(linkedSetOf()) { it.id }

    /** Creates a new immutable adapter whose executor allowlist is exactly the approved manifest set. */
    fun scopedTo(agentToolIds: Set<String>): SheVAgentAdkTools {
        val unknown = agentToolIds - availableToolIds()
        require(unknown.isEmpty()) { "Неизвестные или недоступные инструменты: ${unknown.joinToString()}." }
        return SheVAgentAdkTools(executor, executionContext, agentToolIds, skillLibrary)
    }

    private fun execute(request: AgentToolRequest): AgentToolExecutionResult =
        approvedAgentToolIds?.let { executor.executeForAgent(it, request, executionContext) }
            ?: executor.execute(request, executionContext)

    private suspend fun executeSuspending(request: AgentToolRequest): AgentToolExecutionResult =
        approvedAgentToolIds?.let { executor.executeSuspendingForAgent(it, request, executionContext) }
            ?: executor.executeSuspending(request, executionContext)

    @Tool(
        name = "inspect_available_agent_tools",
        description = "Lists locally registered SheV Agent tools that are safe to inspect."
    )
    fun inspectAvailableTools(): Map<String, String> {
        val execution = execute(
            AgentToolRequest(
                toolId = TOOL_ID,
                capability = AgentCapability.ANALYZE_DATA,
                input = "{}"
            )
        )
        val result = execution.result
        return if (execution.status == AgentToolExecutionStatus.COMPLETED && result?.success == true) {
            mapOf("status" to "ok", "tools" to result.output)
        } else {
            mapOf(
                "status" to "denied",
                "reason" to execution.status.name,
                "error" to (result?.errorCode ?: "not_executed")
            )
        }
    }

    /**
     * Creates a local task draft only. It does not approve the task or run any action.
     */
    @Tool(
        name = "create_task_draft",
        description = "Creates a local task draft for the user. The task remains WAITING_APPROVAL and no action is executed."
    )
    suspend fun createTaskDraft(
        @Param("Краткое описание поручения, которое пользователь явно попросил сохранить.") title: String
    ): Map<String, String> {
        val execution = executeSuspending(
            AgentToolRequest(
                toolId = CREATE_TASK_DRAFT_ID,
                capability = AgentCapability.CREATE_DRAFT,
                input = Gson().toJson(mapOf("title" to title))
            )
        )
        val result = execution.result
        return if (execution.status == AgentToolExecutionStatus.COMPLETED && result?.success == true) {
            mapOf("status" to "draft_created", "message" to result.output)
        } else {
            mapOf(
                "status" to "not_created",
                "reason" to execution.status.name,
                "message" to (result?.errorCode ?: "Поручение не сохранено.")
            )
        }
    }

    @Tool(
        name = "save_reusable_skill_draft",
        description = "Сохраняет найденный во время работы повторно используемый способ решения как ЧЕРНОВИК навыка. Навык не активируется автоматически и не считается проверенным."
    )
    suspend fun saveReusableSkillDraft(
        @Param("Краткое название навыка.") title: String,
        @Param("Для чего навык нужен.") summary: String,
        @Param("Пошаговая инструкция, без секретов и исполняемого кода.") instructions: String,
        @Param("Ключевые слова через запятую.") keywords: String,
        @Param("Публичные HTTP(S)-ссылки на источники, каждая с новой строки.") sourceUrls: String
    ): Map<String, String> {
        if (skillLibrary == null) return mapOf("status" to "unavailable", "message" to "Библиотека навыков не подключена.")
        val execution = executeSuspending(
            AgentToolRequest(
                toolId = SAVE_SKILL_DRAFT_TOOL_ID,
                capability = AgentCapability.CREATE_DRAFT,
                input = Gson().toJson(
                    mapOf(
                        "title" to title,
                        "summary" to summary,
                        "instructions" to instructions,
                        "keywords" to keywords,
                        "sourceUrls" to sourceUrls
                    )
                )
            )
        )
        val result = execution.result
        return if (execution.status == AgentToolExecutionStatus.COMPLETED && result?.success == true) {
            mapOf("status" to "draft_saved", "message" to result.output)
        } else {
            mapOf(
                "status" to "not_saved",
                "reason" to execution.status.name,
                "message" to (result?.errorCode ?: "Черновик навыка не сохранён.")
            )
        }
    }

    @Tool(
        name = "get_current_local_time",
        description = "Returns the actual current date, time and timezone from the Android device. Use this for every question about current time or today's date; never guess."
    )
    fun getCurrentLocalTime(
        @Param("Передай строку now, чтобы получить точное текущее местное время устройства.") query: String
    ): Map<String, String> {
        val execution = execute(
            AgentToolRequest(
                toolId = CURRENT_TIME_TOOL_ID,
                capability = AgentCapability.ANALYZE_DATA,
                input = Gson().toJson(mapOf("query" to query))
            )
        )
        val result = execution.result
        return if (execution.status == AgentToolExecutionStatus.COMPLETED && result?.success == true) {
            mapOf("status" to "ok", "current_time" to result.output)
        } else {
            mapOf("status" to "unavailable", "reason" to execution.status.name)
        }
    }


    @Tool(
        name = "search_web",
        description = "Ищет актуальную информацию в публичном интернете и возвращает до пяти заголовков, ссылок и сниппетов. Не утверждай, что прочитал полные страницы."
    )
    suspend fun searchWeb(
        @Param("Поисковый запрос на русском или английском языке, от 2 до 200 символов.") query: String
    ): Map<String, String> {
        val execution = executeSuspending(
            AgentToolRequest(
                toolId = WEB_SEARCH_TOOL_ID,
                capability = AgentCapability.READ_REMOTE_DATA,
                input = Gson().toJson(mapOf("query" to query))
            )
        )
        val result = execution.result
        return if (execution.status == AgentToolExecutionStatus.COMPLETED && result?.success == true) {
            mapOf("status" to "ok", "results" to result.output)
        } else {
            mapOf("status" to "unavailable", "reason" to execution.status.name,
                "message" to (result?.errorCode ?: "Поиск не выполнен."))
        }
    }

    @Tool(
        name = "get_current_weather",
        description = "Получает актуальную погоду в указанном городе через публичный онлайн-сервис. Вызывай для вопросов о текущей погоде и прогнозе на сегодня. Передай только название города; не выдумывай погодные данные."
    )
    suspend fun getCurrentWeather(
        @Param("Название города на русском или английском языке, например Мытищи.") city: String
    ): Map<String, String> {
        val execution = executeSuspending(
            AgentToolRequest(
                toolId = WEATHER_TOOL_ID,
                capability = AgentCapability.READ_REMOTE_DATA,
                input = Gson().toJson(mapOf("city" to city))
            )
        )
        val result = execution.result
        return if (execution.status == AgentToolExecutionStatus.COMPLETED && result?.success == true) {
            mapOf("status" to "ok", "weather" to result.output)
        } else {
            mapOf(
                "status" to "unavailable",
                "reason" to execution.status.name,
                "message" to (result?.errorCode ?: "Не удалось получить погоду.")
            )
        }
    }

    companion object {
        const val TOOL_ID = "agent.inspect_tools"
        const val CURRENT_TIME_TOOL_ID = "agent.current_local_time"
        const val CREATE_TASK_DRAFT_ID = "agent.create_task_draft"
        const val WEATHER_TOOL_ID = "agent.current_weather"
        const val WEB_SEARCH_TOOL_ID = "agent.web_search"
        const val SAVE_SKILL_DRAFT_TOOL_ID = "agent.save_skill_draft"
    }
}