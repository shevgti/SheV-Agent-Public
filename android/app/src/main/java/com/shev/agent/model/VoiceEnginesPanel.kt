package com.shev.agent.model

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.launch

private data class VoiceEngineEntry(
    val name: String, val russian: String, val license: String, val runtime: String,
    val repositoryUrl: String, val modelUrl: String, val note: String
)

/** Catalogue only: these Python/PyTorch models are not Android-installable packages. */
@Composable
fun VoiceEnginesPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceStore = remember(context) { RussianPiperVoiceStore(context) }
    val installed = remember { mutableStateMapOf<String, Boolean>() }
    val progress = remember { mutableStateMapOf<String, Float>() }
    val status = remember { mutableStateMapOf<String, String>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) {
        RussianPiperVoice.entries.forEach { voice ->
            installed[voice.name] = voiceStore.isInstalled(voice)
        }
    }
    val engines = listOf(
        VoiceEngineEntry("Coqui XTTS-v2", "Русский подтверждён · 16 языков",
            "Coqui Public Model License (CPML)", "Python / PyTorch · сервер или ПК с GPU",
            "https://github.com/coqui-ai/TTS", "https://huggingface.co/coqui/XTTS-v2",
            "Клонирование голоса и многоязычная речь. Условия CPML нужно отдельно проверить перед распространением."),
        VoiceEngineEntry("F5-TTS Russian", "Русский есть в сторонней fine-tune модели",
            "Веса русской модели: CC-BY-NC-4.0", "Python / PyTorch · сервер или ПК с GPU",
            "https://github.com/SWivid/F5-TTS", "https://huggingface.co/hotstone228/F5-TTS-Russian",
            "Это community-модель, не официальный базовый checkpoint. Некоммерческое ограничение не подходит для коммерческого распространения без разрешения."),
        VoiceEngineEntry("Chatterbox Multilingual V3", "Русский подтверждён · 23+ языка",
            "Код MIT · условия весов проверять отдельно", "Python / PyTorch · сервер или ПК с GPU",
            "https://github.com/resemble-ai/chatterbox", "https://huggingface.co/ResembleAI/Chatterbox",
            "Русский есть в официальном списке языков. Репозиторий опубликован как архив без поддержки и обновлений."),
        VoiceEngineEntry("Fish Speech", "Мультиязычная модель · русский checkpoint требует отдельного теста",
            "Fish Audio Community License; отдельные веса могут иметь другие условия",
            "Python / PyTorch · сервер или ПК с GPU",
            "https://github.com/fishaudio/fish-speech", "https://huggingface.co/fishaudio",
            "Лицензия ограничивает hosted-service и некоторые коммерческие сценарии. Не отмечаем как готовую русскую модель без проверки конкретных весов.")
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Голосовые движки и языки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Четыре перспективных нейросетевых движка. Эти модели запускаются через Python/PyTorch на отдельном компьютере или сервере; они не устанавливаются напрямую как Android-пакеты.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            engines.forEach { engine ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(engine.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(engine.russian, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        Text("Лицензия: ${engine.license}", style = MaterialTheme.typography.bodySmall)
                        Text("Запуск: ${engine.runtime}", style = MaterialTheme.typography.bodySmall)
                        Text(engine.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(engine.repositoryUrl))) }) { Text("Код") }
                            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(engine.modelUrl))) }) { Text("Модель") }
                        }
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider()
            Text("Русские офлайн-голоса", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Загружаются на телефон один раз. После установки озвучивание работает без интернета и платного API. Размер каждого архива около 67 МБ.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RussianPiperVoice.entries.forEach { voice ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(voice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Piper ONNX · русский · офлайн", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (busy[voice.name] == true) {
                            LinearProgressIndicator(progress = { progress[voice.name] ?: 0f }, modifier = Modifier.fillMaxWidth())
                            Text(status[voice.name] ?: "Подготовка модели…", style = MaterialTheme.typography.bodySmall)
                        } else if (installed[voice.name] == true) {
                            Text("Модель установлена", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("Не загружена", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = busy[voice.name] != true,
                                onClick = {
                                    scope.launch {
                                        busy[voice.name] = true
                                        status[voice.name] = "Загружаю и проверяю архив…"
                                        try {
                                            voiceStore.install(voice) { downloaded, total ->
                                                progress[voice.name] = (downloaded.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                            }
                                            installed[voice.name] = true
                                            status[voice.name] = "Голос установлен"
                                        } catch (error: Exception) {
                                            status[voice.name] = "Ошибка: ${error.message ?: "не удалось установить"}"
                                        } finally {
                                            busy[voice.name] = false
                                        }
                                    }
                                }
                            ) { Text(if (installed[voice.name] == true) "Переустановить" else "Скачать") }
                            OutlinedButton(
                                enabled = installed[voice.name] == true && busy[voice.name] != true,
                                onClick = {
                                    scope.launch {
                                        busy[voice.name] = true
                                        status[voice.name] = "Готовлю пример речи…"
                                        try {
                                            voiceStore.speakSample(context, voice, "Привет! Я голосовой помощник SheV. Рад тебя слышать.")
                                            status[voice.name] = "Пример воспроизведён"
                                        } catch (error: Exception) {
                                            status[voice.name] = "Ошибка воспроизведения: ${error.message ?: "неизвестная ошибка"}"
                                        } finally {
                                            busy[voice.name] = false
                                        }
                                    }
                                }
                            ) { Text("Прослушать") }
                        }
                        status[voice.name]?.takeIf { busy[voice.name] != true }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text("Важно: кнопки открывают официальные исходники и страницы моделей, а не скачивают Python-модели в телефон. Для выбора и загрузки прямо в SheV-Agent нужен отдельный inference backend либо портирование движков на Android. Старые встроенные нейросетевые движки удалены. Новые модели пока доступны как каталог; для скачивания и запуска внутри Android требуется отдельный совместимый inference backend или портирование.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
