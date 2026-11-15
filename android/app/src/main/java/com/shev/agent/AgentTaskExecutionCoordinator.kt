package com.shev.agent

import com.shev.agent.data.AgentDao
import com.shev.agent.data.AgentTaskRepositoryResult
import com.shev.agent.data.AgentTaskStepEntity
import com.shev.agent.data.AgentTaskTransitionRequest
import com.shev.agent.data.RoomAgentTaskRepository
import com.shev.agent.tools.AgentToolExecutionContext
import com.shev.agent.tools.AgentToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

enum class AgentTaskExecutionCoordinatorStatus {
    NEEDS_VERIFICATION,
    PAUSED,
    FAILED,
    REJECTED
}

data class AgentTaskExecutionCoordinatorResult(
    val status: AgentTaskExecutionCoordinatorStatus,
    val message: String
)

/**
 * Executes only the exact plan stored and approved in Room.
 * Every step update is persisted before the next step is dispatched.
 * Successful tool output moves the task to VERIFYING, never directly to COMPLETED.
 */
class AgentTaskExecutionCoordinator(
    private val dao: AgentDao,
    private val taskRepository: RoomAgentTaskRepository,
    private val validator: AgentPlanValidator,
    private val executor: AgentToolExecutor,
    private val agentAllowedToolIds: Set<String>,
    private val executionContext: AgentToolExecutionContext
) {
    private val engine = AgentPlanExecutionEngine(validator, executor)

    suspend fun execute(taskId: String, expectedRevision: Long): AgentTaskExecutionCoordinatorResult {
        val initial = dao.getTask(taskId)
            ?: return rejected("Поручение не найдено.")
        if (initial.revision != expectedRevision) return rejected("Поручение изменилось. Обнови список.")
        val plan = AgentPlanJsonCodec.decode(initial.planJson)
            ?: return rejected("Сохранённый план повреждён или отсутствует.")
        val planHash = AgentPlanJsonCodec.sha256(AgentPlanJsonCodec.canonicalJson(plan))
        if (initial.planJson != AgentPlanJsonCodec.canonicalJson(plan) || initial.planHash != planHash) {
            return rejected("Контрольная сумма плана не совпадает. Выполнение заблокировано.")
        }
        val validation = validator.validate(plan)
        if (!validation.accepted || plan.steps.any { it.toolId !in agentAllowedToolIds }) {
            return rejected("План больше не соответствует доступным инструментам. Выполнение заблокировано.")
        }

        var revision = initial.revision
        when (initial.phase) {
            AgentPhase.APPROVED.name -> {
                val started = taskRepository.startApprovedExecution(
                    taskId = taskId,
                    expectedRevision = revision,
                    planHash = planHash,
                    eventId = UUID.randomUUID().toString(),
                    idempotencyKey = "task-start:$taskId:$revision",
                    occurredAtEpochMillis = System.currentTimeMillis()
                )
                revision = started.appliedStateForPhase(AgentPhase.RUNNING)?.revision
                    ?: return rejected("Поручение уже запускалось или не перешло в RUNNING. Повторный запуск заблокирован.")
            }
            AgentPhase.PAUSED.name -> {
                if (initial.resumePhase != AgentPhase.RUNNING.name) {
                    return rejected("Это поручение нельзя продолжить из текущего состояния.")
                }
                val resumed = taskRepository.transition(
                    transitionRequest(taskId, AgentWorkflowCommand.RESUME, revision, planHash)
                )
                revision = resumed.appliedStateForPhase(AgentPhase.RUNNING)?.revision
                    ?: return rejected("Поручение уже продолжено или не перешло в RUNNING. Повторный запуск заблокирован.")
            }
            AgentPhase.RUNNING.name -> return rejected("Поручение уже выполняется. Повторный запуск запрещён.")
            AgentPhase.VERIFYING.name -> return AgentTaskExecutionCoordinatorResult(
                AgentTaskExecutionCoordinatorStatus.NEEDS_VERIFICATION,
                "Все шаги уже завершены. Ожидается твоя проверка сохранённых результатов."
            )
            else -> return rejected("Поручение не одобрено или уже завершено.")
        }

        val storedSteps = dao.getTaskSteps(taskId)
        if (storedSteps.size != plan.steps.size ||
            storedSteps.map { it.stepId to it.planHash } != plan.steps.map { it.id to planHash }
        ) return failRunning(taskId, "Журнал шагов не совпадает с одобренным планом.")

        val completedIds = storedSteps.filter { it.status == AgentTaskStepEntity.COMPLETED }
            .mapTo(mutableSetOf()) { it.stepId }
        val remaining = plan.steps.filterNot { it.id in completedIds }
        if (remaining.isEmpty()) {
            return beginVerification(taskId, revision)
        }

        return try {
            val execution = engine.executeApprovedPlan(
                plan = plan.copy(steps = remaining),
                agentAllowedToolIds = agentAllowedToolIds,
                context = executionContext,
                onStepStarted = { step ->
                    val updated = dao.markTaskStepStarted(
                        taskId = taskId,
                        stepId = step.id,
                        planHash = planHash,
                        startedAtEpochMillis = System.currentTimeMillis()
                    )
                    check(updated == 1) { "Не удалось записать начало шага ${step.id}." }
                },
                onStepFinished = { step ->
                    val updated = dao.markTaskStepFinished(
                        taskId = taskId,
                        stepId = step.stepId,
                        planHash = planHash,
                        status = if (step.success) AgentTaskStepEntity.COMPLETED else AgentTaskStepEntity.FAILED,
                        output = step.output,
                        errorCode = step.errorCode,
                        finishedAtEpochMillis = System.currentTimeMillis()
                    )
                    check(updated == 1) { "Не удалось сохранить результат шага ${step.stepId}." }
                }
            )

            if (execution.status == AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION) {
                beginVerification(taskId, revision)
            } else if (execution.status in setOf(
                    AgentPlanExecutionStatus.APPROVAL_REQUIRED,
                    AgentPlanExecutionStatus.ACCESS_DENIED,
                    AgentPlanExecutionStatus.STEP_FAILED
                )
            ) {
                pauseRunning(
                    taskId,
                    revision,
                    "Выполнение остановлено на шаге ${execution.failedStepId ?: "неизвестно"} (${execution.status}). Исправь причину и нажми «Продолжить»."
                )
            } else {
                failRunning(
                    taskId,
                    "Выполнение заблокировано: ${execution.status}" +
                        (execution.failedStepId?.let { " · шаг $it" } ?: "")
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                dao.markRunningTaskStepsInterrupted(taskId, planHash, System.currentTimeMillis())
                val current = dao.getTask(taskId)
                if (current?.phase == AgentPhase.RUNNING.name) {
                    pauseRunning(taskId, current.revision, "Выполнение прервано. Можно безопасно продолжить оставшиеся шаги.")
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                dao.markRunningTaskStepsInterrupted(taskId, planHash, System.currentTimeMillis())
            }
            failRunning(
                taskId,
                failure.message ?: failure.javaClass.simpleName
            )
        }
    }

    private suspend fun beginVerification(
        taskId: String,
        revision: Long
    ): AgentTaskExecutionCoordinatorResult {
        val result = taskRepository.transition(
            transitionRequest(taskId, AgentWorkflowCommand.BEGIN_VERIFICATION, revision, null)
        )
        return if (result.stateForPhase(AgentPhase.VERIFYING) != null) {
            AgentTaskExecutionCoordinatorResult(
                AgentTaskExecutionCoordinatorStatus.NEEDS_VERIFICATION,
                "Все шаги выполнены. Проверь сохранённые результаты; до твоего подтверждения поручение не считается завершённым."
            )
        } else {
            AgentTaskExecutionCoordinatorResult(AgentTaskExecutionCoordinatorStatus.FAILED, "Поручение не перешло в VERIFYING: $result")
        }
    }

    private suspend fun pauseRunning(
        taskId: String,
        revision: Long,
        message: String
    ): AgentTaskExecutionCoordinatorResult {
        val result = taskRepository.transition(
            transitionRequest(taskId, AgentWorkflowCommand.PAUSE, revision, null)
        )
        return if (result.stateForPhase(AgentPhase.PAUSED) != null) {
            AgentTaskExecutionCoordinatorResult(AgentTaskExecutionCoordinatorStatus.PAUSED, message)
        } else {
            AgentTaskExecutionCoordinatorResult(AgentTaskExecutionCoordinatorStatus.FAILED, "Поручение не перешло в PAUSED: $result")
        }
    }

    private suspend fun failRunning(
        taskId: String,
        reason: String
    ): AgentTaskExecutionCoordinatorResult {
        val current = dao.getTask(taskId)
        if (current?.phase != AgentPhase.RUNNING.name) {
            return AgentTaskExecutionCoordinatorResult(AgentTaskExecutionCoordinatorStatus.FAILED, reason)
        }
        val result = taskRepository.transition(
            transitionRequest(taskId, AgentWorkflowCommand.FAIL, current.revision, null)
        )
        return if (result.stateForPhase(AgentPhase.FAILED) != null) {
            AgentTaskExecutionCoordinatorResult(AgentTaskExecutionCoordinatorStatus.FAILED, reason)
        } else {
            AgentTaskExecutionCoordinatorResult(AgentTaskExecutionCoordinatorStatus.FAILED, "$reason; статус не обновлён: $result")
        }
    }

    private fun transitionRequest(
        taskId: String,
        command: AgentWorkflowCommand,
        revision: Long,
        planHash: String?
    ) = AgentTaskTransitionRequest(
        taskId = taskId,
        command = command,
        expectedRevision = revision,
        eventId = UUID.randomUUID().toString(),
        idempotencyKey = "task-${command.name.lowercase()}:$taskId:$revision",
        occurredAtEpochMillis = System.currentTimeMillis(),
        planHash = planHash
    )

    private fun AgentTaskRepositoryResult.stateForPhase(phase: AgentPhase): AgentWorkflowState? = when (this) {
        is AgentTaskRepositoryResult.Applied -> state.takeIf { it.task.phase == phase }
        is AgentTaskRepositoryResult.Replayed -> state.takeIf { it.task.phase == phase }
        else -> null
    }

    private fun AgentTaskRepositoryResult.appliedStateForPhase(phase: AgentPhase): AgentWorkflowState? =
        (this as? AgentTaskRepositoryResult.Applied)?.state?.takeIf { it.task.phase == phase }

    private fun rejected(message: String) =
        AgentTaskExecutionCoordinatorResult(AgentTaskExecutionCoordinatorStatus.REJECTED, message)
}
