package com.shev.agent.model

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun LocalModelPanel(viewModel: LocalModelViewModel) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val chatListState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) chatListState.animateScrollToItem(state.messages.lastIndex)
    }

    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }
    DisposableEffect(recognizer) {
        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
        }
    }

    fun beginRussianSpeechInput() {
        val engine = recognizer
        if (engine == null) {
            voiceMessage = "На устройстве нет доступного распознавания речи."
            return
        }
        voiceMessage = "Слушаю. Говори по-русски."
        listening = true
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
            override fun onBeginningOfSpeech() { listening = true }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                voiceMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Не удалось разобрать речь. Попробуй ещё раз."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не обнаружена."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
                    else -> "Распознавание остановлено (код $error)."
                }
            }
            override fun onResults(results: android.os.Bundle?) {
                listening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    viewModel.setPrompt(spoken)
                    voiceMessage = "Текст распознан. Проверь его и нажми «Отправить»."
                } else {
                    voiceMessage = "Речь не распознана. Попробуй ещё раз."
                }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    viewModel.setPrompt(partial)
                    voiceMessage = "Распознаю речь…"
                }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говори по-русски")
        }
        runCatching { engine.startListening(intent) }.onFailure {
            listening = false
            voiceMessage = "Не удалось запустить микрофон: ${it.message ?: "ошибка"}"
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRussianSpeechInput()
        else voiceMessage = "Разрешение на микрофон не выдано."
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::importModel)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Диалог с SheV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Личный помощник на твоём устройстве",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = if (state.ready || state.useOmniRoute) Color(0xFFE0F5EA) else Color(0xFFEAF0FF)
                ) {
                    Text(
                        when { state.useOmniRoute -> "● OmniRoute"; state.ready -> "● Локально"; else -> "● Модель" },
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.ready || state.useOmniRoute) Color(0xFF176B45) else Color(0xFF3657A5),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!state.ready && !state.useOmniRoute) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFFF1F4FC)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            if (state.verified) "Модель установлена" else "Подключим локальный интеллект",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B2B4A)
                        )
                        Text(
                            "Gemma 4 E2B IT · 2,58 ГБ · работает на телефоне",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF53627D)
                        )
                        Text(
                            "После установки модель хранится в памяти приложения. Для обычного диалога внешний API и платный ключ не нужны.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF53627D)
                        )
                    }
                }

                when {
                    state.progress != null -> {
                        LinearProgressIndicator(
                            progress = { state.progress ?: 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Подготовка модели: ${((state.progress ?: 0f) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = viewModel::cancelDownload) { Text("Отменить") }
                    }
                    state.busy && !state.verified -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Проверяю модель и сохраняю её в защищённой области приложения…")
                    }
                    !state.verified -> {
                        Button(
                            onClick = viewModel::download,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("Скачать модель · 2,58 ГБ") }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("Выбрать уже скачанный файл") }
                    }
                    else -> {
                        Button(
                            onClick = viewModel::launchAgent,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(if (state.busy) "Запускаю модель…" else "Запустить SheV")
                        }
                    }
                }

                Text(
                    state.deviceSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (state.messages.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFF5F7FC)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Привет! Я SheV.", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Напиши сообщение или надиктуй его по-русски. История диалога сохраняется на этом устройстве.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        AssistChip(
                            onClick = { viewModel.setPrompt("Сохрани поручение: ") },
                            label = { Text("Создать поручение") }
                        )
                        AssistChip(
                            onClick = { viewModel.setPrompt("Помоги составить план на сегодня.") },
                            label = { Text("План на день") }
                        )
                    }
                } else {
                    LazyColumn(
                        state = chatListState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(state.messages, key = { index, _ -> index }) { _, line ->
                            val fromUser = line.startsWith("Вы:")
                            val message = line.substringAfter(":").trim()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    modifier = Modifier.widthIn(max = 320.dp),
                                    shape = RoundedCornerShape(
                                        topStart = 19.dp,
                                        topEnd = 19.dp,
                                        bottomStart = if (fromUser) 19.dp else 5.dp,
                                        bottomEnd = if (fromUser) 5.dp else 19.dp
                                    ),
                                    color = if (fromUser) Color(0xFF3158C8) else Color(0xFFF0F3F9),
                                    contentColor = if (fromUser) Color.White else MaterialTheme.colorScheme.onSurface
                                ) {
                                    Text(message, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
                                }
                            }
                        }
                    }
                }

                if (state.busy) {
                    Text(
                        "SheV формирует ответ…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = viewModel::setPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Напиши SheV…") },
                    minLines = 1,
                    maxLines = 4,
                    enabled = !state.busy,
                    shape = RoundedCornerShape(20.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            if (listening) {
                                recognizer?.stopListening()
                                listening = false
                                voiceMessage = "Остановил распознавание. Проверь текст."
                            } else if (
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                beginRussianSpeechInput()
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(if (listening) "■ Стоп" else "🎙 Голос")
                    }
                    Button(
                        onClick = viewModel::send,
                        enabled = state.prompt.isNotBlank() && !state.busy,
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(if (state.busy) "Отвечаю…" else "Отправить  ➤")
                    }
                }
                voiceMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            state.error?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
