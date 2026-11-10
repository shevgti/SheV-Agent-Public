package com.shev.agent.model

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.shev.agent.AgentCapability
import com.shev.agent.data.AgentTaskDraftRequest
import com.shev.agent.data.AgentTaskDraftResult
import com.shev.agent.data.RoomAgentTaskRepository
import com.shev.agent.tools.AgentTool
import com.shev.agent.tools.AgentToolDescriptor
import com.shev.agent.tools.AgentToolExecutionContext
import com.shev.agent.tools.AgentToolExecutor
import com.shev.agent.tools.AgentToolRegistry
import com.shev.agent.tools.AgentToolRequest
import com.shev.agent.tools.AgentToolResult
import com.shev.agent.tools.LocalTimeTool
import com.shev.agent.tools.SuspendingAgentTool
import com.shev.agent.tools.ToolInputField
import com.shev.agent.tools.ToolInputSchema
import com.shev.agent.tools.ToolInputType
import java.util.UUID

/**
 * Exposes only three bounded local tools to the OpenAI Responses API.
 * Every call is re-validated by SheV's existing AgentToolExecutor and capability policy.
 */
class OpenAiLocalToolBridge(private val taskRepository: RoomAgentTaskRepository) {
    private val registry = AgentToolRegistry().apply {
        register(
            AgentToolDescriptor(
                id = TIME_TOOL_ID,
                title = "Текущее местное время",
                description = "Возвращает точные дату, время и часовой пояс устройства",
                capabilities = setOf(AgentCapability.ANALYZE_DATA),
                inputSchema = ToolInputSchema(
                    listOf(ToolInputField("query", ToolInputType.STRING, required = true, maxLength = 8, allowedValues = setOf("now")))
                )
            )
        )
        register(
            AgentToolDescriptor(
                id = INSPECT_TOOL_ID,
                title = "Просмотр инструментов",
                description = "Показывает доступные локальные инструменты SheV",
                capabilities = setOf(AgentCapability.ANALYZE_DATA),
                inputSchema = ToolInputSchema(
                    listOf(ToolInputField("query", ToolInputType.STRING, required = true, maxLength = 8, allowedValues = setOf("list")))
                )
            )
        )
        register(
            AgentToolDescriptor(
                id = DRAFT_TOOL_ID,
                title = "Создать черновик поручения",
                description = "Сохраняет локальное поручение в ожидании подтверждения; ничего не выполняет",
                capabilities = setOf(AgentCapability.CREATE_DRAFT),
                inputSchema = ToolInputSchema(
                    listOf(ToolInputField("title", ToolInputType.STRING, required = true, maxLength = 2_000))
                )
            )
        )
    }

    private val draftHandler = SuspendingAgentTool { input ->
        val title = runCatching {
            JsonParser.parseString(input).asJsonObject.get("title")?.asString
        }.getOrNull()
        if (title.isNullOrBlank()) {
            AgentToolResult(false, "Пустое или некорректное поручение.", "INVALID_TITLE")
        } else {
            val now = System.currentTimeMillis()
            val requestBucket = now / 60_000L
            val taskId = UUID.nameUUIDFromBytes("shev-task-draft-v1:$requestBucket:$title".toByteArray()).toString()
            val eventId = UUID.nameUUIDFromBytes("event:$taskId".toByteArray()).toString()
            when (val result = taskRepository.createDraft(
                AgentTaskDraftRequest(
                    taskId = taskId,
                    title = title,
                    eventId = eventId,
                    idempotencyKey = "task-create:$taskId",
                    occurredAtEpochMillis = now
                )
            )) {
                is AgentTaskDraftResult.Created -> AgentToolResult(
                    true,
                    "Черновик поручения «${result.state.task.title}» сохранён. Он ожидает подтверждения во вкладке «Память». Никакое действие не запускалось."
                )
                is AgentTaskDraftResult.Replayed -> AgentToolResult(
                    true,
                    "Это поручение уже сохранено и ожидает подтверждения во вкладке «Память». Повторная запись не создана."
                )
                AgentTaskDraftResult.InvalidRequest -> AgentToolResult(false, "Поручение не сохранено: некорректный запрос.", "INVALID_REQUEST")
                AgentTaskDraftResult.Conflict -> AgentToolResult(false, "Поручение не сохранено: обнаружен конфликт записи.", "CONFLICT")
                AgentTaskDraftResult.NotFound -> AgentToolResult(false, "Поручение не найдено после записи.", "NOT_FOUND")
            }
        }
    }

    private val executor = AgentToolExecutor(
        registry = registry,
        handlers = mapOf(
            TIME_TOOL_ID to AgentTool { _ -> AgentToolResult(true, LocalTimeTool().readCurrentTime()) },
            INSPECT_TOOL_ID to AgentTool {
                AgentToolResult(true, registry.list().joinToString("\n") { "${it.title}: ${it.description}" })
            }
        ),
        suspendingHandlers = mapOf(DRAFT_TOOL_ID to draftHandler)
    )

    val definitions: List<OpenAiFunctionTool> = listOf(
        OpenAiFunctionTool(
            name = "get_current_local_time",
            description = "Получить фактические дату, время и часовой пояс Android-устройства. Используй для вопросов о сегодняшнем дне и текущем времени.",
            parameters = objectSchema(
                "query" to stringSchema("Всегда передавай значение now.", listOf("now"))
            )
        ),
        OpenAiFunctionTool(
            name = "inspect_available_agent_tools",
            description = "Показать список инструментов, зарегистрированных в SheV Agent.",
            parameters = objectSchema(
                "query" to stringSchema("Передай list.", listOf("list"))
            )
        ),
        OpenAiFunctionTool(
            name = "create_task_draft",
            description = "Сохранить поручение как черновик во вкладке Память. Используй только если пользователь явно попросил сохранить или добавить поручение. Черновик не выполняет действия и ожидает подтверждения пользователя.",
            parameters = objectSchema(
                "title" to stringSchema("Краткое описание поручения.", maxLength = 2000)
            )
        )
    )

    suspend fun execute(name: String, argumentsJson: String): String {
        val result = when (name) {
            "get_current_local_time" -> executor.execute(
                AgentToolRequest(TIME_TOOL_ID, AgentCapability.ANALYZE_DATA, argumentsJson),
                EXECUTION_CONTEXT
            )
            "inspect_available_agent_tools" -> executor.execute(
                AgentToolRequest(INSPECT_TOOL_ID, AgentCapability.ANALYZE_DATA, argumentsJson),
                EXECUTION_CONTEXT
            )
            "create_task_draft" -> executor.executeSuspending(
                AgentToolRequest(DRAFT_TOOL_ID, AgentCapability.CREATE_DRAFT, argumentsJson),
                EXECUTION_CONTEXT
            )
            else -> return """{"status":"denied","reason":"UNKNOWN_TOOL"}"""
        }
        val toolResult = result.result
        return Gson().toJson(
            mapOf(
                "status" to if (result.status.name == "COMPLETED" && toolResult?.success == true) "ok" else "not_executed",
                "execution_status" to result.status.name,
                "result" to toolResult?.output,
                "error_code" to toolResult?.errorCode
            )
        )
    }

    private fun objectSchema(vararg fields: Pair<String, JsonObject>): JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply { fields.forEach { (name, schema) -> add(name, schema) } })
            add("required", com.google.gson.JsonArray().apply { fields.forEach { add(it.first) } })
            addProperty("additionalProperties", false)
        }

    private fun stringSchema(description: String, enum: List<String>? = null, maxLength: Int = 64): JsonObject =
        JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", description)
            addProperty("maxLength", maxLength)
            enum?.let { values -> add("enum", com.google.gson.JsonArray().apply { values.forEach { add(it) } }) }
        }

    private companion object {
        const val TIME_TOOL_ID = "agent.current_local_time"
        const val INSPECT_TOOL_ID = "agent.inspect_tools"
        const val DRAFT_TOOL_ID = "agent.create_task_draft"
        val EXECUTION_CONTEXT = AgentToolExecutionContext(
            setOf(AgentCapability.ANALYZE_DATA, AgentCapability.CREATE_DRAFT)
        )
    }
}
