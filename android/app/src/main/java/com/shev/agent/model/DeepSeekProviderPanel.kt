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

/** Direct DeepSeek API configuration; the key is encrypted with a dedicated Android Keystore key. */
@Composable
fun DeepSeekProviderPanel(viewModel: LocalModelViewModel) {
    val agentState by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("shev_deepseek", android.content.Context.MODE_PRIVATE)
    }
    var modelId by remember { mutableStateOf("deepseek-flash") }
    var apiKey by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("Привет! Представься по-русски и кратко расскажи, чем можешь помочь в SheV Agent.") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("DeepSeek не подключён") }
    var busy by remember { mutableStateOf(false) }
    var credentialsSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(preferences) {
        modelId = preferences.getString("model_id", "deepseek-flash") ?: "deepseek-flash"
        apiKey = preferences.getString("encrypted_token", null)?.let { encrypted ->
            runCatching { DeepSeekSecretCipher.decrypt(encrypted) }.getOrDefault("")
        } ?: ""
        credentialsSaved = apiKey.isNotBlank()
        if (preferences.contains("encrypted_token") && !credentialsSaved) {
            status = "Сохранённый ключ недоступен. Введи его заново."
        } else if (credentialsSaved) {
            status = "Ключ DeepSeek сохранён на этом телефоне."
        }
    }

    fun markDirty() {
        credentialsSaved = false
        if (agentState.useDeepSeek) viewModel.setDeepSeekPrimaryEnabled(false)
    }

    fun client(): OpenAiCompatibleClient = OpenAiCompatibleClient(
        OpenAiCompatibleConfig(
            baseUrl = "https://api.deepseek.com",
            modelId = modelId.trim(),
            apiToken = apiKey.trim(),
            providerName = "DeepSeek",
            appendV1IfMissing = false
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
            Text("DeepSeek · API", style = MaterialTheme.typography.titleLarge)
            Text(
                "Подключение по твоему API-ключу. Ключ хранится зашифрованным в Android Keystore и не отправляется в историю чата. Использование API может расходовать баланс DeepSeek.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://platform.deepseek.com/api_keys")
                            )
                        )
                        status = "Открой страницу ключей DeepSeek, скопируй ключ и вернись сюда."
                    }.onFailure { status = "Не удалось открыть страницу ключей DeepSeek." }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Открыть страницу API-ключей DeepSeek") }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    val copied = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
                    if (copied.length > 20) {
                        apiKey = copied
                        markDirty()
                        status = "Ключ вставлен. Нажми «Сохранить ключ»."
                    } else {
                        status = "В буфере не найден API-ключ. Скопируй его на странице DeepSeek."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Вставить скопированный ключ") }
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it; markDirty() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Модель DeepSeek") },
                supportingText = { Text("Актуальные варианты: deepseek-flash и deepseek-v4-pro.") }
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; markDirty() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("API-ключ DeepSeek") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            client()
                            preferences.edit()
                                .putString("model_id", modelId.trim())
                                .putString("encrypted_token", DeepSeekSecretCipher.encrypt(apiKey.trim()))
                                .apply()
                            credentialsSaved = true
                            status = "Ключ сохранён в Android Keystore."
                        }.onFailure { status = it.message ?: "Не удалось сохранить настройки DeepSeek." }
                    },
                    enabled = apiKey.isNotBlank() && modelId.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить ключ") }
                TextButton(onClick = {
                    preferences.edit().clear().apply()
                    apiKey = ""
                    credentialsSaved = false
                    viewModel.setDeepSeekPrimaryEnabled(false)
                    status = "Ключ DeepSeek удалён."
                }) { Text("Удалить") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Использовать DeepSeek для основного чата", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = agentState.useDeepSeek,
                    onCheckedChange = viewModel::setDeepSeekPrimaryEnabled,
                    enabled = credentialsSaved && !busy
                )
            }
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    busy = true
                    status = "Проверяю API-ключ и модель DeepSeek…"
                    scope.launch {
                        try {
                            val testAnswer = withContext(Dispatchers.IO) {
                                client().complete(
                                    "Ответь одним словом: подключение работает?",
                                    "Ты проверяешь соединение. Ответь кратко по-русски."
                                )
                            }
                            status = "Подключение DeepSeek работает. Модель $modelId ответила: ${testAnswer.take(120)}"
                        } catch (error: Exception) {
                            status = error.message ?: "Не удалось проверить DeepSeek API."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && apiKey.isNotBlank() && modelId.isNotBlank(),
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
                            status = "Ответ получен от DeepSeek."
                        } catch (error: Exception) {
                            status = error.message ?: "Ошибка запроса к DeepSeek."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && apiKey.isNotBlank() && prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Отправить тестовый запрос") }
            if (busy) CircularProgressIndicator()
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (answer.isNotBlank()) {
                Text("Ответ DeepSeek", style = MaterialTheme.typography.titleMedium)
                Text(answer)
            }
            Text(
                "Сначала сохрани ключ и проверь соединение. После этого включи DeepSeek для основного чата. Не отправляй API-ключ в сообщения и не добавляй его в GitHub.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
