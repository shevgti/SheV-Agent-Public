package com.shev.chat

import com.shev.chat.data.ChatEntryEntity
import com.shev.chat.data.MemorySnapshot

/** Builds an explicit, project-scoped handoff prompt for the official ChatGPT app. */
object ChatProjectContext {
    const val MEMORY_CHECKPOINT_URL =
        "https://github.com/shevgti/SheV-Memory/blob/main/ACTIVE-CHECKPOINT.md"

    fun build(project: String, entries: List<ChatEntryEntity> = emptyList(), memory: MemorySnapshot? = null): String {
        val projectLine = when (project) {
            "ФотоПорядок" -> "Проект: ФотоПорядок. Продолжай работу над Android-фотогалереей."
            "SheV Agent" -> "Проект: SheV Agent. Продолжай работу над Android-помощником."
            "Моя память" -> "Проект: Моя память. Продолжай работу над календарём и системой напоминаний."
            else -> "Проект: $project. Сначала найди его актуальный паспорт в SheV-Memory."
        }
        val recentHistory = entries
            .sortedBy { it.createdAtEpochMillis }
            .takeLast(20)
            .joinToString("\n\n") { entry ->
                val speaker = if (entry.role == "ASSISTANT") "Ответ ChatGPT" else "Моё сообщение"
                "$speaker:\n${entry.content.take(2500)}"
            }
            .takeLast(12_000)

        return buildString {
            appendLine(projectLine)
            appendLine()
            appendLine("Перед ответом восстанови подтверждённое состояние проекта из SheV-Memory.")
            appendLine("Единая точка входа: $MEMORY_CHECKPOINT_URL")
            appendLine("Не проси пользователя повторять уже сохранённое. Не утверждай, что что-либо изменил или сохранил, пока это не подтверждено фактическим результатом.")
            appendLine("После существенной работы подготовь обновление ACTIVE-CHECKPOINT.md и проверь его сохранение.")
            if (memory != null && memory.project == project) {
                appendLine()
                appendLine("## Подтверждённый кэш SheV-Memory")
                appendLine("Последняя успешная синхронизация: ${memory.syncedAtEpochMillis}")
                appendLine("### Общая активная точка")
                appendLine(memory.activeCheckpoint.take(6_000))
                appendLine("### Паспорт выбранного проекта: $project")
                appendLine(memory.projectMemory.take(10_000))
            } else {
                appendLine("Локального кэша SheV-Memory пока нет. Не выдумывай статус проекта; открой ссылку и восстанови его из источника.")
            }
            appendLine("Важно: SheV Chat не читает историю ChatGPT и не отправляет сообщение автоматически.")
            if (recentHistory.isNotBlank()) {
                appendLine()
                appendLine("## Последние записи локальной истории SheV Chat (для контекста, не считать внешне синхронизированными)")
                appendLine(recentHistory)
            }
        }
    }
}
