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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

/** Direct Gemini API using Google's documented OpenAI-compatible endpoint. */
@Composable
fun GeminiProviderPanel(viewModel: LocalModelViewModel) {
    val agentState by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("shev_gemini", android.content.Context.MODE_PRIVATE)
    }
    var modelId by remember { mutableStateOf("gemini-2.5-flash") }
    var apiKey by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("Привет! Представься по-русски и расскажи, чем можешь помочь.") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Не подключено") }
    var busy by remember { mutableStateOf(false) }
    var credentialsSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(preferences) {
        modelId = preferences.getString("model_id", modelId) ?: modelId
        apiKey = preferences.getString("encrypted_token", null)?.let { encrypted ->
            runCatching { GeminiSecretCipher.decrypt(encrypted) }.getOrDefault("")
        } ?: ""
        credentialsSaved = apiKey.isNotBlank()
        if (preferences.contains("encrypted_token") && !credentialsSaved) {
            status = "Сохранённый ключ недоступен. Введи его заново."
        }
    }

    fun markDirty() {
        credentialsSaved = false
        if (agentState.useGemini) viewModel.setGeminiPrimaryEnabled(false)
    }

    fun client() = OpenAiCompatibleClient(
        OpenAiCompatibleConfig(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            modelId.trim(),
            apiKey.trim()
        )
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
            Text("Google Gemini · бесплатный тариф", style = MaterialTheme.typography.titleLarge)
            Text(
                "Начинаем с gemini-2.5-flash. У Google есть бесплатный лимит с ограничениями по запросам и доступности. Ключ создаётся в AI Studio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://aistudio.google.com/apikey")
                        ))
                        status = "Создай ключ Gemini API и скопируй его. Затем вернись сюда."
                    }.onFailure { status = "Не удалось открыть Google AI Studio." }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("1 · Получить бесплатный API-ключ Gemini") }
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it; markDirty() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Модель Gemini") },
                supportingText = { Text("По умолчанию gemini-2.5-flash. Бесплатность и лимиты зависят от доступности модели в регионе и проекта.") }
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; markDirty() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Ключ Gemini API") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            require(modelId.isNotBlank()) { "Укажи ID модели Gemini." }
                            require(apiKey.isNotBlank()) { "Вставь API-ключ Gemini." }
                            preferences.edit()
                                .putString("model_id", modelId.trim())
                                .putString("encrypted_token", GeminiSecretCipher.encrypt(apiKey.trim()))
                                .apply()
                            credentialsSaved = true
                            status = "Ключ Gemini сохранён в Android Keystore."
                        }.onFailure { status = it.message ?: "Не удалось сохранить ключ." }
                    },
                    enabled = apiKey.isNotBlank() && modelId.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить") }
                TextButton(onClick = {
                    preferences.edit().clear().apply()
                    apiKey = ""
                    credentialsSaved = false
                    viewModel.setGeminiPrimaryEnabled(false)
                    status = "Ключ Gemini удалён."
                }) { Text("Удалить") }
            }
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    busy = true
                    status = "Проверяю реальный запрос к Gemini…"
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                client().complete("Ответь одним словом: работает?", "Ты проверяешь подключение. Ответь по-русски.")
                            }
                            status = "Gemini отвечает: ${result.take(120)}"
                        } catch (error: Exception) {
                            status = error.message ?: "Не удалось проверить Gemini."
                        } finally { busy = false }
                    }
                },
                enabled = !busy && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Проверить подключение") }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Пробный запрос") },
                minLines = 2,
                maxLines = 4
            )
            Button(
                onClick = {
                    if (busy || prompt.isBlank()) return@Button
                    busy = true
                    answer = ""
                    status = "Отправляю тестовый запрос…"
                    scope.launch {
                        try {
                            answer = withContext(Dispatchers.IO) {
                                client().complete(prompt.trim(), "Ты — SheV, личный AI-помощник. Всегда отвечай по-русски.")
                            }
                            status = "Получен ответ от Gemini."
                        } catch (error: Exception) {
                            status = error.message ?: "Ошибка запроса Gemini."
                        } finally { busy = false }
                    }
                },
                enabled = !busy && apiKey.isNotBlank() && prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Отправить тестовый запрос") }
            if (busy) CircularProgressIndicator()
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (answer.isNotBlank()) {
                Text("Ответ Gemini", style = MaterialTheme.typography.titleMedium)
                Text(answer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Использовать Gemini для основного чата", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = agentState.useGemini,
                    onCheckedChange = viewModel::setGeminiPrimaryEnabled,
                    enabled = credentialsSaved && !busy
                )
            }
            Text(
                "При включении текущий запрос и ограниченная история диалога отправляются Google Gemini. Ответ удалённой модели не может самостоятельно выполнять действия на телефоне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
