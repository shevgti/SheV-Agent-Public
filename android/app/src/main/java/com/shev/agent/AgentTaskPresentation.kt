package com.shev.agent

import com.shev.agent.data.AgentTaskEntity

/** Human-readable queue presentation; does not imply that a draft is being executed. */
object AgentTaskPresentation {
    fun statusLabel(phase: String): String = when (phase) {
        AgentPhase.IDLE.name -> "Черновик"
        AgentPhase.PLANNING.name -> "Подготовка"
        AgentPhase.WAITING_APPROVAL.name -> "Ожидает подтверждения"
        AgentPhase.APPROVED.name -> "Одобрена — ждёт исполнителя"
        AgentPhase.RUNNING.name -> "Выполняется"
        AgentPhase.VERIFYING.name -> "Проверка"
        AgentPhase.COMPLETED.name -> "Завершена"
        AgentPhase.FAILED.name -> "Ошибка"
        AgentPhase.PAUSED.name -> "Приостановлена"
        AgentPhase.CANCELLED.name -> "Отменена"
        else -> "Неизвестный статус"
    }

    fun filter(tasks: List<AgentTaskEntity>, query: String, status: String): List<AgentTaskEntity> {
        val normalized = query.trim()
        return tasks.filter { task ->
            (normalized.isEmpty() || task.title.contains(normalized, ignoreCase = true)) &&
                (status == "Все" || statusLabel(task.phase) == status)
        }
    }

    val filters = listOf("Все", "Черновик", "Ожидает подтверждения", "Одобрена — ждёт исполнителя", "Выполняется", "Завершена", "Ошибка", "Отменена")
}
