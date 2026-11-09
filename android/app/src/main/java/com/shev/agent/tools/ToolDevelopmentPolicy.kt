package com.shev.agent.tools

/**
 * Описание изменения инструмента, подготовленного агентом.
 * Предложение само по себе не меняет исходники и не устанавливает инструмент.
 */
enum class ToolChangeKind { CREATE, UPDATE }

data class ToolChangeProposal(
    val toolId: String,
    val kind: ToolChangeKind,
    val relativePath: String,
    val proposedContent: String,
    val rationale: String
)

enum class ToolProposalStatus {
    READY_FOR_REVIEW,
    INVALID_TOOL_ID,
    UNSUPPORTED_PATH,
    EMPTY_CONTENT,
    CONTENT_TOO_LARGE,
    SECRET_LIKE_CONTENT,
    EMPTY_RATIONALE,
    TARGET_ALREADY_EXISTS,
    TARGET_NOT_FOUND
}

data class ToolProposalValidation(
    val status: ToolProposalStatus,
    val explanation: String
)

/**
 * Локальная предварительная проверка предложений по созданию/улучшению инструментов.
 *
 * Разрешены только Kotlin-файлы в выделенном каталоге адаптеров и тестов.
 * Политика не компилирует и не исполняет предложенный код, не записывает его в проект,
 * не меняет Gradle/workflow/манифесты и не предоставляет разрешения.
 */
object ToolDevelopmentPolicy {
    const val MAX_CONTENT_CHARS = 50_000

    private val toolIdPattern = Regex("[a-z][a-z0-9_.-]{1,63}")
    private val allowedPathPattern = Regex(
        """projects/SheV-Agent/android/app/src/(main|test)/java/com/shev/agent/tools/[A-Za-z0-9_]+\.kt"""
    )
    private val secretPatterns = listOf(
        Regex("""(?i)(api[_-]?key|access[_-]?token|client[_-]?secret|password)\s*[:=]\s*["'][^"']{8,}["']"""),
        Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"""),
        Regex("""\bAKIA[0-9A-Z]{16}\b""")
    )

    fun validate(
        proposal: ToolChangeProposal,
        existingRelativePaths: Set<String> = emptySet()
    ): ToolProposalValidation {
        if (!toolIdPattern.matches(proposal.toolId)) {
            return ToolProposalValidation(ToolProposalStatus.INVALID_TOOL_ID, "Некорректный идентификатор инструмента.")
        }
        if (!allowedPathPattern.matches(proposal.relativePath) || ".." in proposal.relativePath) {
            return ToolProposalValidation(ToolProposalStatus.UNSUPPORTED_PATH, "Изменять можно только Kotlin-файлы каталога tools или его тестов.")
        }
        val targetExists = proposal.relativePath in existingRelativePaths
        if (proposal.kind == ToolChangeKind.CREATE && targetExists) {
            return ToolProposalValidation(ToolProposalStatus.TARGET_ALREADY_EXISTS, "Файл уже существует: создание не должно его перезаписывать.")
        }
        if (proposal.kind == ToolChangeKind.UPDATE && !targetExists) {
            return ToolProposalValidation(ToolProposalStatus.TARGET_NOT_FOUND, "Файл для обновления не найден; создание и обновление должны быть разными операциями.")
        }
        if (proposal.proposedContent.isBlank()) {
            return ToolProposalValidation(ToolProposalStatus.EMPTY_CONTENT, "Содержимое предложения пустое.")
        }
        if (proposal.proposedContent.length > MAX_CONTENT_CHARS) {
            return ToolProposalValidation(ToolProposalStatus.CONTENT_TOO_LARGE, "Предложение превышает допустимый размер.")
        }
        if (secretPatterns.any { it.containsMatchIn(proposal.proposedContent) }) {
            return ToolProposalValidation(ToolProposalStatus.SECRET_LIKE_CONTENT, "Похоже, предложение содержит секрет или учётные данные.")
        }
        if (proposal.rationale.isBlank()) {
            return ToolProposalValidation(ToolProposalStatus.EMPTY_RATIONALE, "Нужно объяснить цель изменения.")
        }
        return ToolProposalValidation(
            ToolProposalStatus.READY_FOR_REVIEW,
            "Предложение прошло предварительные проверки. Нужны diff, тесты и отдельное подтверждение перед применением."
        )
    }
}
