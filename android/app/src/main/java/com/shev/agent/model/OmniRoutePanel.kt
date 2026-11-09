package com.shev.agent.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optional OmniRoute/OpenAI-compatible gateway panel.
 * Credentials deliberately remain in Compose memory and are never persisted or logged.
 */
@Composable
fun OmniRoutePanel() {
    var baseUrl by remember { mutableStateOf("http://127.0.0.1:20128/v1") }
    var modelId by remember { mutableStateOf("auto") }
    var token by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Не подключено") }
    var busy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    val scope = rememberCoroutineScope()

    fun client(): OpenAiCompatibleClient = OpenAiCompatibleClient(
        OpenAiCompatibleConfig(baseUrl.trim(), modelId.trim(), token.trim())
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("OmniRoute · AI-шлюз", style = MaterialTheme.typography.titleLarge)
            Text(
                "Подключает SheV к моделям через единый OpenAI-совместимый API. OmniRoute должен быть запущен на компьютере или сервере.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Адрес OmniRoute") },
                supportingText = { Text("На телефоне укажи HTTPS-адрес сервера. 127.0.0.1 подходит только если шлюз работает на самом телефоне.") }
            )
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Модель / маршрут") },
                supportingText = { Text("Можно указать ID модели или настроенный маршрут OmniRoute, например auto.") }
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("API-ключ OmniRoute") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (busy) return@OutlinedButton
                        busy = true
                        status = "Проверяю подключение…"
                        scope.launch {
                            try {
                                val found = withContext(Dispatchers.IO) { client().listModels().map { it.id } }
                                models = found
                                status = "Подключено · моделей: ${found.size}"
                                if (modelId == "auto" && found.isNotEmpty()) {
                                    status += ". Проверь, поддерживает ли шлюз маршрут auto."
                                }
                            } catch (error: Exception) {
                                status = error.message ?: "Не удалось подключиться."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && token.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Проверить") }
                Button(
                    onClick = {
                        if (busy || prompt.isBlank()) return@Button
                        busy = true
                        answer = ""
                        status = "Запрашиваю модель…"
                        scope.launch {
                            try {
                                answer = withContext(Dispatchers.IO) { client().complete(prompt.trim()) }
                                status = "Ответ получен"
                            } catch (error: Exception) {
                                status = error.message ?: "Ошибка запроса."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && token.isNotBlank() && prompt.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Отправить") }
            }
            if (busy) CircularProgressIndicator()
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (models.isNotEmpty()) {
                Text("Доступные модели: ${models.take(12).joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Пробный запрос") },
                minLines = 2,
                maxLines = 4
            )
            if (answer.isNotBlank()) {
                Text("Ответ модели", style = MaterialTheme.typography.titleMedium)
                Text(answer)
            }
            Text(
                "Ключ хранится только в памяти экрана и исчезает после закрытия приложения. Не используй административный ключ manage для обычного чата.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
