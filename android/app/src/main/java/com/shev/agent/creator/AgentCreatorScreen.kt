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
                            enabled = !busy && !modelState.busy && request.isNotBlank() && modelState.ready,
                            onClick = {
                                busy = true
                                status = "Локальная модель проектирует агента…"
                                scope.launch {
                                    try {
                                        val knownTools = modelViewModel.availableAgentToolIds()
                                        val creation = AgentCreator().createDraft(
                                            userRequest = request,
                                            knownToolIds = knownTools,
                                            existingAgentIds = definitions.map { it.agentId }.toSet(),
                                            generate = { prompt -> modelViewModel.generateAgentCreatorOutputFromPrompt(prompt) },
                                            existingAgentSummaries = definitions.map { "${it.agentId}: ${it.name} — ${it.purpose}" }
                                        )
                                        if (creation is AgentCreationResult.NeedsClarification) {
                                            val questions = creation.questions.mapIndexed { index, question -> (index + 1).toString() + ". " + question }.joinToString("\n")
                                            status = "Нужно уточнить перед созданием:\n" + questions
                                            request = request.trim() + "\n\nОтветы на уточнения:\n"
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
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        status = failure.message ?: "Не удалось создать паспорт агента."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text(if (busy) "Создаю…" else "Спроектировать агента") }
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
            AgentDefinitionCard(definition) {
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
                        status = "Паспорт одобрен тобой. Агент пока не активирован и не выполняет действия."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        status = "Не удалось одобрить паспорт: ${failure.message ?: "ошибка"}"
                    }
                }
            }
        }
        item {
            Text(
                "Безопасность: создание паспорта не активирует агента. Исполнение специализированных агентов, выдача им инструментов и изменение данных будут подключаться отдельным проверяемым этапом. До этого момента ни один созданный паспорт не может выполнять действия.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AgentDefinitionCard(definition: AgentDefinitionEntity, onApprove: () -> Unit) {
    val context = LocalContext.current
    var exportStatus by remember(definition.agentId) { mutableStateOf<String?>(null) }
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
                Text("Ожидает подключения безопасного runtime.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
