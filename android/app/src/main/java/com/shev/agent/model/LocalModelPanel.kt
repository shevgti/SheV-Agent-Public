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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.DialogProperties
import com.shev.agent.ui.LiveWidgetsOverlay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.Locale

@Composable
fun LocalModelPanel(
    viewModel: LocalModelViewModel,
    onOpenCreator: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenTools: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val liveWidgets by viewModel.liveWidgets.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val workspacePrefs = remember(context) { context.getSharedPreferences("megaagent_device_bridge", android.content.Context.MODE_PRIVATE) }
    var workspaceUri by remember { mutableStateOf(workspacePrefs.getString("workspace_tree_uri", null)) }
    val workspacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            workspacePrefs.edit().putString("workspace_tree_uri", uri.toString()).apply()
            workspaceUri = uri.toString()
        }
    }
    var listening by remember { mutableStateOf(false) }
    var voiceConversation by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("Нажми на микрофон, чтобы начать голосовой диалог") }
    var availableVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var selectedVoiceName by remember { mutableStateOf(context.getSharedPreferences("shev_voice", android.content.Context.MODE_PRIVATE).getString("tts_voice", null)) }
    val voicePrefs = remember(context) { context.getSharedPreferences("shev_voice", android.content.Context.MODE_PRIVATE) }
    val piperVoiceStore = remember(context) { RussianPiperVoiceStore(context) }
    var lastSpokenMessageIndex by remember { mutableStateOf(-1) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAppMenu by remember { mutableStateOf(false) }
    val agentTeamPrefs = remember(context) { context.getSharedPreferences("shev_agent_team", android.content.Context.MODE_PRIVATE) }
    var enabledAgentRoles by remember { mutableStateOf(agentTeamPrefs.getStringSet("enabled_roles", null)?.toSet() ?: setOf("shev_researcher", "shev_planner", "shev_engineer", "shev_reviewer", "shev_executor")) }
    var enabledAgentSkills by remember { mutableStateOf(agentTeamPrefs.getStringSet("enabled_skills", null)?.toSet() ?: SheVAgentModelFactory.DEFAULT_SKILLS) }
    var showGatewaySettings by remember { mutableStateOf(false) }
    var showOpenAISettings by remember { mutableStateOf(false) }
    var showDeepSeekSettings by remember { mutableStateOf(false) }
    var showCodexSettings by remember { mutableStateOf(true) }
    var showGeminiSettings by remember { mutableStateOf(false) }
    var showOpenRouterSettings by remember { mutableStateOf(false) }
    var yandexWeatherKey by remember {
        mutableStateOf(context.getSharedPreferences("shev_weather", android.content.Context.MODE_PRIVATE)
            .getString("yandex_api_key", "").orEmpty())
    }
    var weatherKeySaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var piperPlaybackJob by remember { mutableStateOf<Job?>(null) }
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

    LaunchedEffect(state.messages.size, state.busy, voiceConversation) {
        val messageIndex = state.messages.lastIndex
        val last = state.messages.lastOrNull()
        if (voiceConversation && !state.busy && messageIndex > lastSpokenMessageIndex &&
            last?.startsWith("SheV:") == true
        ) {
            lastSpokenMessageIndex = messageIndex
            val answer = cleanTextForSpeech(last.substringAfter(":"))
            if (answer.isNotBlank()) {
                voiceStatus = "SheV отвечает…"
                recognizer?.cancel()
                speech.stop()
                val selectedPiperName = voicePrefs.getString("piper_voice", null)
                val selectedPiperVoice = RussianPiperVoice.entries.firstOrNull { it.name == selectedPiperName }
                if (selectedPiperVoice != null && piperVoiceStore.isInstalled(selectedPiperVoice)) {
                    piperPlaybackJob?.cancel()
                    piperPlaybackJob = scope.launch {
                        try {
                            piperVoiceStore.speakSample(context, selectedPiperVoice, answer)
                            voiceStatus = "Ответ завершён · ${selectedPiperVoice.title}"
                            if (currentVoiceMode) beginListening()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            voiceStatus = "Ошибка Piper: ${error.message ?: "перехожу на системный голос"}"
                            speech.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "shev-answer-$messageIndex")
                        }
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Локальная модель", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Ternary Bonsai 2 · 9B", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Локальная модель работает непосредственно на телефоне. Облачные ключи и сторонние AI-подключения для неё не нужны.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Файл: Ternary-Bonsai-2-9B-Q2_0.gguf · GGUF Q2_0 · около 2,56 ГБ", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    when {
                                        state.bonsaiReady -> "Модель запущена и прошла короткий тест генерации."
                                        state.bonsaiModelInstalled -> "Файл модели установлен и проверен."
                                        state.bonsaiRuntimeInstalled -> "ARM64-движок установлен; модель ещё не установлена."
                                        else -> "Модель и ARM64-движок ещё не установлены."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (state.bonsaiProgress != null) {
                                    LinearProgressIndicator(progress = { state.bonsaiProgress ?: 0f }, modifier = Modifier.fillMaxWidth())
                                    Text(state.bonsaiStage ?: "Установка Bonsai")
                                    TextButton(onClick = viewModel::cancelBonsaiInstall) { Text("Отменить") }
                                } else if (!state.bonsaiModelInstalled || !state.bonsaiRuntimeInstalled) {
                                    Button(onClick = viewModel::installBonsai, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (state.bonsaiModelInstalled || state.bonsaiRuntimeInstalled) "Установить недостающий компонент" else "Скачать Bonsai 9B")
                                    }
                                } else if (!state.bonsaiReady) {
                                    Button(onClick = viewModel::launchBonsai, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Запустить и проверить") }
                                } else {
                                    Button(onClick = viewModel::selectBonsaiModel, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (state.useBonsai) "Bonsai выбрана для чата" else "Выбрать Bonsai для чата")
                                    }
                                }
                                Text(state.deviceSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Button(
                        onClick = { showSettings = false },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)
                    ) { Text("Назад") }
                }
            }
        }
    }


    state.codexApproval?.let { approval ->
        AlertDialog(
            onDismissRequest = { viewModel.approveCodexRequest(approval.requestId, false) },
            title = { Text(approval.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (approval.kind.contains("command")) "Codex хочет выполнить команду:" else "Codex хочет изменить файлы:")
                    Text(approval.detail.take(2400), style = MaterialTheme.typography.bodySmall)
                    Text("Проверь команду, рабочую папку и пути. Разрешение выдаётся только после твоего нажатия.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.approveCodexRequest(approval.requestId, true) }) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.approveCodexRequest(approval.requestId, false) }) { Text("Отклонить") }
            }
        )
    }


    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "MegaAgent",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Box {
                IconButton(onClick = { showAppMenu = true }) {
                    Text("☰", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(expanded = showAppMenu, onDismissRequest = { showAppMenu = false }) {
                    DropdownMenuItem(text = { Text("Создатель агентов") }, onClick = { showAppMenu = false; onOpenCreator() })
                    DropdownMenuItem(text = { Text("Поручения") }, onClick = { showAppMenu = false; onOpenTasks() })
                    DropdownMenuItem(text = { Text("Инструменты и навыки") }, onClick = { showAppMenu = false; onOpenTools() })
                    DropdownMenuItem(text = { Text("Папка проекта") }, onClick = { showAppMenu = false; workspacePicker.launch(workspaceUri?.let { android.net.Uri.parse(it) }) })
                    DropdownMenuItem(text = { Text("Проверить права телефона") }, onClick = {
                        showAppMenu = false
                        viewModel.setPrompt("Проверь права доступа к моему телефону и покажи, какие разрешения включены.")
                    })
                    DropdownMenuItem(text = { Text("Специальные возможности") }, onClick = {
                        showAppMenu = false
                        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    })
                    DropdownMenuItem(text = { Text("Настройки модели") }, onClick = { showAppMenu = false; showSettings = true })
                }
            }
        }


        if (!state.ready && !state.useOmniRoute && !state.useOpenAI && !state.useDeepSeek && !state.useGemini && !state.useOpenRouter && !state.useCodex) {
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                modifier = Modifier.fillMaxSize(),
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
                            modifier = Modifier.clickable {
                                clipboard.setText(AnnotatedString(message))
                                android.widget.Toast.makeText(context, "Текст скопирован", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (fromUser) Color(0xFF3158C8) else MaterialTheme.colorScheme.surface,
                            contentColor = if (fromUser) Color.White else MaterialTheme.colorScheme.onSurface
                        ) {
                            Text(message, modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp))
                        }
                    }
                }
                state.streamingMessage?.let { streaming ->
                    item(key = "codex-streaming") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ) {
                                Text(streaming.substringAfter(":").trim(), modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp))
                            }
                        }
                    }
                }
            }
        }

        // Render the overlay even before the first chat message and keep it as a
        // sibling above the scrollable transcript, so live cards remain visible.
        LiveWidgetsOverlay(
            widgets = liveWidgets,
            onMove = viewModel::moveLiveWidget,
            onRemove = viewModel::removeLiveWidget,
            onResize = viewModel::resizeLiveWidget
        )
        }

        // Keep status and the message composer below the transcript viewport.
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
                        piperPlaybackJob?.cancel()
                        recognizer?.cancel()
                        voiceStatus = "Остановил ответ. Слушаю тебя…"
                        beginListening()
                    } else if (voiceConversation) {
                        voiceConversation = false
                        listening = false
                        recognizer?.cancel()
                        speech.stop()
                        piperPlaybackJob?.cancel()
                        voiceStatus = "Голосовой диалог выключен"
                    } else {
                        lastSpokenMessageIndex = state.messages.lastIndex
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
