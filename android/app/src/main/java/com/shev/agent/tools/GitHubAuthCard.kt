package com.shev.agent.tools

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shev.agent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GitHubAuthCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { GitHubTokenStore(context.applicationContext) }
    val client = remember { GitHubDeviceFlowClient() }
    var connected by remember { mutableStateOf(tokenStore.read() != null) }
    var deviceCode by remember { mutableStateOf<GitHubDeviceCode?>(null) }
    var status by remember { mutableStateOf("Подключение GitHub ещё не настроено.") }
    var busy by remember { mutableStateOf(false) }
    val clientId = BuildConfig.GITHUB_CLIENT_ID

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("GitHub", style = MaterialTheme.typography.titleLarge)
            if (connected) {
                Text("Аккаунт подключён. Токен хранится в Android Keystore.")
                OutlinedButton(
                    enabled = !busy && clientId.isNotBlank() && tokenStore.readRefreshToken() != null,
                    onClick = {
                        busy = true
                        status = "Обновляю GitHub-токен…"
                        scope.launch {
                            val refresh = withContext(Dispatchers.IO) { tokenStore.readRefreshToken() }
                            val result = if (refresh == null) {
                                GitHubTokenRefreshResult.Failed("Refresh token отсутствует. Подключите GitHub заново.")
                            } else withContext(Dispatchers.IO) {
                                client.refreshAccessToken(clientId, refresh)
                            }
                            when (result) {
                                is GitHubTokenRefreshResult.Refreshed -> {
                                    withContext(Dispatchers.IO) {
                                        tokenStore.save(result.tokens.accessToken, result.tokens.refreshToken)
                                    }
                                    status = "Токены обновлены; новая пара сохранена защищённо."
                                }
                                is GitHubTokenRefreshResult.Failed -> status = result.reason
                            }
                            busy = false
                        }
                    }
                ) { Text("Обновить токен") }
                OutlinedButton(onClick = {
                    tokenStore.clear()
                    connected = false
                    status = "GitHub отключён. Сохранённый токен удалён."
                }) { Text("Отключить GitHub") }
            } else if (clientId.isBlank()) {
                Text("Для входа нужен Client ID GitHub App. Он задаётся сборочной настройкой GITHUB_CLIENT_ID; секреты в приложение не вшиваются.")
            } else {
                Text("Вход через одноразовый код GitHub. Пароль приложения не запрашивается.")
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        status = "Запрашиваю код у GitHub…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                client.requestDeviceCode(clientId)
                            }
                            result.onSuccess { code ->
                                deviceCode = code
                                status = "Введите код ниже на официальной странице GitHub."
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri))
                                context.startActivity(intent)
                                launch {
                                    var interval = code.intervalSeconds
                                    val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000
                                    var finished = false
                                    while (!finished && System.currentTimeMillis() < deadline) {
                                        delay(interval * 1000)
                                        when (val poll = withContext(Dispatchers.IO) {
                                            client.pollForAccessToken(clientId, code.deviceCode)
                                        }) {
                                            is GitHubDevicePollResult.Authorized -> {
                                                withContext(Dispatchers.IO) { tokenStore.save(poll.accessToken, poll.refreshToken) }
                                                connected = true
                                                deviceCode = null
                                                status = "GitHub успешно подключён."
                                                finished = true
                                            }
                                            GitHubDevicePollResult.Pending -> status = "Ожидаю подтверждения на GitHub…"
                                            is GitHubDevicePollResult.SlowDown -> {
                                                interval = maxOf(interval + 5, poll.recommendedIntervalSeconds)
                                                status = "GitHub попросил увеличить интервал проверки."
                                            }
                                            GitHubDevicePollResult.Expired -> {
                                                status = "Код истёк. Запросите новый."
                                                finished = true
                                            }
                                            GitHubDevicePollResult.Denied -> {
                                                status = "Вход отменён на странице GitHub."
                                                finished = true
                                            }
                                            is GitHubDevicePollResult.Failed -> {
                                                status = poll.reason
                                                finished = true
                                            }
                                        }
                                    }
                                    if (!finished && !connected) status = "Время действия кода истекло. Запросите новый."
                                    deviceCode = null
                                    busy = false
                                }
                            }.onFailure {
                                status = it.message ?: "Не удалось начать вход GitHub."
                                busy = false
                            }
                        }
                    }
                ) { Text("Подключить GitHub") }
                deviceCode?.let { code ->
                    Text("Код подтверждения", style = MaterialTheme.typography.labelLarge)
                    Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
                    Text("Если страница не открылась, перейдите на github.com/login/device и введите этот код.")
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri)))
                    }) { Text("Открыть GitHub для подтверждения") }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
