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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Official Sign in with ChatGPT for the open-source SheV Android client. */
@Composable
fun OpenAIProviderPanel(viewModel: LocalModelViewModel) {
    val agentState by viewModel.state.collectAsState()
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("shev_openai", android.content.Context.MODE_PRIVATE)
    }
    var modelId by remember { mutableStateOf("gpt-6.1-sol") }
    var connected by remember { mutableStateOf(false) }
    var sessionPresent by remember { mutableStateOf(false) }
    var accountLabel by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("ChatGPT не подключён") }
    var needsReauth by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(preferences) {
        modelId = preferences.getString("model_id", "gpt-6.1-sol") ?: "gpt-6.1-sol"
        try {
            val session = ChatGptPlanOAuth(context, preferences).load()
            sessionPresent = session != null
            connected = false
            accountLabel = session?.email.orEmpty()
            status = if (sessionPresent) {
                "Сессия сохранена: ${accountLabel.ifBlank { "аккаунт ChatGPT" }}. Нажми «Проверить соединение», чтобы проверить реальный ответ модели."
            } else {
                "Аккаунт ChatGPT ещё не подключён. Нажми «Войти через ChatGPT»."
            }
        } catch (error: Exception) {
            connected = false
            sessionPresent = false
            accountLabel = ""
            status = "Не удалось прочитать сохранённую сессию ChatGPT: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("ChatGPT · OAuth по плану", style = MaterialTheme.typography.titleLarge)
            Text(
                "Альтернативный официальный OAuth-вход для обычного чата через план ChatGPT; Codex остаётся отдельным агентом. API-ключ не нужен. Подтверждение аккаунта и телефона, если его запросит OpenAI, выполняется в браузере.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    status = if (sessionPresent && !needsReauth) "Проверяю сохранённую сессию и соединение ChatGPT…" else "Открываю безопасный вход ChatGPT…"
                    scope.launch {
                        try {
                            val oauth = ChatGptPlanOAuth(context, preferences)
                            val existing = if (!needsReauth) oauth.load() else null
                            val session = existing ?: oauth.authorize()
                            val persisted = oauth.load()
                                ?: throw IllegalStateException("OpenAI подтвердил вход, но сохранённая сессия не найдена.")
                            check(persisted.subject == session.subject) {
                                "Сохранённая сессия относится к другому аккаунту. ChatGPT не включён."
                            }
                            status = "Проверяю доступ к моделям ChatGPT…"
                            val availableModels = oauth.availableModels()
                            if (availableModels.isEmpty()) {
                                throw IllegalStateException("OAuth-сессия сохранена, но ChatGPT не вернул ни одной доступной модели.")
                            }
                            val selectedModel = availableModels.firstOrNull { it.id == modelId } ?: availableModels.first()
                            modelId = selectedModel.id
                            preferences.edit().putString("model_id", selectedModel.id).apply()
                            status = "Проверяю реальный запрос к ChatGPT (${selectedModel.displayName})…"
                            val probeAnswer = viewModel.verifyOpenAIConnection(selectedModel.id)
                            require(probeAnswer.isNotBlank()) { "ChatGPT принял запрос, но вернул пустой ответ." }
                            connected = true
                            sessionPresent = true
                            needsReauth = false
                            accountLabel = persisted.email
                            viewModel.setOpenAIPrimaryEnabled(true)
                            status = "Соединение проверено: ${selectedModel.displayName}. ChatGPT ответил на тестовый запрос. Основной чат SheV включён."
                        } catch (error: Exception) {
                            connected = false
                            sessionPresent = runCatching { ChatGptPlanOAuth(context, preferences).load() != null }.getOrDefault(false)
                            needsReauth = error.message?.contains("HTTP 401") == true ||
                                error.message?.contains("Сначала подключи") == true ||
                                error.message?.contains("обновить ChatGPT") == true ||
                                error.message?.contains("Сохранённая авторизация ChatGPT недоступна") == true
                            viewModel.setOpenAIPrimaryEnabled(false)
                            status = error.message ?: "Не удалось проверить соединение ChatGPT."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (needsReauth) "Войти в ChatGPT заново" else if (sessionPresent) "Проверить соединение" else "Войти через ChatGPT")
            }
            if (connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Аккаунт: ${accountLabel.ifBlank { "ChatGPT" }}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            scope.launch {
                                try {
                                    val revoked = ChatGptPlanOAuth(context, preferences).disconnect()
                                    connected = false
                                    sessionPresent = false
                                    needsReauth = false
                                    accountLabel = ""
                                    viewModel.setOpenAIPrimaryEnabled(false)
                                    status = if (revoked) {
                                        "Аккаунт ChatGPT отключён, refresh-сессия отозвана."
                                    } else {
                                        "Локальные токены удалены. Удалённый отзыв OpenAI не удалось подтвердить."
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy
                    ) { Text("Отключить") }
                }
            }
            OutlinedTextField(
                value = modelId,
                onValueChange = {
                    modelId = it
                    connected = false
                    preferences.edit().putString("model_id", it.trim()).apply()
                    if (agentState.useOpenAI) viewModel.setOpenAIPrimaryEnabled(false)
                    status = "Модель изменена. Проверь соединение, прежде чем снова включать ChatGPT."
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Модель ChatGPT") },
                supportingText = { Text("По умолчанию gpt-6.1-sol. Доступность модели проверяется самим ChatGPT-аккаунтом.") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Использовать ChatGPT для основного чата SheV",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = agentState.useOpenAI,
                    onCheckedChange = viewModel::setOpenAIPrimaryEnabled,
                    enabled = connected && !busy
                )
            }
            if (busy) CircularProgressIndicator()
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Разрешение ChatGPT plan usage и лимиты определяются твоим аккаунтом и настройками ChatGPT. SheV не получает доступ к перепискам ChatGPT — только разрешение на запросы к моделям. История SheV отправляется в OpenAI, когда выбран этот провайдер.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
