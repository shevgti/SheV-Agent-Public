package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.CapabilityRisk
import com.shev.agent.ScopedCapabilityGrant
import com.shev.agent.data.AgentPlanStepExecutionEntity
import com.shev.agent.data.ClaimPlanStepStatus
import com.shev.agent.data.PlanStepClaim
import com.shev.agent.data.RoomAgentPlanExecutionRepository
import com.shev.agent.data.RoomAgentPlanRepository
import com.shev.agent.data.SheVAgentDatabase

enum class PlanExecutionAttemptStatus {
    STEP_COMPLETED,
    PLAN_COMPLETE,
    BLOCKED,
    TASK_NOT_RUNNING,
    TOOL_NOT_AVAILABLE,
    TOOL_CONFIGURATION_INVALID,
    STEP_FAILED,
    OUTCOME_UNKNOWN
}

data class PlanExecutionAttemptResult(
    val status: PlanExecutionAttemptStatus,
    val stepId: String? = null,
    val errorCode: String? = null
)

/**
 * Executes at most one ready step from the persisted DAG.
 *
 * It never invents task/plan/step revisions. The Room ledger owns the stable
 * invocation ID and all state transitions. Remote MCP steps remain suspended
 * in WAITING_APPROVAL until the exact per-invocation provider grants them.
 */
class AgentPlanExecutor(
    private val database: SheVAgentDatabase,
    private val registry: AgentToolRegistry,
    private val toolExecutor: AgentToolExecutor,
    private val executionRepository: RoomAgentPlanExecutionRepository =
        RoomAgentPlanExecutionRepository(database),
    private val planRepository: RoomAgentPlanRepository = RoomAgentPlanRepository(database)
) {
    suspend fun executeNext(
        taskId: String,
        grantedCapabilities: Set<AgentCapability>,
        explicitlyApprovedCapabilities: Set<AgentCapability>,
        persistentGrants: List<ScopedCapabilityGrant> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): PlanExecutionAttemptResult {
        val task = database.agentDao().getTask(taskId)
            ?: return PlanExecutionAttemptResult(PlanExecutionAttemptStatus.TASK_NOT_RUNNING)
        if (task.phase != "RUNNING") {
            return PlanExecutionAttemptResult(PlanExecutionAttemptStatus.TASK_NOT_RUNNING)
        }
        val persisted = planRepository.loadPlan(taskId)
            ?: return PlanExecutionAttemptResult(PlanExecutionAttemptStatus.BLOCKED, errorCode = "PLAN_NOT_FOUND")
        val rows = database.agentDao().getAgentPlanStepExecutions(taskId).associateBy { it.stepId }
        if (persisted.plan.steps.all { rows[it.id]?.status == AgentPlanStepExecutionEntity.SUCCEEDED }) {
            return PlanExecutionAttemptResult(PlanExecutionAttemptStatus.PLAN_COMPLETE)
        }
        if (rows.values.any { it.status == AgentPlanStepExecutionEntity.OUTCOME_UNKNOWN }) {
            return PlanExecutionAttemptResult(PlanExecutionAttemptStatus.OUTCOME_UNKNOWN)
        }
        if (rows.values.any { it.status == AgentPlanStepExecutionEntity.FAILED }) {
            return PlanExecutionAttemptResult(PlanExecutionAttemptStatus.BLOCKED)
        }

        fun dependenciesSucceeded(stepId: String): Boolean {
            val step = persisted.plan.steps.firstOrNull { it.id == stepId } ?: return false
            return step.dependsOn.all { rows[it]?.status == AgentPlanStepExecutionEntity.SUCCEEDED }
        }

        val waitingStep = persisted.plan.steps.firstOrNull { step ->
            rows[step.id]?.status == AgentPlanStepExecutionEntity.WAITING_APPROVAL &&
                dependenciesSucceeded(step.id)
        }
        val pendingStep = if (waitingStep == null) {
            persisted.plan.steps.firstOrNull { step ->
                rows[step.id]?.status == AgentPlanStepExecutionEntity.PENDING &&
                    dependenciesSucceeded(step.id)
            }
        } else null
        val step = waitingStep ?: pendingStep
            ?: return PlanExecutionAttemptResult(PlanExecutionAttemptStatus.BLOCKED)

        val descriptor = registry.find(step.toolId)
            ?: return PlanExecutionAttemptResult(
                PlanExecutionAttemptStatus.TOOL_NOT_AVAILABLE,
                step.id,
                "TOOL_NOT_REGISTERED"
            )
        if (!descriptor.planExecutable || descriptor.capabilities.size != 1) {
            return PlanExecutionAttemptResult(
                PlanExecutionAttemptStatus.TOOL_CONFIGURATION_INVALID,
                step.id,
                "TOOL_NOT_PLAN_EXECUTABLE_OR_AMBIGUOUS_CAPABILITY"
            )
        }
        val capability = descriptor.capabilities.single()
        val requiresInvocationApproval = capability == AgentCapability.REMOTE_MCP_OPERATION

        val claim: PlanStepClaim = if (waitingStep != null) {
            val execution = rows.getValue(step.id)
            PlanStepClaim(step, execution, awaitingApproval = true)
        } else {
            val result = executionRepository.claimStep(
                taskId = taskId,
                stepId = step.id,
                requiresInvocationApproval = requiresInvocationApproval,
                nowMillis = nowMillis
            )
            if (result.status != ClaimPlanStepStatus.CLAIMED || result.claim == null) {
                return PlanExecutionAttemptResult(
                    PlanExecutionAttemptStatus.BLOCKED,
                    step.id,
                    result.status.name
                )
            }
            result.claim
        }

        val execution = claim.execution
        val mcpContext = if (requiresInvocationApproval) {
            planRepository.createExecutionContext(taskId, step.id, execution.invocationId)
                ?: return PlanExecutionAttemptResult(
                    PlanExecutionAttemptStatus.STEP_FAILED,
                    step.id,
                    "MCP_EXECUTION_CONTEXT_MISSING"
                )
        } else null

        val executionContext = AgentToolExecutionContext(
            grantedCapabilities = grantedCapabilities,
            explicitlyApprovedCapabilities = explicitlyApprovedCapabilities,
            persistentGrants = persistentGrants,
            nowMillis = nowMillis,
            mcpInvocationContext = mcpContext,
            beforeMcpNetworkCall = if (mcpContext != null) { trustedContext ->
                executionRepository.markDispatched(
                    taskId = taskId,
                    stepId = step.id,
                    invocationId = trustedContext.invocationId,
                    taskRevision = trustedContext.taskRevision,
                    planRevision = trustedContext.planRevision,
                    stepRevision = trustedContext.stepRevision,
                    nowMillis = System.currentTimeMillis()
                )
            } else null,
            beforeToolDispatch = if (!requiresInvocationApproval) {
                {
                    executionRepository.markDispatched(
                        taskId = taskId,
                        stepId = step.id,
                        invocationId = execution.invocationId,
                        taskRevision = execution.taskRevision,
                        planRevision = execution.planRevision,
                        stepRevision = execution.stepRevision,
                        nowMillis = System.currentTimeMillis()
                    )
                }
            } else null
        )

        val toolResult = toolExecutor.executeSuspending(
            AgentToolRequest(
                toolId = step.toolId,
                capability = capability,
                input = step.argumentsJson
            ),
            executionContext
        )
        val result = toolResult.result
        if (toolResult.status == AgentToolExecutionStatus.COMPLETED && result?.success == true) {
            val completed = executionRepository.completeStep(
                taskId = taskId,
                stepId = step.id,
                invocationId = execution.invocationId,
                taskRevision = execution.taskRevision,
                planRevision = execution.planRevision,
                stepRevision = execution.stepRevision,
                output = result.output,
                nowMillis = System.currentTimeMillis()
            )
            return if (completed) {
                PlanExecutionAttemptResult(PlanExecutionAttemptStatus.STEP_COMPLETED, step.id)
            } else {
                PlanExecutionAttemptResult(
                    PlanExecutionAttemptStatus.OUTCOME_UNKNOWN,
                    step.id,
                    "RESULT_NOT_PERSISTED_AFTER_DISPATCH"
                )
            }
        }

        val latest = database.agentDao().getAgentPlanStepExecution(taskId, step.id)
        val mayHaveSideEffect = latest?.status == AgentPlanStepExecutionEntity.DISPATCHED &&
            capability.risk.ordinal >= CapabilityRisk.HIGH.ordinal
        val errorCode = result?.errorCode ?: toolResult.status.name
        executionRepository.failStep(
            taskId = taskId,
            stepId = step.id,
            invocationId = execution.invocationId,
            taskRevision = execution.taskRevision,
            planRevision = execution.planRevision,
            stepRevision = execution.stepRevision,
            errorCode = errorCode.take(100),
            mayHaveSideEffect = mayHaveSideEffect,
            nowMillis = System.currentTimeMillis()
        )
        return PlanExecutionAttemptResult(
            if (mayHaveSideEffect) PlanExecutionAttemptStatus.OUTCOME_UNKNOWN
            else PlanExecutionAttemptStatus.STEP_FAILED,
            step.id,
            errorCode
        )
    }

    suspend fun recoverAfterProcessRestart(taskId: String, nowMillis: Long = System.currentTimeMillis()): Int =
        executionRepository.recoverAfterProcessRestart(taskId, nowMillis)
}
