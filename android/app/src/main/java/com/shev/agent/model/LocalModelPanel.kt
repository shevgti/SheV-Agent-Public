package com.shev.agent.model

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Build
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.shev.agent.BuildConfig
import com.shev.agent.update.AppUpdate
import com.shev.agent.update.AppUpdater
import com.shev.agent.update.UpdateCheckWorker
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun LocalModelPanel(viewModel: LocalModelViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var voiceConversation by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("Нажми на микрофон, чтобы начать голосовой диалог") }
    var availableVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var selectedVoiceName by remember { mutableStateOf(context.getSharedPreferences("shev_voice", android.content.Context.MODE_PRIVATE).getString("tts_voice", null)) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showGatewaySettings by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdate?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloaded by remember { mutableStateOf(0L) }
    var updateTotal by remember { mutableStateOf(0L) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentVoiceMode by rememberUpdatedState(voiceConversation)
    val chatListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        UpdateCheckWorker.schedule(context)
        checkingUpdate = true
        try {
            updateInfo = AppUpdater.check(BuildConfig.VERSION_CODE)
            if (updateInfo != null) showUpdateDialog = true
            updateMessage = if (updateInfo == null) "Установлена последняя версия" else null
        } catch (error: Exception) {
            updateMessage = "Не удалось проверить обновления: ${error.message ?: "ошибка сети"}"
        } finally {
            checkingUpdate = false
        }
    }

    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    val speech = remember(context) {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { status ->
            Handler(Looper.getMainLooper()).post {
                if (status == TextToSpeech.SUCCESS) {
                    engine.language = Locale("ru", "RU")
                    val russianVoices = engine.voices.orEmpty()
                        .filter { it.locale.language.equals("ru", ignoreCase = true) }
                        .sortedBy { it.name.lowercase(Locale.ROOT) }
                    availableVoices = russianVoices
                    val savedVoice = selectedVoiceName?.let { name ->
                        russianVoices.firstOrNull { it.name == name }
                    }
                    if (savedVoice != null) {
                        engine.voice = savedVoice
                    } else if (russianVoices.isNotEmpty()) {
                        engine.voice = russianVoices.first()
                        selectedVoiceName = russianVoices.first().name
                    }
                    if (russianVoices.isEmpty()) {
                        voiceStatus = "Русские голоса не найдены. Проверь службу синтеза речи Android."
                    }
                } else {
                    availableVoices = emptyList()
                    voiceStatus = "Не удалось запустить синтез речи Android (код $status)."
                }
            }
        }
        engine
    }

    fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Говори с SheV")
    }

    fun beginListening() {
        val engine = recognizer
        if (engine == null) {
            voiceStatus = "На устройстве нет службы распознавания речи."
            voiceConversation = false
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceStatus = "Нужно разрешение на микрофон."
            return
        }
        voiceStatus = "Слушаю тебя…"
        listening = true
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
            override fun onBeginningOfSpeech() { listening = true; voiceStatus = "Слушаю тебя…" }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false; voiceStatus = "Распознаю…" }
            override fun onError(error: Int) {
                listening = false
                voiceStatus = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышал. Нажми микрофон и повтори."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не услышал речь. Попробуй ещё раз."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
                    else -> "Голосовой ввод остановлен (код $error)."
                }
                if (currentVoiceMode && error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                    Handler(Looper.getMainLooper()).postDelayed({ if (currentVoiceMode) beginListening() }, 900L)
                }
            }
            override fun onResults(results: android.os.Bundle?) {
                listening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (spoken.isNullOrBlank()) {
                    voiceStatus = "Не удалось распознать речь."
                } else {
                    viewModel.setPrompt(spoken)
                    if (currentVoiceMode) {
                        voiceStatus = "Отправляю сообщение…"
                        viewModel.send()
                    } else {
                        voiceStatus = "Текст распознан. Нажми «Отправить»."
                    }
                }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })
        runCatching { engine.startListening(speechIntent()) }.onFailure {
            listening = false
            voiceStatus = "Не удалось включить микрофон: ${it.message ?: "ошибка"}"
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginListening() else {
            voiceConversation = false
            voiceStatus = "Разрешение на микрофон не выдано."
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateMessage = if (granted) "Уведомления об обновлениях включены" else "Разрешение на уведомления не выдано"
    }

    DisposableEffect(recognizer, speech) {
        speech.setSpeechRate(1.0f)
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                Handler(Looper.getMainLooper()).post { voiceStatus = "Не удалось воспроизвести голосовой ответ." }
            }
            override fun onDone(utteranceId: String?) {
                Handler(Looper.getMainLooper()).post {
                    if (currentVoiceMode) beginListening()
                }
            }
        })
        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
            speech.stop()
            speech.shutdown()
        }
    }

    LaunchedEffect(state.messages.lastOrNull(), state.busy) {
        val last = state.messages.lastOrNull()
        if (voiceConversation && last?.startsWith("SheV:") == true && !state.busy) {
            val answer = last.substringAfter(":").trim()
            if (answer.isNotBlank()) {
                voiceStatus = "SheV отвечает…"
                speech.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "shev-answer-${state.messages.size}")
            }
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) chatListState.animateScrollToItem(state.messages.lastIndex)
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Настройки подключения") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Приложение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Установленная версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
                            if (updateInfo != null) {
                                Text("Доступна версия: ${updateInfo!!.versionName}", color = Color(0xFF16834A), fontWeight = FontWeight.SemiBold)
                                Button(onClick = { showUpdateDialog = true }, enabled = !downloadingUpdate, modifier = Modifier.fillMaxWidth()) { Text("Скачать и установить") }
                            } else {
                                Text("Обновление: ${if (checkingUpdate) "проверяю…" else "последняя версия или нет опубликованного релиза"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                enabled = !checkingUpdate && !downloadingUpdate,
                                onClick = {
                                    scope.launch {
                                        checkingUpdate = true
                                        updateMessage = null
                                        try {
                                            updateInfo = AppUpdater.check(BuildConfig.VERSION_CODE)
                                            if (updateInfo != null) showUpdateDialog = true
                                            updateMessage = if (updateInfo == null) "Новых обновлений нет" else "Доступна версия ${updateInfo!!.versionName}"
                                        } catch (error: Exception) {
                                            updateMessage = "Не удалось проверить: ${error.message ?: "ошибка сети"}"
                                        } finally { checkingUpdate = false }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (checkingUpdate) "Проверяю обновления…" else "Проверить обновления") }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Голос", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { showVoicePicker = true }) { Text("Выбрать голос · ${selectedVoiceName ?: "системный"}") }
                            Text("Русские голоса, доступные в службе синтеза речи Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Подключение AI-шлюза", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Необязательно. Настраивается отдельно и не требуется для локальной модели.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { showGatewaySettings = !showGatewaySettings }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (showGatewaySettings) "Скрыть настройки OmniRoute" else "Настроить OmniRoute")
                            }
                            if (showGatewaySettings) OmniRoutePanel(viewModel)
                        }
                    }
                    TextButton(
                        enabled = !checkingUpdate && !downloadingUpdate,
                        onClick = {
                            scope.launch {
                                checkingUpdate = true
                                updateMessage = null
                                try {
                                    updateInfo = AppUpdater.check(BuildConfig.VERSION_CODE)
                                    if (updateInfo != null) showUpdateDialog = true
                                    updateMessage = if (updateInfo == null) "Установлена последняя версия" else null
                                } catch (error: Exception) {
                                    updateMessage = "Ошибка проверки: ${error.message ?: "нет соединения"}"
                                } finally {
                                    checkingUpdate = false
                                }
                            }
                        }
                    ) { Text(if (checkingUpdate) "Проверяю…" else "Проверить обновления") }
                    TextButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                updateMessage = "Уведомления об обновлениях уже разрешены"
                            }
                        }
                    ) { Text("Включить уведомления об обновлениях") }
                    updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Закрыть") }
            }
        )
    }


    if (showVoicePicker) {
        AlertDialog(
            onDismissRequest = { showVoicePicker = false },
            title = { Text("Голос SheV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (availableVoices.isEmpty()) {
                        Text("Русские голоса не найдены. Установи дополнительные голоса в настройках синтеза речи Android.")
                    } else {
                        availableVoices.forEach { voice ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(voice.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(if (voice.isNetworkConnectionRequired) "Требуется интернет" else "Работает офлайн", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = {
                                    speech.voice = voice
                                    selectedVoiceName = voice.name
                                    context.getSharedPreferences("shev_voice", android.content.Context.MODE_PRIVATE)
                                        .edit().putString("tts_voice", voice.name).apply()
                                    speech.speak("Привет! Я SheV. Давай поговорим.", TextToSpeech.QUEUE_FLUSH, null, "shev-voice-preview")
                                }) { Text(if (selectedVoiceName == voice.name) "Выбран" else "Слушать") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVoicePicker = false }) { Text("Готово") } }
        )
    }

    if (showUpdateDialog && updateInfo != null) {
        val update = updateInfo!!
        AlertDialog(
            onDismissRequest = { if (!downloadingUpdate) showUpdateDialog = false },
            title = { Text("Доступно обновление ${update.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(update.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "Новая версия SheV Agent готова.")
                    Text("Текущая версия: ${BuildConfig.VERSION_NAME}")
                    if (downloadingUpdate) {
                        if (updateTotal > 0L) {
                            LinearProgressIndicator(
                                progress = { (updateDownloaded.toFloat() / updateTotal.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Загружено ${(updateDownloaded * 100L / updateTotal).toInt()}%")
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    updateMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !downloadingUpdate,
                    onClick = {
                        scope.launch {
                            downloadingUpdate = true
                            updateMessage = null
                            try {
                                val started = AppUpdater.downloadAndInstall(context, update) { done, total ->
                                    updateDownloaded = done
                                    updateTotal = total
                                }
                                if (started) showUpdateDialog = false
                                else updateMessage = "Разреши SheV Agent устанавливать приложения в настройках Android и повтори."
                            } catch (error: Exception) {
                                updateMessage = "Ошибка обновления: ${error.message ?: "не удалось скачать APK"}"
                            } finally {
                                downloadingUpdate = false
                            }
                        }
                    }
                ) { Text(if (downloadingUpdate) "Загрузка…" else "Скачать и установить") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }, enabled = !downloadingUpdate) { Text("Позже") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("SheV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        voiceConversation && listening -> "Слушаю тебя"
                        voiceConversation && state.busy -> "Готовлю ответ"
                        voiceConversation -> "Голосовой диалог включён"
                        state.useOmniRoute -> "Удалённый AI"
                        state.ready -> "Личный помощник"
                        else -> "Твой AI-помощник"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (updateInfo != null) {
                Button(
                    onClick = { showUpdateDialog = true },
                    enabled = !downloadingUpdate,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF16834A), contentColor = Color.White)
                ) { Text("Обновить") }
            }
            IconButton(onClick = { showSettings = true }) {
                Text("⚙", style = MaterialTheme.typography.titleLarge)
            }
        }


        if (!state.ready && !state.useOmniRoute) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (state.verified) "Модель готова к запуску" else "Подключим интеллект SheV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Локальная модель работает на телефоне. Для удалённой модели открой настройки подключения.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.progress != null) {
                        LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                        Text("Подготовка модели: ${((state.progress ?: 0f) * 100).toInt()}%")
                        TextButton(onClick = viewModel::cancelDownload) { Text("Отменить") }
                    } else if (state.busy && !state.verified) {
                        CircularProgressIndicator()
                        Text("Проверяю модель…")
                    } else if (!state.verified) {
                        Button(onClick = viewModel::download, modifier = Modifier.fillMaxWidth()) { Text("Скачать модель · 2,58 ГБ") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать файл модели") }
                    } else {
                        Button(onClick = viewModel::launchAgent, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.busy) "Запускаю…" else "Запустить SheV")
                        }
                    }
                    Text(state.deviceSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (state.messages.isEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text("Привет! Я SheV.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Напиши мне или нажми на микрофон и просто поговори. Я отвечу здесь.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                state = chatListState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
            ) {
                itemsIndexed(state.messages, key = { index, _ -> index }) { _, line ->
                    val fromUser = line.startsWith("Вы:")
                    val message = line.substringAfter(":").trim()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (fromUser) Color(0xFF3158C8) else MaterialTheme.colorScheme.surface,
                            contentColor = if (fromUser) Color.White else MaterialTheme.colorScheme.onSurface
                        ) {
                            Text(message, modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
                Text("SheV думает…", style = MaterialTheme.typography.bodySmall)
            }
        }

        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (voiceConversation) {
            Text(voiceStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::setPrompt,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение…") },
                singleLine = false,
                maxLines = 4,
                enabled = !state.busy,
                shape = RoundedCornerShape(24.dp)
            )
            Button(
                onClick = {
                    if (voiceConversation) {
                        voiceConversation = false
                        listening = false
                        recognizer?.cancel()
                        speech.stop()
                        voiceStatus = "Голосовой диалог выключен"
                    } else {
                        voiceConversation = true
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            beginListening()
                        } else {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                shape = CircleShape,
                enabled = !state.busy || voiceConversation
            ) { Text(if (voiceConversation) "■" else "🎙") }
            Button(
                onClick = viewModel::send,
                enabled = state.prompt.isNotBlank() && !state.busy,
                shape = CircleShape
            ) { Text("➤") }
        }
    }
}
