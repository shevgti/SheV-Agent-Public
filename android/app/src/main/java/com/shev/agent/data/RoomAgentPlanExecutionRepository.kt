package com.shev.agent.data

import androidx.room.withTransaction
import com.shev.agent.AgentPhase
import com.shev.agent.AgentTask
import com.shev.agent.AgentTaskOrchestrator
import com.shev.agent.AgentWorkflowState
import com.shev.agent.AgentWorkflowCommand
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.PlanStep
import java.util.UUID

enum class StartPlanExecutionResult {
    STARTED, ALREADY_STARTED, NOT_FOUND, STALE_TASK, INVALID_PHASE, PLAN_NOT_FOUND, INVALID_PLAN, CONFLICT
}

enum class ClaimPlanStepStatus {
    CLAIMED, NOT_FOUND, TASK_NOT_RUNNING, STALE_TASK, DEPENDENCIES_NOT_MET, ALREADY_CLAIMED, ALL_STEPS_COMPLETE
}

data class PlanStepClaim(
    val step: PlanStep,
    val execution: AgentPlanStepExecutionEntity,
    val awaitingApproval: Boolean
)

data class ClaimPlanStepResult(
    val status: ClaimPlanStepStatus,
    val claim: PlanStepClaim? = null
)

/**
 * Durable plan execution ledger. Every transition is persisted before control
 * returns to the caller, and a dispatched-but-unfinished step is never retried
 * automatically after process death.
 */
class RoomAgentPlanExecutionRepository(
    private val database: SheVAgentDatabase,
    private val planRepository: RoomAgentPlanRepository = RoomAgentPlanRepository(database)
) {
    suspend fun startPlan(
        taskId: String,
        expectedTaskRevision: Long,
        nowMillis: Long
    ): StartPlanExecutionResult {
        if (taskId.isBlank() || expectedTaskRevision < 0L || nowMillis <= 0L) {
            return StartPlanExecutionResult.CONFLICT
        }
        return database.withTransaction {
            val dao = database.agentDao()
            val idempotencyKey = "plan-execution:$taskId:start"
            val previous = dao.findEventByIdempotencyKey(idempotencyKey)
            if (previous != null) {
                val rows = dao.getAgentPlanStepExecutions(taskId)
                return@withTransaction if (
                    previous.taskId == taskId &&
                    previous.eventType == AgentWorkflowCommand.START_EXECUTION.name &&
                    previous.revision == expectedTaskRevision + 1L &&
                    rows.isNotEmpty()
                ) StartPlanExecutionResult.ALREADY_STARTED else StartPlanExecutionResult.CONFLICT
            }

            val task = dao.getTask(taskId) ?: return@withTransaction StartPlanExecutionResult.NOT_FOUND
            if (task.revision != expectedTaskRevision) return@withTransaction StartPlanExecutionResult.STALE_TASK
            if (task.phase != AgentPhase.APPROVED.name) return@withTransaction StartPlanExecutionResult.INVALID_PHASE

            val persisted = planRepository.loadPlan(taskId)
                ?: return@withTransaction StartPlanExecutionResult.PLAN_NOT_FOUND
            val plan = persisted.plan
            if (plan.taskId != taskId || persisted.stepRevisions.keys != plan.steps.map { it.id }.toSet()) {
                return@withTransaction StartPlanExecutionResult.INVALID_PLAN
            }
            if (dao.getAgentPlanStepExecutions(taskId).isNotEmpty()) {
                return@withTransaction StartPlanExecutionResult.CONFLICT
            }

            val state = AgentWorkflowState(
                task = AgentTask(
                    id = task.id,
                    title = task.title,
                    phase = AgentPhase.valueOf(task.phase),
                    requiresApproval = task.requiresApproval
                ),
                resumePhase = task.resumePhase?.let(AgentPhase::valueOf),
                revision = task.revision
            )
            val transition = AgentTaskOrchestrator.transition(
                state,
                AgentWorkflowCommand.START_EXECUTION,
                expectedRevision = expectedTaskRevision
            )
            if (!transition.accepted) return@withTransaction StartPlanExecutionResult.INVALID_PHASE

            val nextRevision = transition.state.revision
            dao.saveTask(
                task.copy(
                    phase = AgentPhase.RUNNING.name,
                    revision = nextRevision,
                    resumePhase = null,
                    updatedAtEpochMillis = nowMillis
                )
            )
            dao.insertAgentPlanStepExecutions(
                plan.steps.map { step ->
                    AgentPlanStepExecutionEntity(
                        taskId = taskId,
                        stepId = step.id,
                        taskRevision = nextRevision,
                        planRevision = plan.revision,
                        stepRevision = persisted.stepRevisions.getValue(step.id),
                        invocationId = UUID.randomUUID().toString(),
                        attempt = 0,
                        status = AgentPlanStepExecutionEntity.PENDING,
                        createdAtEpochMillis = nowMillis,
                        updatedAtEpochMillis = nowMillis
                    )
                }
            )
            dao.appendEvent(
                AgentEventEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    eventType = AgentWorkflowCommand.START_EXECUTION.name,
                    message = "APPROVED->RUNNING; persisted ${plan.steps.size} step executions",
                    createdAtEpochMillis = nowMillis,
                    revision = nextRevision,
                    idempotencyKey = idempotencyKey
                )
            )
            StartPlanExecutionResult.STARTED
        }
    }

    suspend fun claimStep(
        taskId: String,
        stepId: String,
        requiresInvocationApproval: Boolean,
        nowMillis: Long
    ): ClaimPlanStepResult {
        if (taskId.isBlank() || stepId.isBlank() || nowMillis <= 0L) {
            return ClaimPlanStepResult(ClaimPlanStepStatus.NOT_FOUND)
        }
        return database.withTransaction {
            val dao = database.agentDao()
            val task = dao.getTask(taskId)
                ?: return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.NOT_FOUND)
            if (task.phase != AgentPhase.RUNNING.name) {
                return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.TASK_NOT_RUNNING)
            }
            val persisted = planRepository.loadPlan(taskId)
                ?: return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.NOT_FOUND)
            val step = persisted.plan.steps.firstOrNull { it.id == stepId }
                ?: return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.NOT_FOUND)
            val execution = dao.getAgentPlanStepExecution(taskId, stepId)
                ?: return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.NOT_FOUND)
            if (execution.taskRevision != task.revision ||
                execution.planRevision != persisted.plan.revision ||
                execution.stepRevision != persisted.stepRevisions[stepId]
            ) return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.STALE_TASK)
            if (execution.status != AgentPlanStepExecutionEntity.PENDING) {
                return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.ALREADY_CLAIMED)
            }

            val executionRows = dao.getAgentPlanStepExecutions(taskId)
            if (executionRows.isNotEmpty() && executionRows.all { it.status == AgentPlanStepExecutionEntity.SUCCEEDED }) {
                return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.ALL_STEPS_COMPLETE)
            }
            val executions = executionRows.associateBy { it.stepId }
            if (step.dependsOn.any { dependency ->
                    executions[dependency]?.status != AgentPlanStepExecutionEntity.SUCCEEDED
                }
            ) return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.DEPENDENCIES_NOT_MET)

            val nextStatus = if (requiresInvocationApproval) {
                AgentPlanStepExecutionEntity.WAITING_APPROVAL
            } else {
                AgentPlanStepExecutionEntity.DISPATCHING
            }
            val updated = dao.transitionAgentPlanStepExecution(
                taskId = taskId,
                stepId = stepId,
                invocationId = execution.invocationId,
                taskRevision = task.revision,
                planRevision = execution.planRevision,
                stepRevision = execution.stepRevision,
                expectedStatus = AgentPlanStepExecutionEntity.PENDING,
                newStatus = nextStatus,
                nowMillis = nowMillis,
                dispatchStartedAtMillis = if (requiresInvocationApproval) null else nowMillis
            )
            if (updated != 1) return@withTransaction ClaimPlanStepResult(ClaimPlanStepStatus.ALREADY_CLAIMED)

            dao.appendEvent(
                AgentEventEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    eventType = if (requiresInvocationApproval) "APPROVAL_REQUESTED" else "STEP_DISPATCH_INTENT",
                    message = "step=$stepId invocation=${execution.invocationId}",
                    createdAtEpochMillis = nowMillis,
                    revision = task.revision,
                    idempotencyKey = "step:$taskId:$stepId:${execution.invocationId}:claim"
                )
            )
            val claimed = execution.copy(status = nextStatus, updatedAtEpochMillis = nowMillis)
            ClaimPlanStepResult(
                ClaimPlanStepStatus.CLAIMED,
                PlanStepClaim(step, claimed, requiresInvocationApproval)
            )
        }
    }

    /** Called after the one-use approval is consumed and immediately before network dispatch. */
    suspend fun markDispatched(
        taskId: String,
        stepId: String,
        invocationId: String,
        taskRevision: Long,
        planRevision: Long,
        stepRevision: Long,
        nowMillis: Long
    ): Boolean {
        if (nowMillis <= 0L) return false
        return database.withTransaction {
            val dao = database.agentDao()
            val current = dao.getAgentPlanStepExecution(taskId, stepId) ?: return@withTransaction false
            if (current.invocationId != invocationId) return@withTransaction false
            if (current.status == AgentPlanStepExecutionEntity.DISPATCHED) return@withTransaction true
            if (current.status !in setOf(
                    AgentPlanStepExecutionEntity.WAITING_APPROVAL,
                    AgentPlanStepExecutionEntity.DISPATCHING
                )
            ) return@withTransaction false
            val updated = dao.transitionAgentPlanStepExecution(
                taskId = taskId,
                stepId = stepId,
                invocationId = invocationId,
                taskRevision = taskRevision,
                planRevision = planRevision,
                stepRevision = stepRevision,
                expectedStatus = current.status,
                newStatus = AgentPlanStepExecutionEntity.DISPATCHED,
                nowMillis = nowMillis,
                dispatchStartedAtMillis = current.dispatchStartedAtEpochMillis ?: nowMillis,
                dispatchedAtMillis = nowMillis
            )
            if (updated != 1) return@withTransaction false
            dao.appendEvent(
                AgentEventEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    eventType = "STEP_DISPATCHED",
                    message = "step=$stepId invocation=$invocationId",
                    createdAtEpochMillis = nowMillis,
                    revision = taskRevision,
                    idempotencyKey = "step:$taskId:$stepId:$invocationId:dispatched"
                )
            )
            true
        }
    }

    suspend fun completeStep(
        taskId: String,
        stepId: String,
        invocationId: String,
        taskRevision: Long,
        planRevision: Long,
        stepRevision: Long,
        output: String,
        nowMillis: Long
    ): Boolean {
        if (output.length > MAX_STEP_OUTPUT_CHARS || nowMillis <= 0L) return false
        return database.withTransaction {
            val dao = database.agentDao()
            val updated = dao.transitionAgentPlanStepExecution(
                taskId = taskId,
                stepId = stepId,
                invocationId = invocationId,
                taskRevision = taskRevision,
                planRevision = planRevision,
                stepRevision = stepRevision,
                expectedStatus = AgentPlanStepExecutionEntity.DISPATCHED,
                newStatus = AgentPlanStepExecutionEntity.SUCCEEDED,
                nowMillis = nowMillis,
                completedAtMillis = nowMillis,
                resultCiphertext = LocalPlanCipher.encrypt(output)
            )
            if (updated != 1) return@withTransaction false
            dao.appendEvent(
                AgentEventEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    eventType = "STEP_COMPLETED",
                    message = "step=$stepId invocation=$invocationId",
                    createdAtEpochMillis = nowMillis,
                    revision = taskRevision,
                    idempotencyKey = "step:$taskId:$stepId:$invocationId:completed"
                )
            )
            true
        }
    }

    suspend fun failStep(
        taskId: String,
        stepId: String,
        invocationId: String,
        taskRevision: Long,
        planRevision: Long,
        stepRevision: Long,
        errorCode: String,
        mayHaveSideEffect: Boolean,
        nowMillis: Long
    ): Boolean {
        if (errorCode.isBlank() || errorCode.length > 100 || nowMillis <= 0L) return false
        return database.withTransaction {
            val dao = database.agentDao()
            val current = dao.getAgentPlanStepExecution(taskId, stepId) ?: return@withTransaction false
            if (current.invocationId != invocationId) return@withTransaction false
            if (current.status !in setOf(
                    AgentPlanStepExecutionEntity.WAITING_APPROVAL,
                    AgentPlanStepExecutionEntity.DISPATCHING,
                    AgentPlanStepExecutionEntity.DISPATCHED
                )
            ) return@withTransaction false
            val terminal = if (mayHaveSideEffect &&
                current.status in setOf(
                    AgentPlanStepExecutionEntity.DISPATCHING,
                    AgentPlanStepExecutionEntity.DISPATCHED
                )
            ) AgentPlanStepExecutionEntity.OUTCOME_UNKNOWN else AgentPlanStepExecutionEntity.FAILED
            val updated = dao.transitionAgentPlanStepExecution(
                taskId = taskId,
                stepId = stepId,
                invocationId = invocationId,
                taskRevision = taskRevision,
                planRevision = planRevision,
                stepRevision = stepRevision,
                expectedStatus = current.status,
                newStatus = terminal,
                nowMillis = nowMillis,
                completedAtMillis = nowMillis,
                errorCode = errorCode
            )
            if (updated != 1) return@withTransaction false
            dao.appendEvent(
                AgentEventEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    eventType = if (terminal == AgentPlanStepExecutionEntity.OUTCOME_UNKNOWN) "STEP_OUTCOME_UNKNOWN" else "STEP_FAILED",
                    message = "step=$stepId invocation=$invocationId code=$errorCode",
                    createdAtEpochMillis = nowMillis,
                    revision = taskRevision,
                    idempotencyKey = "step:$taskId:$stepId:$invocationId:$terminal"
                )
            )
            true
        }
    }

    /** On process recovery, dispatched operations are ambiguous and must be reconciled, never retried. */
    suspend fun recoverAfterProcessRestart(taskId: String, nowMillis: Long): Int {
        if (taskId.isBlank() || nowMillis <= 0L) return 0
        return database.withTransaction {
            val dao = database.agentDao()
            val before = dao.getAgentPlanStepExecutions(taskId).filter {
                it.status in setOf(
                    AgentPlanStepExecutionEntity.DISPATCHING,
                    AgentPlanStepExecutionEntity.DISPATCHED
                )
            }
            val changed = dao.markUncertainPlanStepExecutions(taskId, nowMillis)
            before.forEach { execution ->
                dao.appendEvent(
                    AgentEventEntity(
                        id = UUID.randomUUID().toString(),
                        taskId = taskId,
                        eventType = "STEP_OUTCOME_UNKNOWN",
                        message = "process restart; step=${execution.stepId} invocation=${execution.invocationId}",
                        createdAtEpochMillis = nowMillis,
                        revision = execution.taskRevision,
                        idempotencyKey = "step:$taskId:${execution.stepId}:${execution.invocationId}:restart-unknown"
                    )
                )
            }
            changed
        }
    }

    suspend fun readStepOutput(taskId: String, stepId: String): String? {
        val execution = database.agentDao().getAgentPlanStepExecution(taskId, stepId) ?: return null
        if (execution.status != AgentPlanStepExecutionEntity.SUCCEEDED) return null
        return execution.resultCiphertext?.let { runCatching { LocalPlanCipher.decrypt(it) }.getOrNull() }
    }

    suspend fun allStepsSucceeded(taskId: String): Boolean {
        val persisted = planRepository.loadPlan(taskId) ?: return false
        val executions = database.agentDao().getAgentPlanStepExecutions(taskId).associateBy { it.stepId }
        return persisted.plan.steps.all {
            executions[it.id]?.status == AgentPlanStepExecutionEntity.SUCCEEDED
        }
    }

    suspend fun cancelTask(
        taskId: String,
        expectedTaskRevision: Long,
        nowMillis: Long
    ): Boolean {
        if (taskId.isBlank() || expectedTaskRevision < 0L || nowMillis <= 0L) return false
        return database.withTransaction {
            val dao = database.agentDao()
            val idempotencyKey = "plan-execution:$taskId:cancel:$expectedTaskRevision"
            val previous = dao.findEventByIdempotencyKey(idempotencyKey)
            if (previous != null) {
                return@withTransaction dao.getTask(taskId)?.phase == AgentPhase.CANCELLED.name
            }
            val task = dao.getTask(taskId) ?: return@withTransaction false
            if (task.revision != expectedTaskRevision) return@withTransaction false
            val currentState = AgentWorkflowState(
                task = AgentTask(
                    id = task.id,
                    title = task.title,
                    phase = AgentPhase.valueOf(task.phase),
                    requiresApproval = task.requiresApproval
                ),
                resumePhase = task.resumePhase?.let(AgentPhase::valueOf),
                revision = task.revision
            )
            val transition = AgentTaskOrchestrator.transition(
                currentState,
                AgentWorkflowCommand.CANCEL,
                expectedRevision = expectedTaskRevision
            )
            if (!transition.accepted) return@withTransaction false

            val before = dao.getAgentPlanStepExecutions(taskId)
            dao.saveTask(
                task.copy(
                    phase = AgentPhase.CANCELLED.name,
                    revision = transition.state.revision,
                    resumePhase = null,
                    updatedAtEpochMillis = nowMillis
                )
            )
            dao.cancelUndispatchedPlanStepExecutions(taskId, nowMillis)
            dao.markUncertainPlanStepExecutions(taskId, nowMillis)
            dao.invalidateMcpInvocationApprovalRequestsForTask(taskId, nowMillis)

            before.filter { execution ->
                execution.status in setOf(
                    AgentPlanStepExecutionEntity.PENDING,
                    AgentPlanStepExecutionEntity.WAITING_APPROVAL,
                    AgentPlanStepExecutionEntity.DISPATCHING,
                    AgentPlanStepExecutionEntity.DISPATCHED
                )
            }.forEach { execution ->
                val dispatched = execution.status in setOf(
                    AgentPlanStepExecutionEntity.DISPATCHING,
                    AgentPlanStepExecutionEntity.DISPATCHED
                )
                val eventType = if (dispatched) "STEP_OUTCOME_UNKNOWN" else "STEP_CANCELLED"
                dao.appendEvent(
                    AgentEventEntity(
                        id = UUID.randomUUID().toString(),
                        taskId = taskId,
                        eventType = eventType,
                        message = "task cancelled; step=${execution.stepId} invocation=${execution.invocationId}",
                        createdAtEpochMillis = nowMillis,
                        revision = transition.state.revision,
                        idempotencyKey = "step:$taskId:${execution.stepId}:${execution.invocationId}:cancelled"
                    )
                )
            }
            dao.appendEvent(
                AgentEventEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    eventType = AgentWorkflowCommand.CANCEL.name,
                    message = "${currentState.task.phase.name}->CANCELLED",
                    createdAtEpochMillis = nowMillis,
                    revision = transition.state.revision,
                    idempotencyKey = idempotencyKey
                )
            )
            true
        }
    }

    private companion object {
        const val MAX_STEP_OUTPUT_CHARS = 12_000
    }
}
