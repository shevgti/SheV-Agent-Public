package com.shev.chat

/** Builds an explicit, project-scoped handoff prompt for the official ChatGPT app. */
object ChatProjectContext {
    const val MEMORY_CHECKPOINT_URL =
        "https://github.com/shevgti/SheV-Memory/blob/main/ACTIVE-CHECKPOINT.md"

    fun build(project: String): String {
        val projectLine = when (project) {
            "ФотоПорядок" -> "Проект: ФотоПорядок. Продолжай работу над Android-фотогалереей."
            "SheV Agent" -> "Проект: SheV Agent. Продолжай работу над Android-помощником."
            "Моя память" -> "Проект: Моя память. Продолжай работу над календарём и системой напоминаний."
            else -> "Проект: $project. Сначала найди его актуальный паспорт в SheV-Memory."
        }
        return """
            $projectLine
            Перед ответом восстанови подтверждённое состояние проекта из SheV-Memory.
            Начни с единой точки входа: $MEMORY_CHECKPOINT_URL
            Затем открой только относящиеся к выбранному проекту паспорт и последние доказательные записи.
            Не проси пользователя повторять уже сохранённое. Не утверждай, что что-либо изменил или сохранил, пока это не подтверждено фактическим результатом.
            После существенной работы подготовь обновление ACTIVE-CHECKPOINT.md и проверь его сохранение.
            Важно: этот текст передаётся через буфер обмена. SheV Chat не читает историю ChatGPT и не отправляет сообщение автоматически.
        """.trimIndent()
    }
}
