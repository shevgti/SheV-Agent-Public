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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.window.DialogProperties
import com.shev.agent.BuildConfig
import com.shev.agent.update.AppUpdate
import com.shev.agent.update.AppUpdater
import com.shev.agent.update.UpdateCheckWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
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
    val neuralTts = remember(context) { SupertonicRussianTts(context) }
    val voicePrefs = remember(context) { context.getSharedPreferences("shev_voice", android.content.Context.MODE_PRIVATE) }
    var neuralTtsInstalled by remember { mutableStateOf(neuralTts.isInstalled()) }
    var neuralTtsDownloading by remember { mutableStateOf(false) }
    var neuralTtsDownloaded by remember { mutableStateOf(0L) }
    var neuralTtsTotal by remember { mutableStateOf(0L) }
    var neuralTtsStatus by remember { mutableStateOf("Дополнительный офлайн-пакет · 6 отобранных дикторов") }
    var selectedNeuralSpeaker by remember { mutableStateOf(voicePrefs.getInt("neural_speaker_id", 0).coerceIn(0, 9)) }
    var selectedNeuralEmotion by remember { mutableStateOf(voicePrefs.getString("neural_emotion_id", "natural") ?: "natural") }
    var neuralTtsPlaying by remember { mutableStateOf(false) }
    var previewSpeakerId by remember { mutableStateOf<Int?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var lastSpokenMessageIndex by remember { mutableStateOf(-1) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val agentTeamPrefs = remember(context) { context.getSharedPreferences("shev_agent_team", android.content.Context.MODE_PRIVATE) }
    var enabledAgentRoles by remember { mutableStateOf(agentTeamPrefs.getStringSet("enabled_roles", null)?.toSet() ?: setOf("shev_researcher", "shev_planner", "shev_engineer", "shev_reviewer")) }
    var enabledAgentSkills by remember { mutableStateOf(agentTeamPrefs.getStringSet("enabled_skills", null)?.toSet() ?: SheVAgentModelFactory.DEFAULT_SKILLS) }
    var showGatewaySettings by remember { mutableStateOf(false) }
    var showOpenAISettings by remember { mutableStateOf(false) }
    var showCodexSettings by remember { mutableStateOf(true) }
    var showGeminiSettings by remember { mutableStateOf(false) }
    var showOpenRouterSettings by remember { mutableStateOf(false) }
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

    val femaleVoices = remember(availableVoices) {
        availableVoices.filter { RussianVoiceCatalog.classify(it.name) == RussianVoiceGender.FEMALE }
            .distinctBy { RussianVoiceCatalog.identityKey(it.name) }.take(3)
    }
    val maleVoices = remember(availableVoices) {
        availableVoices.filter { RussianVoiceCatalog.classify(it.name) == RussianVoiceGender.MALE }
            .distinctBy { RussianVoiceCatalog.identityKey(it.name) }.take(5)
    }
    val unclassifiedVoices = remember(availableVoices) {
        availableVoices.filter { RussianVoiceCatalog.classify(it.name) == RussianVoiceGender.UNKNOWN }
    }

    LaunchedEffect(Unit) {
        UpdateCheckWorker.schedule(context)
        checkingUpdate = true
        var lastError: Exception? = null
        try {
            // GitHub may briefly be unreachable while the app is opening (mobile data/VPN wake-up).
            // Retry automatically so the user does not need to enter Settings and press Check.
            for (attempt in 0..2) {
                try {
                    val found = AppUpdater.check(BuildConfig.VERSION_CODE)
                    updateInfo = found
                    if (found != null) showUpdateDialog = true
                    updateMessage = if (found == null) "Установлена последняя версия" else null
                    lastError = null
                    break
                } catch (error: Exception) {
                    lastError = error
                    if (attempt < 2) kotlinx.coroutines.delay(3000L * (attempt + 1))
                }
            }
            if (updateInfo == null && lastError != null) {
                updateMessage = "Не удалось проверить обновления: ${lastError?.message ?: "ошибка сети"}"
            }
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
            previewJob?.cancel()
            neuralTts.release()
        }
    }

    LaunchedEffect(state.messages.size, state.busy, voiceConversation) {
        val messageIndex = state.messages.lastIndex
        val last = state.messages.lastOrNull()
        // A completed assistant turn is spoken once only. The previous effect could
        // run again when busy changed, causing overlapping/repeated speech.
        if (voiceConversation && !state.busy && messageIndex > lastSpokenMessageIndex &&
            last?.startsWith("SheV:") == true
        ) {
            lastSpokenMessageIndex = messageIndex
            val answer = cleanTextForSpeech(last.substringAfter(":"))
            if (answer.isNotBlank()) {
                voiceStatus = "SheV отвечает…"
                speech.stop()
                if (neuralTtsInstalled) {
                    neuralTtsPlaying = true
                    try {
                        neuralTts.speak(
                            answer,
                            selectedNeuralSpeaker,
                            SupertonicVoiceCatalog.styles.firstOrNull { it.id == selectedNeuralEmotion }?.expressionTag
                        )
                        voiceStatus = "Ответ завершён"
                        if (currentVoiceMode) beginListening()
                    } catch (error: Exception) {
                        // Do not start a second engine after a neural playback failure:
                        // it may already have emitted part of the answer.
                        voiceStatus = "Ошибка нейроголоса: ${error.message ?: "не удалось воспроизвести ответ"}."
                    } finally {
                        neuralTtsPlaying = false
                    }
                } else {
                    speech.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "shev-answer-$messageIndex")
                }
            }
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) chatListState.animateScrollToItem(state.messages.lastIndex)
    }

    if (showSettings) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showSettings = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showSettings = false }) { Text("Закрыть") }
                    }
                    Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
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
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Выбор основного AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Выбери, кто будет отвечать в основном чате. Активным может быть один основной провайдер.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("OpenAI Codex · полноценный AI-агент", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.useCodex) "Сейчас выбран Codex" else "Официальный вход через ChatGPT · без API-ключа и Termux",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = { showCodexSettings = !showCodexSettings }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (showCodexSettings) "Скрыть настройки Codex" else "Подключить Codex")
                            }
                            if (showCodexSettings) CodexProviderPanel(viewModel)
                            androidx.compose.material3.HorizontalDivider()
                            Text("Google Gemini · облачная модель", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (state.useGemini) "Сейчас выбран Gemini" else "Для работы нужен ключ Google AI Studio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { showGeminiSettings = !showGeminiSettings }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (showGeminiSettings) "Скрыть настройки Gemini" else "Настроить / выбрать Gemini")
                            }
                            if (showGeminiSettings) GeminiProviderPanel(viewModel)
                            androidx.compose.material3.HorizontalDivider()
                            Text("Gemma · локально на телефоне", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    state.useCodex || state.useGemini || state.useOpenAI || state.useOpenRouter || state.useOmniRoute -> "Сейчас выбран облачный провайдер"
                                    state.ready -> "Gemma запущена и выбрана для локальной работы"
                                    state.verified -> "Модель загружена и проверена"
                                    else -> "Модель ещё не загружена · размер около 2,58 ГБ"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.progress != null) {
                                LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                                Text("Загрузка: ${((state.progress ?: 0f) * 100).toInt()}%")
                                TextButton(onClick = viewModel::cancelDownload) { Text("Отменить загрузку") }
                            } else if (!state.verified) {
                                Button(onClick = viewModel::download, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.busy) "Подготовка…" else "Скачать Gemma · 2,58 ГБ")
                                }
                                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text("Выбрать файл Gemma")
                                }
                            } else {
                                Button(onClick = viewModel::selectLocalModel, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.ready && !state.useCodex && !state.useGemini && !state.useOpenAI && !state.useOpenRouter && !state.useOmniRoute) "Gemma выбрана" else "Выбрать Gemma")
                                }
                                OutlinedButton(onClick = viewModel::deleteModel, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text("Удалить Gemma с телефона")
                                }
                            }
                            Text(state.deviceSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Команда локальных AI-агентов", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Все роли работают на одной загруженной модели. Изменения применятся после перезапуска локальной модели.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf(
                                "shev_researcher" to ("Исследователь" to "Анализирует вопросы и факты"),
                                "shev_planner" to ("Планировщик" to "Разбивает задачи на этапы"),
                                "shev_engineer" to ("Инженер" to "Помогает с кодом и Android"),
                                "shev_reviewer" to ("Контролёр" to "Проверяет решения и ошибки")
                            ).forEach { (id, info) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(info.first, fontWeight = FontWeight.Medium)
                                        Text(info.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Checkbox(
                                        checked = id in enabledAgentRoles,
                                        onCheckedChange = { checked ->
                                            val updated = if (checked) enabledAgentRoles + id else enabledAgentRoles - id
                                            enabledAgentRoles = updated
                                            agentTeamPrefs.edit().putStringSet("enabled_roles", updated).apply()
                                        }
                                    )
                                }
                            }
                            Text("Активно: ${enabledAgentRoles.size} из 4", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Навыки SheV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Навыки добавляют рабочие инструкции агенту, не загружают отдельные модели и не требуют API.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf(
                                "research" to ("Исследование" to "Факты, источники и проверка сведений"),
                                "coding" to ("Разработка" to "Android, Kotlin, архитектура и тесты"),
                                "planning" to ("Планирование" to "Этапы, зависимости и критерии готовности"),
                                "quality" to ("Контроль качества" to "Безопасность, ошибки и регрессии")
                            ).forEach { (id, info) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(info.first, fontWeight = FontWeight.Medium)
                                        Text(info.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Checkbox(
                                        checked = id in enabledAgentSkills,
                                        onCheckedChange = { checked ->
                                            val updated = if (checked) enabledAgentSkills + id else enabledAgentSkills - id
                                            enabledAgentSkills = updated
                                            agentTeamPrefs.edit().putStringSet("enabled_skills", updated).apply()
                                        }
                                    )
                                }
                            }
                            Text("Включено: ${enabledAgentSkills.size} из 4", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Настоящие нейроголоса · Supertonic 3", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Отдельный офлайн-пакет: десять разных дикторских тембров, русский язык, работа без интернета после загрузки. Модель не входит в APK.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!neuralTtsInstalled) {
                                OutlinedButton(
                                    enabled = !neuralTtsDownloading,
                                    onClick = {
                                        scope.launch {
                                            neuralTtsDownloading = true
                                            neuralTtsStatus = "Загружаю голосовую модель…"
                                            try {
                                                neuralTts.downloadModel { done, total ->
                                                    neuralTtsDownloaded = done
                                                    neuralTtsTotal = total
                                                }
                                                neuralTtsInstalled = neuralTts.isInstalled()
                                                neuralTtsStatus = "Пакет установлен. Теперь можно прослушать все 10 дикторов."
                                            } catch (error: Exception) {
                                                neuralTtsStatus = error.message ?: "Не удалось загрузить голосовую модель."
                                            } finally {
                                                neuralTtsDownloading = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (neuralTtsDownloading) "Загрузка…" else "Загрузить 10 нейроголосов") }
                                if (neuralTtsDownloading) {
                                    if (neuralTtsTotal > 0L) {
                                        LinearProgressIndicator(
                                            progress = { (neuralTtsDownloaded.toFloat() / neuralTtsTotal.toFloat()).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            "${neuralTtsDownloaded / (1024 * 1024)} МБ из ${neuralTtsTotal / (1024 * 1024)} МБ",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            } else {
                                Text("Пакет установлен · 3 женских + 3 мужских голоса доступны офлайн", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                Text("ЖЕНСКИЕ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                for (speakerId in 0..2) {
                                    val speaker = SupertonicVoiceCatalog.speakers[speakerId]
                                    Row(
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(speaker.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (selectedNeuralSpeaker == speakerId) FontWeight.Bold else FontWeight.Normal)
                                        if (selectedNeuralSpeaker == speakerId) Text("✓", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                                        TextButton(
                                            onClick = {
                                                if (previewSpeakerId == speakerId) {
                                                    previewJob?.cancel()
                                                    previewJob = null
                                                    previewSpeakerId = null
                                                    neuralTtsPlaying = false
                                                    neuralTtsStatus = "Прослушивание остановлено."
                                                } else {
                                                    previewJob?.cancel()
                                                    previewSpeakerId = speakerId
                                                    selectedNeuralSpeaker = speakerId
                                                    voicePrefs.edit().putInt("neural_speaker_id", speakerId).apply()
                                                    neuralTtsPlaying = true
                                                    neuralTtsStatus = "Генерирую: ${speaker.title}…"
                                                    previewJob = scope.launch {
                                                        try {
                                                            neuralTts.speak("Привет! Я ${speaker.title} приложения SheV. Давай поговорим.", speakerId)
                                                            neuralTtsStatus = "Прослушивание завершено."
                                                        } catch (error: kotlinx.coroutines.CancellationException) {
                                                            neuralTtsStatus = "Прослушивание остановлено."
                                                            throw error
                                                        } catch (error: Exception) {
                                                            neuralTtsStatus = error.message ?: "Не удалось воспроизвести голос."
                                                        } finally {
                                                            if (previewSpeakerId == speakerId) {
                                                                previewSpeakerId = null
                                                                previewJob = null
                                                                neuralTtsPlaying = false
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        ) { Text(if (previewSpeakerId == speakerId) "Стоп" else "▶") }
                                                                            }
                                }
                                Text("МУЖСКИЕ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                for (speakerId in 5..7) {
                                    val speaker = SupertonicVoiceCatalog.speakers[speakerId]
                                    Row(
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(speaker.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (selectedNeuralSpeaker == speakerId) FontWeight.Bold else FontWeight.Normal)
                                        if (selectedNeuralSpeaker == speakerId) Text("✓", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                                        TextButton(
                                            onClick = {
                                                if (previewSpeakerId == speakerId) {
                                                    previewJob?.cancel()
                                                    previewJob = null
                                                    previewSpeakerId = null
                                                    neuralTtsPlaying = false
                                                    neuralTtsStatus = "Прослушивание остановлено."
                                                } else {
                                                    previewJob?.cancel()
                                                    previewSpeakerId = speakerId
                                                    selectedNeuralSpeaker = speakerId
                                                    voicePrefs.edit().putInt("neural_speaker_id", speakerId).apply()
                                                    neuralTtsPlaying = true
                                                    neuralTtsStatus = "Генерирую: ${speaker.title}…"
                                                    previewJob = scope.launch {
                                                        try {
                                                            neuralTts.speak("Привет! Я ${speaker.title} приложения SheV. Давай поговорим.", speakerId)
                                                            neuralTtsStatus = "Прослушивание завершено."
                                                        } catch (error: kotlinx.coroutines.CancellationException) {
                                                            neuralTtsStatus = "Прослушивание остановлено."
                                                            throw error
                                                        } catch (error: Exception) {
                                                            neuralTtsStatus = error.message ?: "Не удалось воспроизвести голос."
                                                        } finally {
                                                            if (previewSpeakerId == speakerId) {
                                                                previewSpeakerId = null
                                                                previewJob = null
                                                                neuralTtsPlaying = false
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        ) { Text(if (previewSpeakerId == speakerId) "Стоп" else "▶") }
                                        TextButton(onClick = {
                                            selectedNeuralSpeaker = speakerId
                                            voicePrefs.edit().putInt("neural_speaker_id", speakerId).apply()
                                        }) { Text(if (selectedNeuralSpeaker == speakerId) "Выбран" else "Выбрать") }
                                    }
                                }
                                Text("ЭМОЦИИ И МАНЕРА РЕЧИ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Экспериментальные выразительные маркеры Supertonic. В русском языке некоторые могут срабатывать неидеально.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                for (style in SupertonicVoiceCatalog.styles) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.weight(1f)) {
                                            Text(style.title, fontWeight = if (selectedNeuralEmotion == style.id) FontWeight.SemiBold else FontWeight.Normal)
                                            Text(style.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(
                                            enabled = !neuralTtsPlaying,
                                            onClick = {
                                                selectedNeuralEmotion = style.id
                                                voicePrefs.edit().putString("neural_emotion_id", style.id).apply()
                                                scope.launch {
                                                    neuralTtsPlaying = true
                                                    try {
                                                        neuralTts.speak(
                                                            "Привет! Я SheV. Рада тебя слышать. Давай обсудим это вместе.",
                                                            selectedNeuralSpeaker,
                                                            style.expressionTag
                                                        )
                                                        neuralTtsStatus = "Манера речи: ${style.title}."
                                                    } catch (error: Exception) {
                                                        neuralTtsStatus = error.message ?: "Не удалось воспроизвести выбранную манеру."
                                                    } finally {
                                                        neuralTtsPlaying = false
                                                    }
                                                }
                                            }
                                        ) { Text(if (selectedNeuralEmotion == style.id) "Выбрано · Слушать" else "Слушать") }
                                    }
                                }
                            }
                            Text(neuralTtsStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "Женские и мужские варианты сопоставлены с официальными стилями F1–F5 и M1–M5. Эмоциональные маркеры экспериментальные: в русском языке они могут звучать не всегда естественно.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Бесплатные AI-модели · OpenRouter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Единый доступ к бесплатным моделям разных разработчиков. По умолчанию используется автоматический бесплатный маршрутизатор.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { showOpenRouterSettings = !showOpenRouterSettings }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (showOpenRouterSettings) "Скрыть настройки OpenRouter" else "Настроить бесплатный OpenRouter")
                            }
                            if (showOpenRouterSettings) OpenRouterProviderPanel(viewModel)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Подключение ChatGPT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Прямой доступ к OpenAI API с отдельным ключом. Подписка ChatGPT не заменяет API-биллинг.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { showOpenAISettings = !showOpenAISettings }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (showOpenAISettings) "Скрыть настройки OpenAI" else "Настроить ChatGPT / OpenAI")
                            }
                            if (showOpenAISettings) OpenAIProviderPanel(viewModel)
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
                    Button(
                        onClick = { showSettings = false },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)
                    ) { Text("Назад") }
                }
            }
        }
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
                        state.useOpenAI -> "ChatGPT · OpenAI"
                        state.useOpenRouter -> "Бесплатные модели · OpenRouter"
                        state.useOmniRoute -> "Удалённый AI · OmniRoute"
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


        if (!state.ready && !state.useOmniRoute && !state.useOpenAI && !state.useGemini && !state.useOpenRouter) {
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
                    if (voiceConversation && state.busy) {
                        // Let the user take the floor while SheV is generating an answer.
                        viewModel.cancelResponse()
                        speech.stop()
                        recognizer?.cancel()
                        voiceStatus = "Остановил ответ. Слушаю тебя…"
                        beginListening()
                    } else if (voiceConversation) {
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
            ) { Text(if (voiceConversation && state.busy) "↻" else if (voiceConversation) "■" else "🎙") }
            Button(
                onClick = viewModel::send,
                enabled = state.prompt.isNotBlank() && !state.busy,
                shape = CircleShape
            ) { Text("➤") }
        }
    }
}


@androidx.compose.runtime.Composable
private fun VoiceChoiceRow(
    label: String,
    voice: Voice,
    selected: Boolean,
    onPreview: () -> Unit,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(
                if (voice.isNetworkConnectionRequired) "Требуется интернет" else "Работает офлайн",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onPreview) { Text("Слушать") }
        TextButton(onClick = onSelect) { Text(if (selected) "Выбран" else "Выбрать") }
    }
}


private fun cleanTextForSpeech(source: String): String {
    return source
        .replace(Regex("`{1,3}"), "")
        .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
