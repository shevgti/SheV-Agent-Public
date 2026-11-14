package com.shev.agent.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.shev.agent.data.AgentDao
import com.shev.agent.data.AgentDefinitionEntity
import com.shev.agent.model.LocalModelViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AgentCreatorScreen(dao: AgentDao, modelViewModel: LocalModelViewModel) {
    val definitions by dao.observeAgentDefinitions().collectAsState(initial = emptyList())
    val modelState by modelViewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Опиши специалиста, которого нужно создать.") }
    var busy by remember { mutableStateOf(false) }
    var clarificationPending by remember { mutableStateOf(false) }
    var useChatGptForCreator by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Фабрика ИИ-агентов", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "SheV Agent проектирует специализированных агентов по заданию, проверяет их паспорт и сохраняет паспорта локально.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Откуда берётся ИИ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Выбери интеллект, который будет проектировать паспорт специалиста. ChatGPT использует уже подключённый Codex-аккаунт; локальная модель работает непосредственно на телефоне.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !busy && modelState.codexConnected,
                            onClick = { useChatGptForCreator = true }
                        ) { Text(if (useChatGptForCreator) "✓ ChatGPT выбран" else "ChatGPT / Codex") }
                        OutlinedButton(
                            enabled = !busy && modelState.ready,
                            onClick = { useChatGptForCreator = false }
                        ) { Text(if (!useChatGptForCreator) "✓ Локальная модель" else "Локальная модель") }
                    }
                    Text(
                        when {
                            useChatGptForCreator && modelState.codexConnected -> "Источник конструктора: ChatGPT / Codex · подключён"
                            useChatGptForCreator -> "ChatGPT / Codex не подключён. Открой настройки и выполни вход."
                            modelState.ready -> "Источник конструктора: локальная модель · запущена"
                            modelState.verified -> "Источник конструктора: локальная модель · файл установлен, но модель не запущена"
                            else -> "Источник конструктора: локальная модель · модель ещё не установлена"
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = if ((useChatGptForCreator && modelState.codexConnected) || (!useChatGptForCreator && modelState.ready)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Инструменты с реальными обработчиками:", fontWeight = FontWeight.SemiBold)
                    val availableTools = modelViewModel.availableAgentTools()
                    if (availableTools.isEmpty()) {
                        Text(
                            "Каталог появится после успешного запуска локального исполнителя.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        availableTools.forEach { tool ->
                            Text(
                                "• ${tool.title} (${tool.id}) — ${tool.description}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        "Сам конструктор работает без инструментов. Список выше — каталог инструментов с реальными обработчиками. При создании паспорта модель может выбрать только эти инструменты; после твоего одобрения специалист получит только выбранный allowlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Создать нового агента", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = request,
                        onValueChange = { request = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        label = { Text("Какую работу должен выполнять агент?") },
                        placeholder = { Text("Например: исследователь Android-библиотек, который сравнивает лицензии, совместимость и готовит проверенный отчёт…") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = !busy && !modelState.busy && request.isNotBlank() && if (useChatGptForCreator) modelState.codexConnected else modelState.ready,
                            onClick = {
                                busy = true
                                status = if (useChatGptForCreator) "ChatGPT / Codex проектирует агента…" else "Локальная модель проектирует агента…"
                                scope.launch {
                                    try {
                                        val knownTools = modelViewModel.availableAgentToolIds()
                                        val creation = AgentCreator().createDraft(
                                            userRequest = request,
                                            knownToolIds = knownTools,
                                            existingAgentIds = definitions.map { it.agentId }.toSet(),
                                            knownToolDescriptions = modelViewModel.availableAgentTools().map { tool -> "${tool.id}: ${tool.title} — ${tool.description}; capabilities=${tool.capabilities}" },
                                            generate = { prompt -> if (useChatGptForCreator) modelViewModel.generateAgentCreatorOutputFromCodex(prompt) else modelViewModel.generateAgentCreatorOutputFromPrompt(prompt) },
                                            existingAgentSummaries = definitions.map { "${it.agentId}: ${it.name} — ${it.purpose}" },
                                            forceComplete = clarificationPending
                                        )
                                        if (creation is AgentCreationResult.NeedsClarification) {
                                            val questions = creation.questions.mapIndexed { index, question -> (index + 1).toString() + ". " + question }.joinToString("\n")
                                            clarificationPending = true
                                            status = "Последнее уточнение. Ответь в поле выше или оставь его пустым: при следующей попытке я создам паспорт с разумными настройками по умолчанию. Повторных вопросов не будет.\n" + questions
                                            request = request.trim() + "\n\nОтветы на уточнения (необязательно; можно оставить пустыми):\n"
                                            return@launch
                                        }
                                        val manifest = when (creation) {
                                            is AgentCreationResult.Created -> creation.manifest
                                            is AgentCreationResult.Rejected -> error("Проверка не пройдена: ${creation.reason}. ${creation.detail.orEmpty()}")
                                            is AgentCreationResult.ModelFailure -> error(creation.message)
                                            is AgentCreationResult.NeedsClarification -> error("Неожиданный этап уточнения.")
                                        }
                                        val markdown = AgentMarkdownRenderer.render(manifest)
                                        val now = System.currentTimeMillis()
                                        dao.saveAgentDefinition(
                                            AgentDefinitionEntity(
                                                agentId = manifest.id,
                                                name = manifest.name,
                                                purpose = manifest.purpose,
                                                manifestJson = Gson().toJson(manifest),
                                                agentMarkdown = markdown,
                                                status = AgentManifest.STATUS_DRAFT,
                                                version = manifest.version,
                                                createdAtEpochMillis = now,
                                                updatedAtEpochMillis = now
                                            )
                                        )
                                        status = "Паспорт создан, проверен и сохранён как черновик. Он ещё не получил доступ к инструментам."
                                        request = ""
                                        clarificationPending = false
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        status = failure.message ?: "Не удалось создать паспорт агента."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text(if (busy) "Создаю…" else if (clarificationPending) "Создать агента" else "Спроектировать агента") }
                        OutlinedButton(
                            enabled = !busy && !modelState.busy,
                            onClick = {
                                request = ""
                                clarificationPending = false
                                status = "Опиши специалиста, которого нужно создать."
                            }
                        ) { Text("Обновить задание") }
                        if (busy) CircularProgressIndicator()
                    }
                    if (!modelState.ready) {
                        Text(
                            if (modelState.verified) "Локальная модель установлена, но ещё не запущена."
                            else "Сначала установи локальную модель. Она будет работать на телефоне.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            enabled = !modelState.busy,
                            onClick = { if (modelState.verified) modelViewModel.launchAgent() else modelViewModel.download() }
                        ) {
                            Text(
                                when {
                                    modelState.busy -> "Подготавливаю модель…"
                                    modelState.verified -> "Запустить SheV"
                                    else -> "Скачать локальную модель"
                                }
                            )
                        }
                        modelState.progress?.let { progress ->
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                        modelState.error?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text("Локальная модель готова. Новые агенты проектируются без внешнего API.",
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Мои агенты · ${definitions.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        if (definitions.isEmpty()) item {
            Text("Пока нет созданных агентов. Первый паспорт появится здесь после проектирования.")
        }
        items(definitions, key = { it.agentId }) { definition ->
            AgentDefinitionCard(
                definition = definition,
                canRun = modelState.ready,
                onApprove = {
                    scope.launch {
                        try {
                            val approvedManifest = AgentManifestParser.parse(definition.manifestJson)
                                .copy(status = AgentManifest.STATUS_APPROVED)
                            dao.saveAgentDefinition(
                                definition.copy(
                                    manifestJson = Gson().toJson(approvedManifest),
                                    status = AgentManifest.STATUS_APPROVED,
                                    updatedAtEpochMillis = System.currentTimeMillis()
                                )
                            )
                            status = "Паспорт одобрен. Специалист запускается вручную и получает только перечисленные в паспорте инструменты."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            status = "Не удалось одобрить паспорт: ${failure.message ?: "ошибка"}"
                        }
                    }
                },
                onRun = { input -> modelViewModel.runApprovedSpecialist(definition.manifestJson, input) }
            )
        }
        item {
            Text(
                "Безопасность: одобрение не включает постоянную автономную работу. Специалист запускается вручную; сетевые инструменты допускаются только при READ_ONLY, все вызовы проходят через индивидуальный allowlist и CapabilityPolicy. Произвольного файлового доступа и изменения данных нет; опасные операции требуют отдельного подтверждения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AgentDefinitionCard(
    definition: AgentDefinitionEntity,
    canRun: Boolean,
    onApprove: () -> Unit,
    onRun: suspend (String) -> String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember(definition.agentId) { mutableStateOf<String?>(null) }
    var runInput by remember(definition.agentId) { mutableStateOf("") }
    var runOutput by remember(definition.agentId) { mutableStateOf<String?>(null) }
    var runError by remember(definition.agentId) { mutableStateOf<String?>(null) }
    var running by remember(definition.agentId) { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) {
            exportStatus = "Экспорт отменён. Черновик сохранён в приложении."
        } else {
            exportStatus = runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(definition.agentMarkdown)
                } ?: error("Не удалось открыть выбранный файл.")
                "Markdown-файл сохранён."
            }.getOrElse { "Ошибка экспорта: " + (it.message ?: "не удалось записать файл") }
        }
    }
    val manifest = remember(definition.manifestJson) {
        runCatching { AgentManifestParser.parse(definition.manifestJson) }.getOrNull()
    }
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(definition.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (definition.status == AgentManifest.STATUS_APPROVED) "Одобрен" else "Черновик", color = MaterialTheme.colorScheme.primary)
            }
            Text(definition.purpose)
            if (manifest != null) {
                Text("Роль: ${manifest.role}", style = MaterialTheme.typography.bodySmall)
                Text("Инструменты: ${manifest.allowedToolIds.joinToString().ifBlank { "не назначены" }}", style = MaterialTheme.typography.bodySmall)
                Text("Триггеры: ${manifest.triggers.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                Text("Проверка: ${manifest.verificationStrategy}", style = MaterialTheme.typography.bodySmall)
                Text("Ограничения: ${manifest.limitations.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Не удалось прочитать паспорт. Данные сохранены для диагностики.", color = MaterialTheme.colorScheme.error)
            }
            Text("ID: ${definition.agentId} · версия ${definition.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Предпросмотр файла .md", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(definition.agentMarkdown.take(1800), style = MaterialTheme.typography.bodySmall, maxLines = 24)
            Button(
                enabled = definition.agentMarkdown.isNotBlank() && definition.status == AgentManifest.STATUS_APPROVED,
                onClick = { exportLauncher.launch("${definition.agentId}.md") }
            ) { Text(if (definition.status == AgentManifest.STATUS_APPROVED) "Экспортировать Markdown-файл" else "Сначала одобри паспорт для экспорта") }
            exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (definition.status == AgentManifest.STATUS_DRAFT) {
                Button(onClick = onApprove) { Text("Одобрить паспорт") }
            } else {
                Text("Статус: одобрен · запуск только вручную", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (manifest != null && definition.status == AgentManifest.STATUS_APPROVED) {
                val runDecision = AgentRunPolicy.evaluate(manifest)
                if (!runDecision.allowed) {
                    Text(
                        runDecision.reason ?: "Запуск недоступен.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = runInput,
                        onValueChange = { runInput = it; runError = null },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        label = { Text("Задача для специалиста") },
                        placeholder = { Text("Опиши конкретную задачу…") },
                        enabled = !running
                    )
                    Button(
                        enabled = canRun && !running && runInput.isNotBlank(),
                        onClick = {
                            running = true
                            runOutput = null
                            runError = null
                            scope.launch {
                                try {
                                    runOutput = onRun(runInput.trim())
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    runError = failure.message ?: "Не удалось выполнить задачу."
                                } finally {
                                    running = false
                                }
                            }
                        }
                    ) { Text(if (running) "Выполняю…" else "Запустить специалиста") }
                    if (!canRun) {
                        Text("Сначала запусти локальную модель SheV.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (running) CircularProgressIndicator()
                    runError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    runOutput?.let {
                        Text("Результат запуска", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
