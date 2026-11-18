package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.AgentPhase
import com.shev.agent.AgentWorkflowCommand
import com.shev.agent.data.AgentTaskRepositoryResult
import com.shev.agent.data.AgentTaskTransitionRequest
import com.shev.agent.data.RoomAgentTaskRepository
import com.shev.agent.data.RoomAgentPlanVerificationService
import com.shev.agent.data.PlanVerificationStatus
import com.shev.agent.data.SheVAgentDatabase
import com.shev.agent.data.StartPlanExecutionResult
import java.util.UUID
import kotlinx.coroutines.flow.first

enum class AgentPlanRunStatus {
    COMPLETED,
    VERIFYING,
    OUTCOME_UNKNOWN,
    BLOCKED,
    TASK_NOT_RUNNING,
    TOOL_NOT_AVAILABLE,
    TOOL_CONFIGURATION_INVALID,
    STEP_FAILED,
    START_REJECTED,
    VERIFICATION_BLOCKED,
    PUMP_LIMIT_REACHED
}

data class AgentPlanRunResult(
    val taskId: String,
    val taskTitle: String,
    val status: AgentPlanRunStatus,
    val completedSteps: Int = 0,
    val uncertainSteps: Int = 0,
    val stepId: String? = null,
    val errorCode: String? = null,
    val startStatus: StartPlanExecutionResult? = null
)

/**
 * Kernel-side plan lifecycle runner.
 *
 * The Android ViewModel only requests start/recovery/cancellation and renders
 * typed results. DAG pumping, capability scope, restart recovery and the
 * RUNNING -> VERIFYING transition stay outside the UI layer.
 */
class AgentPlanRunner(
    private val database: SheVAgentDatabase,
    private val executor: AgentPlanExecutor,
    private val taskRepository: RoomAgentTaskRepository,
    private val grantedCapabilities: Set<AgentCapability> = setOf(AgentCapability.ANALYZE_DATA),
    private val explicitlyApprovedCapabilities: Set<AgentCapability> = setOf(AgentCapability.ANALYZE_DATA),
    private val maxStepsPerPump: Int = DEFAULT_MAX_STEPS_PER_PUMP,
    private val verificationService: RoomAgentPlanVerificationService = RoomAgentPlanVerificationService(database)
) {
    init {
        require(maxStepsPerPump in 1..100)
    }

    suspend fun startApproved(taskId: String, expectedTaskRevision: Long): AgentPlanRunResult {
        val task = database.agentDao().getTask(taskId)
        val title = task?.title.orEmpty()
        val start = executor.startPlan(taskId, expectedTaskRevision, System.currentTimeMillis())
        if (start != StartPlanExecutionResult.STARTED &&
            start != StartPlanExecutionResult.ALREADY_STARTED
        ) {
            return AgentPlanRunResult(
                taskId = taskId,
                taskTitle = title,
                status = AgentPlanRunStatus.START_REJECTED,
                startStatus = start
            )
        }
        return pump(taskId, title)
    }

    suspend fun recoverRunning(taskId: String): AgentPlanRunResult {
        val task = database.agentDao().getTask(taskId)
        val title = task?.title.orEmpty()
        val uncertain = executor.recoverAfterProcessRestart(taskId, System.currentTimeMillis())
        if (uncertain > 0) {
            return AgentPlanRunResult(
                taskId = taskId,
                taskTitle = title,
                status = AgentPlanRunStatus.OUTCOME_UNKNOWN,
                uncertainSteps = uncertain
            )
        }
        return pump(taskId, title)
    }

    /** Called once after the native runtime is ready; Room remains the source of truth. */
    suspend fun recoverAndResume(): List<AgentPlanRunResult> {
        val tasks = database.agentDao().observeTasks().first()
        val results = mutableListOf<AgentPlanRunResult>()
        tasks.filter { it.phase == AgentPhase.RUNNING.name }.forEach { task ->
            results += recoverRunning(task.id)
        }
        tasks.filter { it.phase == AgentPhase.VERIFYING.name }.forEach { task ->
            results += verifyTask(task.id, task.title)
        }
        tasks.filter { it.phase == AgentPhase.APPROVED.name }.forEach { task ->
            results += startApproved(task.id, task.revision)
        }
        return results
    }

    suspend fun cancelTask(taskId: String, expectedTaskRevision: Long): Boolean =
        executor.cancelTask(taskId, expectedTaskRevision, System.currentTimeMillis())

    private suspend fun pump(taskId: String, title: String): AgentPlanRunResult {
        var completedSteps = 0
        repeat(maxStepsPerPump) {
            val attempt = executor.executeNext(
                taskId = taskId,
                grantedCapabilities = grantedCapabilities,
                explicitlyApprovedCapabilities = explicitlyApprovedCapabilities
            )
            when (attempt.status) {
                PlanExecutionAttemptStatus.STEP_COMPLETED -> completedSteps++
                PlanExecutionAttemptStatus.PLAN_COMPLETE -> {
                    val task = database.agentDao().getTask(taskId)
                        ?: return AgentPlanRunResult(
                            taskId, title, AgentPlanRunStatus.VERIFICATION_BLOCKED,
                            completedSteps = completedSteps, errorCode = "TASK_NOT_FOUND"
                        )
                    val transition = taskRepository.transition(
                        AgentTaskTransitionRequest(
                            taskId = taskId,
                            command = AgentWorkflowCommand.BEGIN_VERIFICATION,
                            expectedRevision = task.revision,
                            eventId = UUID.nameUUIDFromBytes(
                                "verify:$taskId:${task.revision}".toByteArray()
                            ).toString(),
                            idempotencyKey = "plan-verify:$taskId:${task.revision}",
                            occurredAtEpochMillis = System.currentTimeMillis()
                        )
                    )
                    if (transition !is AgentTaskRepositoryResult.Applied &&
                        transition !is AgentTaskRepositoryResult.Replayed
                    ) {
                        return AgentPlanRunResult(
                            taskId, title, AgentPlanRunStatus.VERIFICATION_BLOCKED,
                            completedSteps = completedSteps,
                            errorCode = transition.javaClass.simpleName
                        )
                    }
                    return verifyTask(taskId, title, completedSteps)
                }
                PlanExecutionAttemptStatus.BLOCKED ->
                    return result(taskId, title, AgentPlanRunStatus.BLOCKED, completedSteps, attempt.stepId, attempt.errorCode)
                PlanExecutionAttemptStatus.TASK_NOT_RUNNING ->
                    return result(taskId, title, AgentPlanRunStatus.TASK_NOT_RUNNING, completedSteps, attempt.stepId, attempt.errorCode)
                PlanExecutionAttemptStatus.TOOL_NOT_AVAILABLE ->
                    return result(taskId, title, AgentPlanRunStatus.TOOL_NOT_AVAILABLE, completedSteps, attempt.stepId, attempt.errorCode)
                PlanExecutionAttemptStatus.TOOL_CONFIGURATION_INVALID ->
                    return result(taskId, title, AgentPlanRunStatus.TOOL_CONFIGURATION_INVALID, completedSteps, attempt.stepId, attempt.errorCode)
                PlanExecutionAttemptStatus.STEP_FAILED ->
                    return result(taskId, title, AgentPlanRunStatus.STEP_FAILED, completedSteps, attempt.stepId, attempt.errorCode)
                PlanExecutionAttemptStatus.OUTCOME_UNKNOWN ->
                    return result(taskId, title, AgentPlanRunStatus.OUTCOME_UNKNOWN, completedSteps, attempt.stepId, attempt.errorCode)
            }
        }
        return AgentPlanRunResult(
            taskId, title, AgentPlanRunStatus.PUMP_LIMIT_REACHED,
            completedSteps = completedSteps
        )
    }

    private suspend fun verifyTask(
        taskId: String,
        title: String,
        completedSteps: Int = 0
    ): AgentPlanRunResult {
        val task = database.agentDao().getTask(taskId)
            ?: return AgentPlanRunResult(
                taskId, title, AgentPlanRunStatus.VERIFICATION_BLOCKED,
                completedSteps = completedSteps, errorCode = "TASK_NOT_FOUND"
            )
        if (task.phase == AgentPhase.COMPLETED.name) {
            return AgentPlanRunResult(taskId, title, AgentPlanRunStatus.COMPLETED, completedSteps)
        }
        if (task.phase != AgentPhase.VERIFYING.name) {
            return AgentPlanRunResult(
                taskId, title, AgentPlanRunStatus.VERIFICATION_BLOCKED,
                completedSteps = completedSteps, errorCode = "TASK_NOT_VERIFYING"
            )
        }

        val nowMillis = System.currentTimeMillis()
        val verification = verificationService.verifyAndIssueCompletionEvidence(
            taskId = taskId,
            expectedTaskRevision = task.revision,
            nowMillis = nowMillis
        )
        val token = verification.completionEvidenceToken
        if (verification.status != PlanVerificationStatus.VERIFIED || token == null) {
            return AgentPlanRunResult(
                taskId, title, AgentPlanRunStatus.VERIFICATION_BLOCKED,
                completedSteps = completedSteps,
                errorCode = "VERIFICATION_${verification.status.name}:${verification.failure?.name ?: verification.failedStepId.orEmpty()}"
            )
        }

        val completion = taskRepository.transition(
            AgentTaskTransitionRequest(
                taskId = taskId,
                command = AgentWorkflowCommand.COMPLETE,
                expectedRevision = task.revision,
                eventId = UUID.nameUUIDFromBytes(
                    "complete:$taskId:${task.revision}".toByteArray()
                ).toString(),
                idempotencyKey = "plan-complete:$taskId:${task.revision}",
                occurredAtEpochMillis = nowMillis,
                evidenceRef = token
            )
        )
        val status = when (completion) {
            is AgentTaskRepositoryResult.Applied,
            is AgentTaskRepositoryResult.Replayed -> AgentPlanRunStatus.COMPLETED
            is AgentTaskRepositoryResult.Rejected,
            AgentTaskRepositoryResult.NotFound,
            AgentTaskRepositoryResult.IdempotencyConflict -> AgentPlanRunStatus.VERIFICATION_BLOCKED
        }
        return AgentPlanRunResult(
            taskId, title, status,
            completedSteps = completedSteps,
            errorCode = if (status == AgentPlanRunStatus.VERIFICATION_BLOCKED) {
                completion.javaClass.simpleName
            } else null
        )
    }

    private fun result(
        taskId: String,
        title: String,
        status: AgentPlanRunStatus,
        completedSteps: Int,
        stepId: String?,
        errorCode: String?
    ) = AgentPlanRunResult(
        taskId = taskId,
        taskTitle = title,
        status = status,
        completedSteps = completedSteps,
        stepId = stepId,
        errorCode = errorCode
    )

    private companion object {
        const val DEFAULT_MAX_STEPS_PER_PUMP = 50
    }
}
