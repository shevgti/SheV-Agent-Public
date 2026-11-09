package com.shev.agent

/**
 * Deterministic lifecycle reducer for a single agent task.
 *
 * This class never invokes tools or performs I/O. The caller must persist the
 * returned state/event transactionally before dispatching any side effect.
 */
data class AgentWorkflowState(
    val task: AgentTask,
    val resumePhase: AgentPhase? = null,
    val revision: Long = 0
)

enum class AgentWorkflowCommand {
    REQUEST_START,
    APPROVE,
    BEGIN_VERIFICATION,
    COMPLETE,
    FAIL,
    CANCEL,
    PAUSE,
    RESUME
}

enum class AgentWorkflowRejection {
    INVALID_REVISION,
    INVALID_PHASE,
    APPROVAL_REQUIRED,
    NOT_PAUSED,
    NO_RESUME_PHASE
}

data class AgentWorkflowTransition(
    val accepted: Boolean,
    val state: AgentWorkflowState,
    val rejection: AgentWorkflowRejection? = null
)

object AgentTaskOrchestrator {
    fun transition(
        state: AgentWorkflowState,
        command: AgentWorkflowCommand,
        expectedRevision: Long = state.revision
    ): AgentWorkflowTransition {
        fun reject(reason: AgentWorkflowRejection) =
            AgentWorkflowTransition(false, state, reason)

        fun accept(
            phase: AgentPhase,
            resumePhase: AgentPhase? = null
        ) = AgentWorkflowTransition(
            true,
            state.copy(
                task = state.task.copy(phase = phase),
                resumePhase = resumePhase,
                revision = state.revision + 1
            )
        )

        if (state.revision < 0L ||
            expectedRevision != state.revision ||
            state.revision == Long.MAX_VALUE
        ) {
            return reject(AgentWorkflowRejection.INVALID_REVISION)
        }

        return when (command) {
            AgentWorkflowCommand.REQUEST_START -> when (state.task.phase) {
                AgentPhase.PLANNING -> {
                    if (state.task.requiresApproval) accept(AgentPhase.WAITING_APPROVAL)
                    else accept(AgentPhase.RUNNING)
                }
                else -> reject(AgentWorkflowRejection.INVALID_PHASE)
            }
            AgentWorkflowCommand.APPROVE ->
                if (state.task.phase == AgentPhase.WAITING_APPROVAL) accept(AgentPhase.APPROVED)
                else reject(AgentWorkflowRejection.INVALID_PHASE)

            AgentWorkflowCommand.BEGIN_VERIFICATION ->
                if (state.task.phase == AgentPhase.RUNNING) accept(AgentPhase.VERIFYING)
                else reject(AgentWorkflowRejection.INVALID_PHASE)

            AgentWorkflowCommand.COMPLETE ->
                if (state.task.phase == AgentPhase.VERIFYING) accept(AgentPhase.COMPLETED)
                else reject(AgentWorkflowRejection.INVALID_PHASE)

            AgentWorkflowCommand.FAIL ->
                if (state.task.phase in ACTIVE_PHASES) accept(AgentPhase.FAILED)
                else reject(AgentWorkflowRejection.INVALID_PHASE)

            AgentWorkflowCommand.CANCEL ->
                if (state.task.phase in ACTIVE_PHASES) accept(AgentPhase.CANCELLED)
                else reject(AgentWorkflowRejection.INVALID_PHASE)

            AgentWorkflowCommand.PAUSE ->
                if (state.task.phase in PAUSABLE_PHASES) accept(
                    AgentPhase.PAUSED,
                    resumePhase = state.task.phase
                ) else reject(AgentWorkflowRejection.INVALID_PHASE)

            AgentWorkflowCommand.RESUME ->
                if (state.task.phase != AgentPhase.PAUSED) {
                    reject(AgentWorkflowRejection.NOT_PAUSED)
                } else {
                    val phase = state.resumePhase
                        ?: return reject(AgentWorkflowRejection.NO_RESUME_PHASE)
                    accept(phase)
                }
        }
    }

    private val ACTIVE_PHASES = setOf(
        AgentPhase.PLANNING,
        AgentPhase.WAITING_APPROVAL,
        AgentPhase.APPROVED,
        AgentPhase.RUNNING,
        AgentPhase.VERIFYING,
        AgentPhase.PAUSED
    )

    private val PAUSABLE_PHASES = setOf(
        AgentPhase.PLANNING,
        AgentPhase.WAITING_APPROVAL,
        AgentPhase.APPROVED,
        AgentPhase.RUNNING,
        AgentPhase.VERIFYING
    )
}
