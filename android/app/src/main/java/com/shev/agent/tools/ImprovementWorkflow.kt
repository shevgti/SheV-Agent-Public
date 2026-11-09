package com.shev.agent.tools

/**
 * Контролируемый жизненный цикл улучшения.
 * Эта модель проверяет переходы и не применяет код к проекту.
 */
enum class ImprovementStage {
    DRAFT, REVIEW_REQUIRED, TESTING, TEST_REPORT_SUBMITTED, TESTS_PASSED,
    TESTS_FAILED, APPROVAL_REQUIRED, APPROVED, APPLIED, REJECTED
}

data class ImprovementRecord(
    val id: String,
    val summary: String,
    val stage: ImprovementStage = ImprovementStage.DRAFT,
    val diffReviewed: Boolean = false,
    val testReportId: String? = null,
    val approvalGranted: Boolean = false,
    val expectedHeadSha: String? = null
)

enum class ImprovementAction {
    SUBMIT_FOR_REVIEW, MARK_DIFF_REVIEWED, START_TESTS, SUBMIT_TEST_REPORT,
    RECORD_TEST_SUCCESS, RECORD_TEST_FAILURE, REQUEST_APPROVAL, APPROVE, APPLY, REJECT
}

data class ImprovementTransition(
    val accepted: Boolean,
    val record: ImprovementRecord,
    val explanation: String
)

object ImprovementWorkflow {
    fun transition(
        record: ImprovementRecord,
        action: ImprovementAction,
        testReportId: String? = null,
        verifiedEvidence: GitHubActionsVerificationResult.Verified? = null
    ): ImprovementTransition {
        fun reject(reason: String) = ImprovementTransition(false, record, reason)
        fun accept(updated: ImprovementRecord, reason: String) = ImprovementTransition(true, updated, reason)

        if (record.id.isBlank() || record.summary.isBlank()) return reject("Улучшение должно иметь идентификатор и описание.")

        return when (action) {
            ImprovementAction.SUBMIT_FOR_REVIEW ->
                if (record.stage != ImprovementStage.DRAFT) reject("На проверку отправляется только черновик.")
                else accept(record.copy(stage = ImprovementStage.REVIEW_REQUIRED), "Черновик отправлен на проверку.")
            ImprovementAction.MARK_DIFF_REVIEWED ->
                if (record.stage != ImprovementStage.REVIEW_REQUIRED) reject("Diff можно отметить проверенным только на этапе review.")
                else accept(record.copy(diffReviewed = true), "Diff отмечен как просмотренный.")
            ImprovementAction.START_TESTS ->
                if (record.stage != ImprovementStage.REVIEW_REQUIRED || !record.diffReviewed) reject("Перед тестированием необходимо просмотреть diff.")
                else accept(record.copy(stage = ImprovementStage.TESTING, testReportId = null), "Тестирование начато.")
            ImprovementAction.SUBMIT_TEST_REPORT ->
                if (record.stage != ImprovementStage.TESTING || testReportId.isNullOrBlank()) reject("Отправка отчёта требует активного тестирования и ID отчёта.")
                else accept(record.copy(stage = ImprovementStage.TEST_REPORT_SUBMITTED, testReportId = testReportId), "ID отчёта сохранён, но результат ещё не проверен.")
            ImprovementAction.RECORD_TEST_SUCCESS -> {
                val proof = verifiedEvidence
                if (record.stage !in setOf(ImprovementStage.TESTING, ImprovementStage.TEST_REPORT_SUBMITTED) ||
                    proof == null || testReportId.isNullOrBlank() ||
                    testReportId != proof.runId.toString() ||
                    record.expectedHeadSha.isNullOrBlank() ||
                    !record.expectedHeadSha.equals(proof.headSha, ignoreCase = true) ||
                    proof.repository != "shevgti/SheV-Memory" ||
                    proof.workflowPath != ".github/workflows/shev-agent-android.yml" ||
                    proof.jobsCount <= 0
                ) reject("Успех требует проверенного GitHub CI-доказательства с точным run ID, SHA, репозиторием, workflow и jobs.")
                else accept(record.copy(stage = ImprovementStage.TESTS_PASSED, testReportId = testReportId), "GitHub Actions подтвердил успешный запуск и все jobs.")
            }
            ImprovementAction.RECORD_TEST_FAILURE ->
                if (record.stage != ImprovementStage.TESTING || testReportId.isNullOrBlank()) reject("Неуспешный результат требует активного тестирования и ID отчёта.")
                else accept(record.copy(stage = ImprovementStage.TESTS_FAILED, testReportId = testReportId), "Ошибка тестирования сохранена.")
            ImprovementAction.REQUEST_APPROVAL ->
                if (record.stage != ImprovementStage.TESTS_PASSED) reject("Запрос подтверждения возможен только после успешных тестов.")
                else accept(record.copy(stage = ImprovementStage.APPROVAL_REQUIRED), "Ожидается решение пользователя.")
            ImprovementAction.APPROVE ->
                if (record.stage != ImprovementStage.APPROVAL_REQUIRED) reject("Подтвердить можно только изменение, ожидающее решения.")
                else accept(record.copy(stage = ImprovementStage.APPROVED, approvalGranted = true), "Пользовательское подтверждение зафиксировано.")
            ImprovementAction.APPLY ->
                if (record.stage != ImprovementStage.APPROVED || !record.approvalGranted) reject("Применение запрещено без успешных тестов и явного подтверждения.")
                else accept(record.copy(stage = ImprovementStage.APPLIED), "Изменение отмечено применённым.")
            ImprovementAction.REJECT ->
                if (record.stage == ImprovementStage.APPLIED || record.stage == ImprovementStage.REJECTED) reject("Завершённое улучшение нельзя отклонить повторно.")
                else accept(record.copy(stage = ImprovementStage.REJECTED, approvalGranted = false), "Предложение отклонено.")
        }
    }
}
