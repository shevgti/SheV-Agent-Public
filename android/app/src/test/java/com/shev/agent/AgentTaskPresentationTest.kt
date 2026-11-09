package com.shev.agent

import com.shev.agent.data.AgentTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskPresentationTest {
    private fun task(id: String, title: String, phase: String) = AgentTaskEntity(
        id = id, title = title, phase = phase, requiresApproval = true,
        createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L
    )

    @Test fun labelsDraftAndUnknownStatesClearly() {
        assertEquals("Черновик", AgentTaskPresentation.statusLabel("IDLE"))
        assertEquals("Неизвестный статус", AgentTaskPresentation.statusLabel("FUTURE_STATE"))
    }

    @Test fun filtersByTitleWithoutCaseSensitivity() {
        val tasks = listOf(task("1", "Проверить FotoPoryadok", "IDLE"), task("2", "Обновить память", "IDLE"))
        val result = AgentTaskPresentation.filter(tasks, "fotoporyadok", "Все")
        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test fun cancelledTasksHaveTerminalLabelAndFilter() {
        assertEquals("Отменена", AgentTaskPresentation.statusLabel(AgentPhase.CANCELLED.name))
        val tasks = listOf(
            task("1", "Отменённое поручение", AgentPhase.CANCELLED.name),
            task("2", "Ожидает подтверждения", AgentPhase.WAITING_APPROVAL.name)
        )
        assertEquals(
            listOf("1"),
            AgentTaskPresentation.filter(tasks, "", "Отменена").map { it.id }
        )
    }

    @Test fun approvedTaskClearlyWaitsForAnExecutor() {
        assertEquals("Одобрена — ждёт исполнителя", AgentTaskPresentation.statusLabel(AgentPhase.APPROVED.name))
        val tasks = listOf(task("1", "Одобрено", AgentPhase.APPROVED.name))
        assertEquals(listOf("1"), AgentTaskPresentation.filter(tasks, "", "Одобрена — ждёт исполнителя").map { it.id })
    }

    @Test fun filtersByHumanReadableStatus() {
        val tasks = listOf(task("1", "Черновик", "IDLE"), task("2", "Успех", "COMPLETED"))
        val result = AgentTaskPresentation.filter(tasks, "", "Завершена")
        assertEquals(listOf("2"), result.map { it.id })
        assertTrue(AgentTaskPresentation.filter(tasks, "", "Ошибка").isEmpty())
    }
}
