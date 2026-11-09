package com.shev.agent

enum class AgentPhase { IDLE, PLANNING, WAITING_APPROVAL, APPROVED, RUNNING, VERIFYING, COMPLETED, FAILED, PAUSED, CANCELLED }

data class AgentTask(
    val id: String,
    val title: String,
    val phase: AgentPhase,
    val requiresApproval: Boolean = true
)

object AgentPolicy {
    fun canExecute(task: AgentTask, approved: Boolean, paused: Boolean): Boolean {
        if (paused || task.phase == AgentPhase.PAUSED) return false
        if (task.requiresApproval && !approved) return false
        return task.phase == AgentPhase.APPROVED
    }

    fun nextPhase(task: AgentTask, approved: Boolean, paused: Boolean): AgentPhase {
        if (paused) return AgentPhase.PAUSED
        if (task.requiresApproval && !approved) {
            return if (task.phase == AgentPhase.PLANNING || task.phase == AgentPhase.WAITING_APPROVAL) {
                AgentPhase.WAITING_APPROVAL
            } else {
                task.phase
            }
        }
        return if (canExecute(task, approved, paused)) AgentPhase.RUNNING else task.phase
    }
}
