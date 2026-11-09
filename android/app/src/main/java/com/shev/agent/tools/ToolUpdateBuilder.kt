package com.shev.agent.tools

/**
 * Подготавливает предложение обновления существующего Kotlin-инструмента.
 * Не записывает файл и не применяет изменения.
 */
object ToolUpdateBuilder {
    fun updateSourceProposal(
        toolId: String,
        relativePath: String,
        currentContent: String,
        proposedContent: String,
        rationale: String,
        existingRelativePaths: Set<String>
    ): ToolChangeProposal {
        require(currentContent.isNotBlank()) { "Текущий исходный код пуст." }
        require(proposedContent.isNotBlank()) { "Новый исходный код пуст." }
        require(currentContent != proposedContent) { "Изменения отсутствуют." }
        require(relativePath in existingRelativePaths) { "Файл для обновления не найден." }

        return ToolChangeProposal(
            toolId = toolId,
            kind = ToolChangeKind.UPDATE,
            relativePath = relativePath,
            proposedContent = proposedContent,
            rationale = rationale
        )
    }
}
