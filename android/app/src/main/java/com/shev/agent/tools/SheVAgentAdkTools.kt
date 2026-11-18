package com.shev.agent.tools

import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.shev.agent.AgentCapability
import com.google.gson.Gson

/**
 * Minimal ADK adapter. Every model-invoked function is routed through the existing
 * SheV Agent executor; ADK annotations never grant capabilities by themselves.
 */
class SheVAgentAdkTools(
    private val executor: AgentToolExecutor,
    private val executionContext: AgentToolExecutionContext
) {
    @Tool(
        name = "inspect_available_agent_tools",
        description = "Lists locally registered SheV Agent tools that are safe to inspect."
    )
    fun inspectAvailableTools(): Map<String, String> {
        val execution = executor.execute(
            AgentToolRequest(
                toolId = TOOL_ID,
                capability = AgentCapability.ANALYZE_DATA,
                input = "{}"
            ),
            executionContext
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
        description = "Creates a local task draft for the user. Immediately follow with save_task_plan using the returned task_id. The task remains WAITING_APPROVAL and no action is executed."
    )
    suspend fun createTaskDraft(
        @Param("Краткое описание поручения, которое пользователь явно попросил сохранить.") title: String
    ): Map<String, String> {
        val execution = executor.executeSuspending(
            AgentToolRequest(
                toolId = CREATE_TASK_DRAFT_ID,
                capability = AgentCapability.CREATE_DRAFT,
                input = Gson().toJson(mapOf("title" to title))
            ),
            executionContext
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

    /**
     * Saves a model-proposed DAG plan only after validating every step against the
     * registered local tool schema and the minimum capability risk.
     */
    @Tool(
        name = "save_task_plan",
        description = "Saves a proposed plan for the task draft just created. Provide the exact task_id returned by create_task_draft, a concise summary, and steps_json as a JSON array of step objects: id, toolId, argumentsJson, dependsOn (array), risk (READ_ONLY, REVERSIBLE_WRITE, EXTERNAL_SIDE_EFFECT, CRITICAL), purpose, expectedOutcome, timeoutMillis. Unknown tools, invalid arguments, or understated risk are rejected. Saving a plan does not execute it."
    )
    suspend fun saveTaskPlan(
        @Param("Точный идентификатор черновика, возвращённый create_task_draft.") taskId: String,
        @Param("Краткое описание предлагаемого плана.") summary: String,
        @Param("JSON-массив шагов с инструментами, аргументами, зависимостями и риском.") stepsJson: String
    ): Map<String, String> {
        val execution = executor.executeSuspending(
            AgentToolRequest(
                toolId = CREATE_TASK_PLAN_ID,
                capability = AgentCapability.CREATE_DRAFT,
                input = Gson().toJson(
                    mapOf(
                        "task_id" to taskId,
                        "summary" to summary,
                        "steps_json" to stepsJson
                    )
                )
            ),
            executionContext
        )
        val result = execution.result
        return if (execution.status == AgentToolExecutionStatus.COMPLETED && result?.success == true) {
            mapOf("status" to "plan_saved", "message" to result.output)
        } else {
            mapOf(
                "status" to "plan_rejected",
                "reason" to execution.status.name,
                "message" to (result?.errorCode ?: "План не сохранён.")
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
        val execution = executor.execute(
            AgentToolRequest(
                toolId = CURRENT_TIME_TOOL_ID,
                capability = AgentCapability.ANALYZE_DATA,
                input = Gson().toJson(mapOf("query" to query))
            ),
            executionContext
        )
        val result = execution.result
        return if (execution.status == AgentToolExecutionStatus.COMPLETED && result?.success == true) {
            mapOf("status" to "ok", "current_time" to result.output)
        } else {
            mapOf("status" to "unavailable", "reason" to execution.status.name)
        }
    }

    companion object {
        const val TOOL_ID = "agent.inspect_tools"
        const val CURRENT_TIME_TOOL_ID = "agent.current_local_time"
        const val CREATE_TASK_DRAFT_ID = "agent.create_task_draft"
        const val CREATE_TASK_PLAN_ID = "agent.save_task_plan"
    }
}