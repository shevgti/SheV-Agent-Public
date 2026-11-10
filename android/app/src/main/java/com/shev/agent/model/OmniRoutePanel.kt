package com.shev.agent.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optional OmniRoute/OpenAI-compatible gateway panel.
 * Credentials are encrypted with a dedicated Android Keystore key before persistence and are never logged.
 */
@Composable
fun OmniRoutePanel(viewModel: LocalModelViewModel) {
    val agentState by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = LocalUriHandler.current
    val preferences = remember(context) {
        context.getSharedPreferences("shev_omniroute", android.content.Context.MODE_PRIVATE)
    }
    var baseUrl by remember { mutableStateOf("https://your-omniroute-host/v1") }
    var modelId by remember { mutableStateOf("auto") }
    var token by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Не подключено") }
    var busy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var credentialsSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(preferences) {
        credentialsSaved = preferences.contains("encrypted_token")
        baseUrl = preferences.getString("base_url", baseUrl) ?: baseUrl
        modelId = preferences.getString("model_id", modelId) ?: modelId
        token = preferences.getString("encrypted_token", null)?.let { encrypted ->
            runCatching { OmniRouteSecretCipher.decrypt(encrypted) }.getOrDefault("")
        } ?: ""
        credentialsSaved = credentialsSaved && token.isNotBlank()
        if (preferences.contains("encrypted_token") && token.isBlank()) {
            status = "Сохранённый ключ недоступен. Введи его заново."
        }
    }

    fun markSettingsDirty() {
        credentialsSaved = false
        if (agentState.useOmniRoute) viewModel.setOmniRoutePrimaryEnabled(false)
    }

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
                "Это отдельный шлюз моделей, не прямая связь с текущим чатом ChatGPT. Он должен быть запущен на компьютере или сервере.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = { uriHandler.openUri("https://github.com/shevgti/SheV-Agent-Public/blob/main/docs/OMNIROUTE_TAILSCALE_SETUP.md") }
            ) { Text("Инструкция по бесплатной настройке") }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; markSettingsDirty() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Адрес OmniRoute") },
                supportingText = { Text("Укажи HTTPS-адрес компьютера или сервера с OmniRoute.") }
            )
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it; markSettingsDirty() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Модели по приоритету") },
                supportingText = { Text("Можно указать несколько ID через запятую: сначала основная, затем резервные.") }
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; markSettingsDirty() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("API-ключ OmniRoute") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val normalizedUrl = OpenAiCompatibleConfig(
                                baseUrl.trim(), modelId.trim(), token.trim()
                            ).validatedBaseUrl()
                            preferences.edit()
                                .putString("base_url", normalizedUrl)
                                .putString("model_id", modelId.trim())
                                .putString("encrypted_token", OmniRouteSecretCipher.encrypt(token.trim()))
                                .apply()
                            baseUrl = normalizedUrl
                            credentialsSaved = true
                            status = "Настройки сохранены в зашифрованном виде."
                        }.onFailure { status = it.message ?: "Не удалось сохранить настройки OmniRoute." }
                    },
                    enabled = token.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить") }
                TextButton(onClick = {
                    preferences.edit().clear().apply()
                    token = ""
                    credentialsSaved = false
                    viewModel.setOmniRoutePrimaryEnabled(false)
                    status = "Сохранённый ключ удалён."
                }) { Text("Забыть ключ") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Использовать OmniRoute для основного чата", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = agentState.useOmniRoute,
                    onCheckedChange = viewModel::setOmniRoutePrimaryEnabled,
                    enabled = credentialsSaved && !busy
                )
            }
            Text(
                "При включении последние сообщения диалога и краткая память будут отправляться на выбранный удалённый AI-сервер. Ответ удалённой модели не получает прямой доступ к действиям телефона.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var agentToolsEnabled by remember { mutableStateOf(preferences.getBoolean("enable_agent_tools", false)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Разрешить инструменты SheV", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Модель сможет запросить локальные инструменты: время, список возможностей и черновик поручения. Если шлюз не поддерживает Responses API, чат автоматически продолжит работу без инструментов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = agentToolsEnabled,
                    onCheckedChange = { enabled ->
                        agentToolsEnabled = enabled
                        preferences.edit().putBoolean("enable_agent_tools", enabled).apply()
                    },
                    enabled = credentialsSaved && !busy
                )
            }
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
                                val details = error.message ?: "Не удалось подключиться."
                                status = if (details.contains("401") || details.contains("403")) {
                                    "Каталог моделей закрыт настройками входа. Введи ID модели вручную и проверь соединение пробным запросом."
                                } else details
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
                Text("Доступные модели · нажми, чтобы выбрать:", style = MaterialTheme.typography.bodySmall)
                models.take(8).forEach { id ->
                    AssistChip(
                        onClick = { modelId = id; markSettingsDirty() },
                        label = { Text(id, maxLines = 1) }
                    )
                }
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
                "Ключ хранится в Android Keystore в зашифрованном виде после нажатия «Сохранить». Не используй ключ с правами admin или MCP-управления для обычного чата.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
