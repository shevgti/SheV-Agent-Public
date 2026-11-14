package com.shev.agent.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** User-facing review gate for skills proposed by the agent. */
@Composable
fun AgentSkillLibraryScreen(
    library: AgentSkillLibrary,
    modifier: Modifier = Modifier
) {
    val skills by library.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Библиотека навыков", style = MaterialTheme.typography.headlineSmall)
        Text(
            "SheV может предлагать навыки после решения задач. Черновики не влияют на ответы, пока ты не проверишь их и не активируешь.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (message != null) {
            Text(message!!, color = MaterialTheme.colorScheme.primary)
        }
        if (skills.isEmpty()) {
            Text("Пока навыков нет. Попроси SheV изучить полезную повторяемую задачу и сохранить найденный способ.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                skills.forEach { skill ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(skill.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text(
                                    when (skill.status) {
                                        AgentSkillEntity.STATUS_VERIFIED -> "Активен"
                                        AgentSkillEntity.STATUS_DRAFT -> "Черновик"
                                        else -> "Отключён"
                                    },
                                    color = if (skill.status == AgentSkillEntity.STATUS_VERIFIED)
                                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(skill.summary)
                            Text("Версия ${skill.version}", style = MaterialTheme.typography.labelSmall)
                            if (skill.sourceUrls.isNotBlank()) {
                                Text("Источники: ${skill.sourceUrls}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(skill.instructions, style = MaterialTheme.typography.bodySmall)
                            if (skill.status == AgentSkillEntity.STATUS_DRAFT) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            runCatching { library.activateAfterUserReview(skill.skillId) }
                                                .onSuccess { message = "Навык «${skill.title}» активирован и будет учитываться в следующих запросах." }
                                                .onFailure { message = it.message ?: "Не удалось активировать навык." }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Проверил — активировать")
                                }
                            } else if (skill.status == AgentSkillEntity.STATUS_VERIFIED) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val disabled = library.disable(skill.skillId)
                                            message = if (disabled) "Навык «${skill.title}» отключён." else "Не удалось отключить навык."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Отключить навык")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
