package com.shev.agent

import org.junit.Assert.*
import org.junit.Test

class AgentTaskOrchestratorTest {
    private fun state(
        phase: AgentPhase = AgentPhase.PLANNING,
        requiresApproval: Boolean = true
    ) = AgentWorkflowState(
        AgentTask("task-1", "Исследовать проект", phase, requiresApproval)
    )

    @Test fun approvalIsRequiredBeforeProtectedTaskStarts() {
        val result = AgentTaskOrchestrator.transition(state(), AgentWorkflowCommand.REQUEST_START)
        assertTrue(result.accepted)
        assertEquals(AgentPhase.WAITING_APPROVAL, result.state.task.phase)
        assertEquals(1L, result.state.revision)
    }

    @Test fun approvedTaskCanStartAndEnterVerificationBeforeCompletion() {
        val waiting = AgentTaskOrchestrator.transition(state(), AgentWorkflowCommand.REQUEST_START).state
        val approved = AgentTaskOrchestrator.transition(waiting, AgentWorkflowCommand.APPROVE).state
        assertEquals(AgentPhase.APPROVED, approved.task.phase)

        val running = approved.copy(task = approved.task.copy(phase = AgentPhase.RUNNING))
        val verifying = AgentTaskOrchestrator.transition(running, AgentWorkflowCommand.BEGIN_VERIFICATION).state
        assertEquals(AgentPhase.VERIFYING, verifying.task.phase)

        val completed = AgentTaskOrchestrator.transition(verifying, AgentWorkflowCommand.COMPLETE)
        assertTrue(completed.accepted)
        assertEquals(AgentPhase.COMPLETED, completed.state.task.phase)
    }

    @Test fun completionCannotSkipVerification() {
        val running = AgentTaskOrchestrator.transition(
            state(requiresApproval = false), AgentWorkflowCommand.REQUEST_START
        ).state
        val result = AgentTaskOrchestrator.transition(running, AgentWorkflowCommand.COMPLETE)
        assertFalse(result.accepted)
        assertEquals(AgentWorkflowRejection.INVALID_PHASE, result.rejection)
        assertEquals(AgentPhase.RUNNING, result.state.task.phase)
    }

    @Test fun pauseAndResumeRestoreExactPhase() {
        val running = AgentTaskOrchestrator.transition(
            state(requiresApproval = false), AgentWorkflowCommand.REQUEST_START
        ).state
        val paused = AgentTaskOrchestrator.transition(running, AgentWorkflowCommand.PAUSE)
        assertEquals(AgentPhase.PAUSED, paused.state.task.phase)
        assertEquals(AgentPhase.RUNNING, paused.state.resumePhase)

        val resumed = AgentTaskOrchestrator.transition(paused.state, AgentWorkflowCommand.RESUME)
        assertTrue(resumed.accepted)
        assertEquals(AgentPhase.RUNNING, resumed.state.task.phase)
        assertNull(resumed.state.resumePhase)
    }

    @Test fun staleRevisionIsRejectedWithoutChangingState() {
        val initial = state()
        val result = AgentTaskOrchestrator.transition(
            initial, AgentWorkflowCommand.REQUEST_START, expectedRevision = 9
        )
        assertFalse(result.accepted)
        assertEquals(AgentWorkflowRejection.INVALID_REVISION, result.rejection)
        assertEquals(initial, result.state)
    }

    @Test fun approvalAloneCannotBeginVerification() {
        val waiting = AgentTaskOrchestrator.transition(state(), AgentWorkflowCommand.REQUEST_START).state
        val approved = AgentTaskOrchestrator.transition(waiting, AgentWorkflowCommand.APPROVE).state
        val verification = AgentTaskOrchestrator.transition(approved, AgentWorkflowCommand.BEGIN_VERIFICATION)
        assertFalse(verification.accepted)
        assertEquals(AgentWorkflowRejection.INVALID_PHASE, verification.rejection)
        assertEquals(AgentPhase.APPROVED, verification.state.task.phase)
    }

    @Test fun approvalCannotBeAppliedToAnUnrelatedPhase() {
        val result = AgentTaskOrchestrator.transition(
            state(AgentPhase.RUNNING), AgentWorkflowCommand.APPROVE
        )
        assertFalse(result.accepted)
        assertEquals(AgentPhase.RUNNING, result.state.task.phase)
    }
    @Test fun cancellationStopsTasksWaitingForApprovalOrPaused() {
        val waiting = AgentTaskOrchestrator.transition(
            state(), AgentWorkflowCommand.REQUEST_START
        ).state
        val cancelledWaiting = AgentTaskOrchestrator.transition(
            waiting, AgentWorkflowCommand.CANCEL
        )
        assertTrue(cancelledWaiting.accepted)
        assertEquals(AgentPhase.CANCELLED, cancelledWaiting.state.task.phase)

        val running = AgentTaskOrchestrator.transition(
            state(requiresApproval = false), AgentWorkflowCommand.REQUEST_START
        ).state
        val paused = AgentTaskOrchestrator.transition(running, AgentWorkflowCommand.PAUSE).state
        val cancelledPaused = AgentTaskOrchestrator.transition(paused, AgentWorkflowCommand.CANCEL)
        assertTrue(cancelledPaused.accepted)
        assertEquals(AgentPhase.CANCELLED, cancelledPaused.state.task.phase)

        val cannotResume = AgentTaskOrchestrator.transition(
            cancelledPaused.state, AgentWorkflowCommand.RESUME
        )
        assertFalse(cannotResume.accepted)
        assertEquals(AgentPhase.CANCELLED, cannotResume.state.task.phase)
    }

    @Test fun completedTaskCannotBeCancelled() {
        val running = AgentTaskOrchestrator.transition(
            state(requiresApproval = false), AgentWorkflowCommand.REQUEST_START
        ).state
        val verifying = AgentTaskOrchestrator.transition(running, AgentWorkflowCommand.BEGIN_VERIFICATION).state
        val completed = AgentTaskOrchestrator.transition(verifying, AgentWorkflowCommand.COMPLETE).state
        val cancelled = AgentTaskOrchestrator.transition(completed, AgentWorkflowCommand.CANCEL)
        assertFalse(cancelled.accepted)
        assertEquals(AgentPhase.COMPLETED, cancelled.state.task.phase)
    }

    @Test fun exhaustedRevisionIsRejectedWithoutOverflow() {
        val exhausted = state().copy(revision = Long.MAX_VALUE)
        val result = AgentTaskOrchestrator.transition(
            exhausted,
            AgentWorkflowCommand.REQUEST_START,
            expectedRevision = Long.MAX_VALUE
        )
        assertFalse(result.accepted)
        assertEquals(AgentWorkflowRejection.INVALID_REVISION, result.rejection)
        assertEquals(Long.MAX_VALUE, result.state.revision)
    }

}
