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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Official Codex App Server embedded in the APK; ChatGPT login is managed by Codex. */
@Composable
fun CodexProviderPanel(viewModel: LocalModelViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Codex · умный AI-агент", style = MaterialTheme.typography.titleLarge)
            Text(
                "Официальный Codex работает прямо внутри SheV Agent. Вход через ChatGPT, без API-ключей, Gemini, Termux и сторонних шлюзов. Агент хранит отдельную историю и запрашивает подтверждение перед командами и изменениями файлов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { scope.launch { viewModel.connectCodex(context) } },
                enabled = !state.codexConnecting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.codexConnected) "Обновить подключение ChatGPT" else "Войти через ChatGPT")
            }
            if (state.codexConnected) {
                Text(
                    "Аккаунт: ${state.codexEmail.ifBlank { "ChatGPT" }} · план: ${state.codexPlanType.ifBlank { "определяется" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("Модель: ${state.codexModelId}", style = MaterialTheme.typography.bodySmall)
                state.codexRateUsedPercent?.let {
                    Text("Использование основного лимита: $it%", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Использовать Codex для основного чата",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = state.useCodex,
                        onCheckedChange = viewModel::setCodexPrimaryEnabled,
                        enabled = !state.codexConnecting
                    )
                }
                TextButton(
                    onClick = { scope.launch { viewModel.disconnectCodex() } },
                    enabled = !state.codexConnecting
                ) { Text("Отключить аккаунт") }
            }
            if (state.codexConnecting) CircularProgressIndicator()
            Text(
                state.codexStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Доступные модели и лимиты зависят от тарифа ChatGPT. На Free действует ограниченный доступ Codex. Команды выполняются только в изолированной папке приложения и требуют подтверждения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
