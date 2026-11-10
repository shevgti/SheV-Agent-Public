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
    var accountLabel by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("ChatGPT не подключён") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(preferences) {
        modelId = preferences.getString("model_id", "gpt-6.1-sol") ?: "gpt-6.1-sol"
        val session = runCatching { ChatGptPlanOAuth(context, preferences).load() }.getOrNull()
        connected = session != null
        accountLabel = session?.email.orEmpty()
        status = if (connected) {
            "ChatGPT подключён: ${accountLabel.ifBlank { "аккаунт подтверждён" }}."
        } else {
            "Нажми «Войти через ChatGPT». API-ключ создавать не нужно."
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
            Text("ChatGPT · официальный вход", style = MaterialTheme.typography.titleLarge)
            Text(
                "SheV подключается напрямую к ChatGPT через официальный OAuth для открытых приложений. Никаких API-ключей, Gemini, сторонних шлюзов или Termux. Ты подтверждаешь доступ в браузере, а токены хранятся зашифрованными на телефоне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    status = "Открываю безопасный вход ChatGPT…"
                    scope.launch {
                        try {
                            val session = ChatGptPlanOAuth(context, preferences).authorize()
                            connected = true
                            accountLabel = session.email
                            viewModel.setOpenAIPrimaryEnabled(true)
                            status = "ChatGPT подключён. SheV использует доступ твоего аккаунта без API-ключа."
                        } catch (error: Exception) {
                            status = error.message ?: "Не удалось подключить ChatGPT."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (connected) "Переподключить ChatGPT" else "Войти через ChatGPT")
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
                    preferences.edit().putString("model_id", it.trim()).apply()
                    if (agentState.useOpenAI) viewModel.setOpenAIPrimaryEnabled(false)
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
