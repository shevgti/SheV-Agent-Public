package com.shev.agent.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Локальный экран Tool Workshop. Создаёт предложения и показывает diff;
 * файловые операции и запуск кода здесь намеренно отсутствуют.
 */
@Composable
fun ToolWorkshopScreen() {
    var toolId by remember { mutableStateOf("sample.tool") }
    var className by remember { mutableStateOf("SampleTool") }
    var title by remember { mutableStateOf("Новый инструмент") }
    var description by remember { mutableStateOf("Черновик нового адаптера") }
    var rationale by remember { mutableStateOf("Подготовить основу инструмента") }
    var relativePath by remember {
        mutableStateOf("projects/SheV-Agent/android/app/src/main/java/com/shev/agent/tools/SampleTool.kt")
    }
    var oldSource by remember { mutableStateOf("") }
    var newSource by remember { mutableStateOf("") }
    var generatedSource by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Пока нет подготовленного предложения.") }
    var diffText by remember { mutableStateOf("") }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Мастерская инструментов", style = MaterialTheme.typography.titleLarge)
            Text(
                "Создавай новые адаптеры и готовь улучшения. Это безопасные черновики: они не записываются в проект и не запускаются."
            )
            OutlinedTextField(toolId, { toolId = it }, Modifier.fillMaxWidth(), label = { Text("ID инструмента") })
            OutlinedTextField(className, { className = it }, Modifier.fillMaxWidth(), label = { Text("Имя Kotlin-класса") })
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") })
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Описание") })
            OutlinedTextField(rationale, { rationale = it }, Modifier.fillMaxWidth(), label = { Text("Зачем нужен инструмент") })

            Button(
                onClick = {
                    runCatching {
                        val proposal = ToolSourceBuilder.createAdapterProposal(
                            toolId.trim(), className.trim(), title.trim(), description.trim(), rationale.trim()
                        )
                        val validation = ToolDevelopmentPolicy.validate(proposal)
                        generatedSource = proposal.proposedContent
                        newSource = proposal.proposedContent
                        diffText = ""
                        val findings = ToolSourceAnalyzer.analyze(generatedSource)
                        val scan = if (findings.isEmpty()) "Статический анализ: известных блокирующих шаблонов не найдено."
                            else findings.joinToString("\n") { "${it.severity}: ${it.message}" }
                        status = "${validation.status}: ${validation.explanation}\n$scan"
                    }.onFailure {
                        status = "Ошибка подготовки: ${it.message ?: "проверь поля"}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Создать черновик инструмента") }

            Text("Подготовить обновление существующего инструмента", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(relativePath, { relativePath = it }, Modifier.fillMaxWidth(), label = { Text("Путь существующего файла") })
            OutlinedTextField(
                oldSource, { oldSource = it }, Modifier.fillMaxWidth(),
                label = { Text("Текущий исходный код") }, minLines = 3
            )
            OutlinedTextField(
                newSource, { newSource = it }, Modifier.fillMaxWidth(),
                label = { Text("Предлагаемый новый код") }, minLines = 3
            )
            Button(
                onClick = {
                    runCatching {
                        val path = relativePath.trim()
                        val proposal = ToolUpdateBuilder.updateSourceProposal(
                            toolId.trim(), path, oldSource, newSource, rationale.trim(), setOf(path)
                        )
                        val validation = ToolDevelopmentPolicy.validate(proposal, setOf(path))
                        val diff = ToolDiffPreview.compare(oldSource, newSource)
                        diffText = diff.lines.joinToString("\n") { "${it.prefix} ${it.text}" } +
                            "\n\nДобавлено: ${diff.addedLines}, удалено: ${diff.removedLines}"
                        val findings = ToolSourceAnalyzer.analyze(newSource)
                        val scan = if (findings.isEmpty()) "Статический анализ: известных блокирующих шаблонов не найдено."
                            else findings.joinToString("\n") { "${it.severity}: ${it.message}" }
                        status = "${validation.status}: ${validation.explanation}\n$scan"
                    }.onFailure {
                        status = "Ошибка обновления: ${it.message ?: "проверь исходники"}"
                        diffText = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Проверить обновление и показать diff") }

            Text("Результат проверки", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (generatedSource.isNotBlank()) {
                Text("Сгенерированный исходник", style = MaterialTheme.typography.titleMedium)
                Text(generatedSource, style = MaterialTheme.typography.bodySmall)
            }
            if (diffText.isNotBlank()) {
                Text("Предварительный diff", style = MaterialTheme.typography.titleMedium)
                Text(diffText, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Изменение файлов и запуск кода пока не подключены. Для этого нужны отдельная изолированная сборка и подтверждение пользователя.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
