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
        description = "Creates a local task draft for the user. The task remains WAITING_APPROVAL and no action is executed."
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

    companion object {
        const val TOOL_ID = "agent.inspect_tools"
        const val CREATE_TASK_DRAFT_ID = "agent.create_task_draft"
    }
}