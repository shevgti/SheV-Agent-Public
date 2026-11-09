package com.shev.agent.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import com.shev.agent.BuildConfig
import kotlinx.coroutines.launch

@Composable
fun ReleaseUpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var message by remember { mutableStateOf("Проверяйте новые версии из публичного GitHub Releases.") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Обновления SheV Agent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Установлена версия ${BuildConfig.VERSION_NAME}")
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (checking || downloading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Button(
                onClick = {
                    scope.launch {
                        checking = true
                        release = null
                        message = "Проверяю публичный канал обновлений…"
                        try {
                            release = ReleaseUpdater.check(BuildConfig.VERSION_NAME)
                            message = if (release == null) {
                                "Новых версий нет. Если канал ещё не создан, проверка пока недоступна."
                            } else {
                                "Доступна версия ${release!!.version}. APK будет проверен по SHA-256 перед установкой."
                            }
                        } catch (error: Exception) {
                            message = "Не удалось проверить обновления: ${error.message ?: "ошибка сети"}"
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = !checking && !downloading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Проверить обновления") }
            release?.let { available ->
                Button(
                    onClick = {
                        scope.launch {
                            downloading = true
                            message = "Скачиваю APK и проверяю контрольную сумму…"
                            try {
                                val installerOpened = ReleaseUpdater.downloadAndPromptInstall(context, available)
                                message = if (installerOpened) "Проверка пройдена. Подтверди установку в системном окне Android." else "Разреши SheV Agent устанавливать обновления в настройках Android, вернись сюда и нажми кнопку установки ещё раз."
                            } catch (error: Exception) {
                                message = "Установка не подготовлена: ${error.message ?: "ошибка"}"
                            } finally {
                                downloading = false
                            }
                        }
                    },
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Скачать и установить ${available.version}") }
            }
        }
    }
}
