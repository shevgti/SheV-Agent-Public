package com.shev.agent

import com.shev.agent.tools.AgentToolExecutionContext
import com.shev.agent.tools.AgentToolExecutionStatus
import com.shev.agent.tools.AgentToolExecutor
import com.shev.agent.tools.AgentToolRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class AgentPlanExecutionStatus {
    EXECUTED_NEEDS_VERIFICATION,
    INVALID_PLAN,
    TOOL_NOT_EXECUTABLE,
    ACCESS_DENIED,
    APPROVAL_REQUIRED,
    AGENT_ALLOWLIST_DENIED,
    STEP_FAILED
}

data class AgentPlanStepExecution(
    val stepId: String,
    val toolId: String,
    val success: Boolean,
    val output: String,
    val errorCode: String? = null
)

data class AgentPlanExecutionResult(
    val status: AgentPlanExecutionStatus,
    val steps: List<AgentPlanStepExecution> = emptyList(),
    val failedStepId: String? = null,
    val validationRejection: AgentPlanRejection? = null
)

/**
 * Sequential dispatcher for a validated plan.
 *
 * This class never grants permissions or approves a task. The caller must invoke it
 * only after the user has approved the exact plan and must pass the current immutable
 * execution context. Every step still passes through AgentToolExecutor. Successful
 * tool returns are NOT treated as proof that the overall goal is complete: the caller
 * must run a separate postcondition verifier and persist VERIFYING/COMPLETED evidence.
 */
class AgentPlanExecutionEngine(
    private val validator: AgentPlanValidator,
    private val executor: AgentToolExecutor,
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS
) {
    init {
        require(maxOutputChars in 256..MAX_ALLOWED_OUTPUT_CHARS)
    }

    suspend fun executeApprovedPlan(
        plan: AgentPlan,
        agentAllowedToolIds: Set<String>,
        context: AgentToolExecutionContext
    ): AgentPlanExecutionResult {
        val validation = validator.validate(plan)
        if (!validation.accepted) {
            return AgentPlanExecutionResult(
                status = AgentPlanExecutionStatus.INVALID_PLAN,
                validationRejection = validation.rejection
            )
        }

        // Fail before the first side effect if any step lacks a real registered handler.
        val executableIds = executor.availableTools().mapTo(mutableSetOf()) { it.id }
        val unavailable = plan.steps.firstOrNull { it.toolId !in executableIds }
        if (unavailable != null) {
            return AgentPlanExecutionResult(
                status = AgentPlanExecutionStatus.TOOL_NOT_EXECUTABLE,
                failedStepId = unavailable.id
            )
        }

        val results = mutableListOf<AgentPlanStepExecution>()
        for (step in plan.steps) {
            currentCoroutineContext().ensureActive()
            val execution = executor.executeSuspendingForAgent(
                agentAllowedToolIds = agentAllowedToolIds,
                request = AgentToolRequest(
                    toolId = step.toolId,
                    capability = step.capability,
                    input = step.input
                ),
                context = context
            )
            val toolResult = execution.result
            if (execution.status != AgentToolExecutionStatus.COMPLETED || toolResult?.success != true) {
                val status = when (execution.status) {
                    AgentToolExecutionStatus.ACCESS_DENIED ->
                        AgentPlanExecutionStatus.ACCESS_DENIED
                    AgentToolExecutionStatus.APPROVAL_REQUIRED ->
                        AgentPlanExecutionStatus.APPROVAL_REQUIRED
                    AgentToolExecutionStatus.AGENT_ALLOWLIST_DENIED ->
                        AgentPlanExecutionStatus.AGENT_ALLOWLIST_DENIED
                    else -> AgentPlanExecutionStatus.STEP_FAILED
                }
                return AgentPlanExecutionResult(
                    status = status,
                    steps = results,
                    failedStepId = step.id
                )
            }

            val output = toolResult.output
            results += AgentPlanStepExecution(
                stepId = step.id,
                toolId = step.toolId,
                success = true,
                output = if (output.length <= maxOutputChars) output
                    else output.take(maxOutputChars) + "\n[результат сокращён]"
            )
        }

        return AgentPlanExecutionResult(
            status = AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION,
            steps = results
        )
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_CHARS = 12_000
        const val MAX_ALLOWED_OUTPUT_CHARS = 32_000
    }
}
