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

/** Direct OpenAI API configuration. API keys are encrypted with a dedicated Android Keystore key. */
@Composable
fun OpenAIProviderPanel(viewModel: LocalModelViewModel) {
    val agentState by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("shev_openai", android.content.Context.MODE_PRIVATE)
    }
    var modelId by remember { mutableStateOf("gpt-4.1-mini") }
    var apiKey by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("Привет! Представься по-русски и кратко расскажи, чем можешь помочь в SheV Agent.") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Не подключено") }
    var busy by remember { mutableStateOf(false) }
    var credentialsSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(preferences) {
        modelId = preferences.getString("model_id", modelId) ?: modelId
        apiKey = preferences.getString("encrypted_token", null)?.let { encrypted ->
            runCatching { OpenAISecretCipher.decrypt(encrypted) }.getOrDefault("")
        } ?: ""
        credentialsSaved = apiKey.isNotBlank()
        if (preferences.contains("encrypted_token") && !credentialsSaved) {
            status = "Сохранённый ключ недоступен. Введи его заново."
        }
    }

    fun markDirty() {
        credentialsSaved = false
        if (agentState.useOpenAI) viewModel.setOpenAIPrimaryEnabled(false)
    }

    fun client(): OpenAiCompatibleClient = OpenAiCompatibleClient(
        OpenAiCompatibleConfig("https://api.openai.com/v1", modelId.trim(), apiKey.trim())
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
            Text("OpenAI · ChatGPT", style = MaterialTheme.typography.titleLarge)
            Text(
                "Прямое подключение к OpenAI API. Нужен отдельный API-ключ платформы OpenAI; подписка ChatGPT Plus/Free не включает API-баланс.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://platform.openai.com/api-keys"))
                    context.startActivity(intent)
                }.onFailure { status = "Не удалось открыть страницу ключей." }
            }) { Text("Открыть страницу API-ключей OpenAI") }
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it; markDirty() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Модель OpenAI") },
                supportingText = { Text("По умолчанию gpt-4.1-mini. Можно указать другую доступную API-модель.") }
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; markDirty() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Секретный API-ключ") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            OpenAiCompatibleConfig("https://api.openai.com/v1", modelId.trim(), apiKey.trim()).validatedBaseUrl()
                            preferences.edit()
                                .putString("model_id", modelId.trim())
                                .putString("encrypted_token", OpenAISecretCipher.encrypt(apiKey.trim()))
                                .apply()
                            credentialsSaved = true
                            status = "Ключ сохранён в Android Keystore."
                        }.onFailure { status = it.message ?: "Не удалось сохранить настройки OpenAI." }
                    },
                    enabled = apiKey.isNotBlank() && modelId.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить ключ") }
                TextButton(onClick = {
                    preferences.edit().clear().apply()
                    apiKey = ""
                    credentialsSaved = false
                    viewModel.setOpenAIPrimaryEnabled(false)
                    status = "Ключ OpenAI удалён."
                }) { Text("Удалить") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Использовать OpenAI для основного чата", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = agentState.useOpenAI,
                    onCheckedChange = viewModel::setOpenAIPrimaryEnabled,
                    enabled = credentialsSaved && !busy
                )
            }
            Text(
                "ChatGPT может пользоваться только тремя безопасными инструментами SheV: узнать местное время, посмотреть доступные инструменты и сохранить черновик поручения. Черновик ждёт подтверждения во вкладке «Память». Изменение настроек, отправка сообщений, удаление и другие рискованные действия пока недоступны.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    busy = true
                    status = "Проверяю API-ключ и модель…"
                    scope.launch {
                        try {
                            val models = withContext(Dispatchers.IO) { client().listModels().map { it.id } }
                            status = if (modelId in models) "OpenAI подключён. Модель $modelId доступна." else
                                "Ключ принят. Модель $modelId не найдена в списке доступных; проверь её ID и доступ."
                        } catch (error: Exception) {
                            status = error.message ?: "Не удалось проверить OpenAI API."
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
                    status = "Отправляю пробный запрос…"
                    scope.launch {
                        try {
                            answer = withContext(Dispatchers.IO) {
                                client().complete(prompt.trim(), "Ты — SheV, личный AI-помощник. Отвечай по-русски.")
                            }
                            status = "Ответ получен от OpenAI."
                        } catch (error: Exception) {
                            status = error.message ?: "Ошибка запроса к OpenAI."
                        } finally { busy = false }
                    }
                },
                enabled = !busy && apiKey.isNotBlank() && prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Отправить тестовый запрос") }
            if (busy) CircularProgressIndicator()
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (answer.isNotBlank()) {
                Text("Ответ ChatGPT", style = MaterialTheme.typography.titleMedium)
                Text(answer)
            }
            Text(
                "Ключ хранится отдельно от OmniRoute и шифруется AES-GCM ключом Android Keystore. История чата отправляется в OpenAI только при выборе этого провайдера. Не вставляй ключ в сообщения или GitHub.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
