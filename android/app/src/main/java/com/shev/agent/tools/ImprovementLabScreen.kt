package com.shev.agent.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shev.agent.BuildConfig
import com.shev.agent.data.AgentDao
import com.shev.agent.data.AgentEventEntity
import com.shev.agent.data.ImprovementEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val VERIFIED_REPOSITORY = "shevgti/SheV-Memory"
private const val VERIFIED_WORKFLOW = ".github/workflows/shev-agent-android.yml"

@Composable
fun ImprovementLabScreen(dao: AgentDao, tokenStore: GitHubTokenStore) {
    var proposals by remember { mutableStateOf(emptyList<ImprovementEntity>()) }
    var history by remember { mutableStateOf(emptyList<AgentEventEntity>()) }
    var summary by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf("") }
    var reportIds by remember { mutableStateOf(emptyMap<String, String>()) }
    var shaInputs by remember { mutableStateOf(emptyMap<String, String>()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(dao) { dao.observeImprovements().collect { proposals = it } }
    LaunchedEffect(dao) {
        dao.observeEvents().collect { history = it.filter { event -> event.eventType.startsWith("IMPROVEMENT_") } }
    }

    fun saveTransition(item: ImprovementEntity, action: ImprovementAction, report: String? = null, result: String? = null, expectedSha: String? = item.expectedHeadSha, proof: GitHubActionsVerificationResult.Verified? = null) {
        val stage = runCatching { ImprovementStage.valueOf(item.stage) }.getOrDefault(ImprovementStage.DRAFT)
        val current = ImprovementRecord(item.id, item.summary, stage, item.diffReviewed, item.testReportId, item.approvalGranted, expectedSha)
        val transition = ImprovementWorkflow.transition(current, action, report, proof)
        if (!transition.accepted) { message = transition.explanation; return }
        val now = System.currentTimeMillis()
        val updated = item.copy(
            stage = transition.record.stage.name,
            diffReviewed = transition.record.diffReviewed,
            testReportId = transition.record.testReportId,
            testResult = result ?: item.testResult,
            approvalGranted = transition.record.approvalGranted,
            expectedHeadSha = expectedSha,
            updatedAtEpochMillis = now
        )
        scope.launch {
            dao.saveImprovement(updated)
            dao.appendEvent(AgentEventEntity(UUID.randomUUID().toString(), item.id, "IMPROVEMENT_${action.name}", transition.explanation, now))
            message = transition.explanation
        }
    }

    fun verifyCi(item: ImprovementEntity) {
        val runId = reportIds[item.id]?.trim()?.toLongOrNull()
        val enteredSha = (shaInputs[item.id] ?: item.expectedHeadSha.orEmpty()).trim()
        if (runId == null || runId <= 0L || !enteredSha.matches(Regex("[0-9a-fA-F]{40}"))) {
            message = "Укажите числовой ID запуска и полный 40-символьный SHA коммита."
            return
        }
        if (item.expectedHeadSha != null && !item.expectedHeadSha.equals(enteredSha, ignoreCase = true)) {
            message = "SHA уже закреплён за этим предложением и не может быть заменён."
            return
        }
        busyId = item.id
        message = "Проверяю запуск GitHub Actions и все его jobs…"
        scope.launch {
            val client = AuthenticatedGitHubActionsClient(tokenStore, BuildConfig.GITHUB_CLIENT_ID)
            val result = withContext(Dispatchers.IO) {
                client.verifyRun(VERIFIED_REPOSITORY, VERIFIED_WORKFLOW, enteredSha.lowercase(), runId)
            }
            when (result) {
                is GitHubActionsVerificationResult.Verified -> {
                    saveTransition(
                        item, ImprovementAction.RECORD_TEST_SUCCESS, result.runId.toString(),
                        "CI SUCCESS · ${result.jobsCount} jobs · SHA ${result.headSha.take(12)}",
                        enteredSha.lowercase(), result
                    )
                }
                is GitHubActionsVerificationResult.Rejected -> {
                    message = "Проверка отклонена: ${result.reason}"
                    if (item.expectedHeadSha == null) {
                        val now = System.currentTimeMillis()
                        scope.launch { dao.saveImprovement(item.copy(expectedHeadSha = enteredSha.lowercase(), updatedAtEpochMillis = now)) }
                    }
                }
                is GitHubActionsVerificationResult.Unavailable -> {
                    message = "Проверка недоступна: ${result.reason}"
                    if (item.expectedHeadSha == null) {
                        val now = System.currentTimeMillis()
                        scope.launch { dao.saveImprovement(item.copy(expectedHeadSha = enteredSha.lowercase(), updatedAtEpochMillis = now)) }
                    }
                }
            }
            busyId = null
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Improvement Lab", style = MaterialTheme.typography.headlineSmall)
        Text("CI проверяется напрямую через GitHub API. Одобрение доступно только после подтверждения точного SHA, workflow и всех jobs.")
        OutlinedTextField(value = summary, onValueChange = { summary = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Что улучшить?") })
        OutlinedTextField(value = diff, onValueChange = { diff = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Предлагаемый diff / описание изменений") }, minLines = 3)
        Button(
            onClick = {
                val title = summary.trim()
                if (title.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val id = UUID.randomUUID().toString()
                    val item = ImprovementEntity(id, title, diff.trim(), ImprovementStage.DRAFT.name, false, null, null, false, now, now)
                    scope.launch {
                        dao.saveImprovement(item)
                        dao.appendEvent(AgentEventEntity(UUID.randomUUID().toString(), id, "IMPROVEMENT_CREATED", "Создан черновик улучшения.", now))
                        summary = ""; diff = ""
                    }
                }
            },
            enabled = summary.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить предложение") }

        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text("Журнал улучшений", style = MaterialTheme.typography.titleLarge)
        if (proposals.isEmpty()) Text("Пока предложений нет.")

        proposals.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.summary, style = MaterialTheme.typography.titleMedium)
                    Text("Статус: ${item.stage}")
                    Text("Предлагаемые изменения", style = MaterialTheme.typography.labelLarge)
                    Text(item.diffPreview.ifBlank { "Описание diff пока не добавлено." })
                    item.expectedHeadSha?.let { Text("Закреплённый SHA: ${it.take(12)}…") }
                    if (item.testReportId != null) Text("CI run: ${item.testReportId} · ${item.testResult ?: "результат не указан"}")

                    when (item.stage) {
                        ImprovementStage.DRAFT.name -> Button(onClick = { saveTransition(item, ImprovementAction.SUBMIT_FOR_REVIEW) }) { Text("Отправить на review") }
                        ImprovementStage.REVIEW_REQUIRED.name -> {
                            Button(onClick = { saveTransition(item, ImprovementAction.MARK_DIFF_REVIEWED) }) { Text("Я просмотрел diff") }
                            OutlinedButton(onClick = { saveTransition(item, ImprovementAction.REJECT) }) { Text("Отклонить") }
                            if (item.diffReviewed) Button(onClick = { saveTransition(item, ImprovementAction.START_TESTS) }) { Text("Начать тестирование") }
                        }
                        ImprovementStage.TESTING.name, ImprovementStage.TEST_REPORT_SUBMITTED.name -> {
                            OutlinedTextField(
                                value = reportIds[item.id] ?: item.testReportId.orEmpty(),
                                onValueChange = { reportIds = reportIds + (item.id to it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("ID запуска GitHub Actions") }
                            )
                            OutlinedTextField(
                                value = shaInputs[item.id] ?: item.expectedHeadSha.orEmpty(),
                                onValueChange = { if (item.expectedHeadSha == null) shaInputs = shaInputs + (item.id to it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Полный SHA коммита (40 символов)") },
                                enabled = item.expectedHeadSha == null
                            )
                            Button(
                                onClick = { verifyCi(item) },
                                enabled = busyId != item.id && (reportIds[item.id] ?: item.testReportId.orEmpty()).isNotBlank()
                            ) { Text(if (busyId == item.id) "Проверяю…" else "Проверить CI автоматически") }
                            Text("Ручной ID не считается доказательством. Приложение сверит репозиторий, workflow, SHA, run ID и полный список jobs.")
                        }
                        ImprovementStage.TESTS_PASSED.name -> Button(onClick = { saveTransition(item, ImprovementAction.REQUEST_APPROVAL) }) { Text("Запросить моё подтверждение") }
                        ImprovementStage.APPROVAL_REQUIRED.name -> {
                            Text("Требуется решение пользователя. Код ещё не применён.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { saveTransition(item, ImprovementAction.APPROVE) }) { Text("Одобрить") }
                                OutlinedButton(onClick = { saveTransition(item, ImprovementAction.REJECT) }) { Text("Отклонить") }
                            }
                        }
                        ImprovementStage.TESTS_FAILED.name -> Text("Тесты не прошли. Одобрение заблокировано.")
                        ImprovementStage.APPROVED.name -> Text("Одобрено. Фактическое применение к проекту пока не реализовано.")
                        ImprovementStage.APPLIED.name -> Text("Отмечено применённым.")
                        ImprovementStage.REJECTED.name -> Text("Предложение отклонено.")
                    }
                }
            }
        }

        Text("История действий", style = MaterialTheme.typography.titleLarge)
        if (history.isEmpty()) Text("История появится после первого предложения.")
        history.forEach { event ->
            Text("• ${event.message}\n" + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.createdAtEpochMillis)), style = MaterialTheme.typography.bodySmall)
        }
    }
}
