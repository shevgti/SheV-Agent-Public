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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class VoiceEngineEntry(
    val name: String, val russian: String, val license: String, val runtime: String,
    val repositoryUrl: String, val modelUrl: String, val note: String
)

/** Catalogue only: these Python/PyTorch models are not Android-installable packages. */
@Composable
fun VoiceEnginesPanel() {
    val context = LocalContext.current
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
            Text("Каталог четырёх движков. Русский язык, лицензии и способ запуска проверяются отдельно для каждой модели.",
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
            Text("Важно: кнопки открывают официальные исходники и страницы моделей, а не скачивают Python-модели в телефон. Для выбора и загрузки прямо в SheV-Agent нужен отдельный inference backend либо портирование движков на Android. Старые встроенные нейросетевые движки удалены. Новые модели пока доступны как каталог; для скачивания и запуска внутри Android требуется отдельный совместимый inference backend или портирование.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
