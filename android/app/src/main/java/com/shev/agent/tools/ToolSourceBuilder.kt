package com.shev.agent.tools

/**
 * Генерирует черновик Kotlin-адаптера. Ничего не записывает и не исполняет.
 * Сгенерированный обработчик намеренно не выполняет реальных операций.
 */
object ToolSourceBuilder {
    private val classNamePattern = Regex("[A-Z][A-Za-z0-9]{1,63}")

    fun createAdapterProposal(
        toolId: String,
        className: String,
        title: String,
        description: String,
        rationale: String
    ): ToolChangeProposal {
        require(classNamePattern.matches(className)) { "Имя класса должно быть UpperCamelCase." }
        require(title.isNotBlank()) { "Название инструмента обязательно." }
        require(description.isNotBlank()) { "Описание инструмента обязательно." }

        val path = "projects/SheV-Agent/android/app/src/main/java/com/shev/agent/tools/$className.kt"
        val content = """
            package com.shev.agent.tools

            /**
             * $title
             * $description
             *
             * Черновик: реальная операция ещё не реализована.
             * Перед подключением добавьте обработку входных данных и тесты.
             */
            class $className : AgentTool {
                override fun execute(input: String): AgentToolResult {
                    return AgentToolResult(
                        success = false,
                        output = "Инструмент пока не реализован",
                        errorCode = "NOT_IMPLEMENTED"
                    )
                }
            }
        """.trimIndent() + "\n"

        return ToolChangeProposal(
            toolId = toolId,
            kind = ToolChangeKind.CREATE,
            relativePath = path,
            proposedContent = content,
            rationale = rationale
        )
    }
}
