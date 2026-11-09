package com.shev.agent

import org.junit.Assert.*
import org.junit.Test

class AgentPolicyTest {
    private val task = AgentTask("task-1", "Изменить файл GitHub", AgentPhase.WAITING_APPROVAL)

    @Test fun approvalRequiredByDefault() {
        assertFalse(AgentPolicy.canExecute(task, approved = false, paused = false))
        assertEquals(AgentPhase.WAITING_APPROVAL, AgentPolicy.nextPhase(task, approved = false, paused = false))
    }

    @Test fun approvalDoesNotStartTaskBeforeExecutorIsReady() {
        assertFalse(AgentPolicy.canExecute(task, approved = true, paused = false))
        assertEquals(AgentPhase.WAITING_APPROVAL, AgentPolicy.nextPhase(task, approved = true, paused = false))
    }

    @Test fun approvedTaskCanStartWhenExecutorIsReady() {
        val approvedTask = task.copy(phase = AgentPhase.APPROVED)
        assertTrue(AgentPolicy.canExecute(approvedTask, approved = true, paused = false))
        assertEquals(AgentPhase.RUNNING, AgentPolicy.nextPhase(approvedTask, approved = true, paused = false))
    }

    @Test fun pausedAgentCannotStartTask() {
        assertFalse(AgentPolicy.canExecute(task.copy(phase = AgentPhase.APPROVED), approved = true, paused = true))
        assertEquals(AgentPhase.PAUSED, AgentPolicy.nextPhase(task.copy(phase = AgentPhase.APPROVED), approved = true, paused = true))
    }

    @Test fun completedTaskCannotBeRestarted() {
        val completed = task.copy(phase = AgentPhase.COMPLETED)
        assertFalse(AgentPolicy.canExecute(completed, approved = true, paused = false))
        assertEquals(AgentPhase.COMPLETED, AgentPolicy.nextPhase(completed, approved = true, paused = false))
    }

    @Test fun failedTaskCannotBeRestartedWithoutNewPlan() {
        val failed = task.copy(phase = AgentPhase.FAILED)
        assertFalse(AgentPolicy.canExecute(failed, approved = true, paused = false))
        assertEquals(AgentPhase.FAILED, AgentPolicy.nextPhase(failed, approved = true, paused = false))
    }

    @Test fun idleTaskDoesNotSkipPlanning() {
        val idle = task.copy(phase = AgentPhase.IDLE)
        assertFalse(AgentPolicy.canExecute(idle, approved = true, paused = false))
        assertEquals(AgentPhase.IDLE, AgentPolicy.nextPhase(idle, approved = true, paused = false))
    }
}
