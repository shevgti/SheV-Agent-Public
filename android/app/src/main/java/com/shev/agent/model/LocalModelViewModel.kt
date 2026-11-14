package com.shev.agent.model

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.adk.kt.events.Event
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.shev.agent.AgentCapability
import com.shev.agent.data.ConversationMessageEntity
import com.shev.agent.data.ConversationStateRepository
import com.shev.agent.data.ConversationSummaryRead
import com.shev.agent.data.AgentTaskDraftRequest
import com.shev.agent.data.AgentTaskDraftResult
import com.shev.agent.data.RoomAgentTaskAuthorizationVerifier
import com.shev.agent.data.RoomAgentTaskRepository
import com.shev.agent.data.LocalMessageCipher
import com.shev.agent.data.SheVAgentDatabaseProvider
import com.shev.agent.creator.AgentManifestParser
import com.shev.agent.creator.AgentRunPolicy
import com.shev.agent.tools.AgentTool
import com.shev.agent.tools.LocalTimeTool
import com.shev.agent.tools.OpenMeteoWeatherTool
import com.shev.agent.tools.PublicWebSearchTool
import com.shev.agent.tools.SuspendingAgentTool
import com.shev.agent.tools.AgentToolDescriptor
import com.shev.agent.tools.AgentToolExecutionContext
import com.shev.agent.tools.AgentToolExecutor
import com.shev.agent.tools.AgentToolRegistry
import com.shev.agent.tools.AgentToolResult
import com.shev.agent.tools.ToolInputField
import com.shev.agent.tools.ToolInputSchema
import com.shev.agent.tools.ToolInputType
import com.shev.agent.tools.SheVAgentAdkTools
import java.util.UUID
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect

data class CodexApproval(
    val requestId: Long,
    val kind: String,
    val title: String,
    val detail: String
)

data class LocalModelUiState(
    val verified: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val ready: Boolean = false,
    val useOmniRoute: Boolean = false,
    val useOpenAI: Boolean = false,
    val useGemini: Boolean = false,
    val useOpenRouter: Boolean = false,
    val useCodex: Boolean = false,
    val codexConnecting: Boolean = false,
    val codexConnected: Boolean = false,
    val codexEmail: String = "",
    val codexPlanType: String = "",
    val codexModelId: String = "",
    val codexRateUsedPercent: Int? = null,
    val codexStatus: String = "Codex не подключён",
    val codexApproval: CodexApproval? = null,
    val streamingMessage: String? = null,
    val backendName: String? = null,
    val deviceSummary: String = "Проверяю характеристики устройства…",
    val prompt: String = "",
    val messages: List<String> = emptyList()
)

/** Owns the native runtime for the Activity lifetime, not a recyclable Compose list item. */
class LocalModelViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ModelStore(application.applicationContext)
    private val database = SheVAgentDatabaseProvider.get(application.applicationContext)
    private val dao = database.agentDao()
    private val conversationStateRepository = ConversationStateRepository(database)
    private val taskRepository = RoomAgentTaskRepository(
        database,
        RoomAgentTaskAuthorizationVerifier(dao)
    )
    private val mutableState = MutableStateFlow(LocalModelUiState())
    val state: StateFlow<LocalModelUiState> = mutableState.asStateFlow()
    private var runtime: SheVAgentRuntime? = null
    private var toolExecutor: AgentToolExecutor? = null
    private var codexClient: CodexAppServerClient? = null
    private var codexActiveThreadId: String? = null
    private var codexActiveTurnId: String? = null
    private var downloadJob: Job? = null
    private var responseJob: Job? = null

    init {
        val gatewayPreferences = application.getSharedPreferences("shev_omniroute", Application.MODE_PRIVATE)
        val openAiPreferences = application.getSharedPreferences("shev_openai", Application.MODE_PRIVATE)
        val geminiPreferences = application.getSharedPreferences("shev_gemini", Application.MODE_PRIVATE)
        val openRouterPreferences = application.getSharedPreferences("shev_openrouter", Application.MODE_PRIVATE)
        val codexPreferences = application.getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
        val useCodex = codexPreferences.getBoolean("use_for_primary_chat", false)
        val useOpenRouter = !useCodex && openRouterPreferences.getBoolean("use_for_primary_chat", false)
        val useGemini = !useCodex && !useOpenRouter && geminiPreferences.getBoolean("use_for_primary_chat", false)
        val useOpenAI = !useCodex && !useOpenRouter && !useGemini && openAiPreferences.getBoolean("use_for_primary_chat", false)
        val useOmniRoute = !useCodex && !useOpenRouter && !useGemini && !useOpenAI && gatewayPreferences.getBoolean("use_for_primary_chat", false)
        mutableState.update { it.copy(useCodex = useCodex, useOmniRoute = useOmniRoute, useOpenAI = useOpenAI, useGemini = useGemini, useOpenRouter = useOpenRouter) }
        viewModelScope.launch(Dispatchers.IO) {
            val deviceSummary = DeviceDiagnostics.summary(getApplication())
            val available = store.findVerifiedModel() != null
            try {
                dao.markInterruptedConversationMessagesFailed()
                // Load only completed conversational turns. Pending/failed rows are not display history.
                val history = dao.getRecentCompletedConversationMessages(HISTORY_DISPLAY_LIMIT).asReversed()
                    .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                mutableState.update {
                    it.copy(
                        verified = available,
                        deviceSummary = deviceSummary,
                        messages = history.map(::formatMessage)
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(
                        verified = available,
                        deviceSummary = DeviceDiagnostics.summary(getApplication()),
                        error = "Не удалось расшифровать локальную историю. Данные не будут показаны: ${failure.message ?: "ошибка ключа"}"
                    )
                }
            }
        }
    }

    suspend fun connectCodex(context: Context) {
        mutableState.update { it.copy(codexConnecting = true, codexStatus = "Запускаю встроенный Codex…", error = null) }
        try {
            val details = withContext(Dispatchers.IO) {
                val client = ensureCodexClient()
                var account = client.accountRead(true).get("account")
                    ?.takeIf { it.isJsonObject }?.asJsonObject
                if (account?.get("type")?.asString != "chatgpt") {
                    val login = client.beginChatGptLogin()
                    val loginId = login.get("loginId")?.asString
                        ?: throw IllegalStateException("Codex не вернул идентификатор входа.")
                    val authUrl = login.get("authUrl")?.asString
                        ?: throw IllegalStateException("Codex не вернул ссылку входа ChatGPT.")
                    withContext(Dispatchers.Main) { client.openAuthUrl(context, authUrl) }
                    val completed = client.awaitLogin(loginId)
                    if (completed.get("success")?.asBoolean != true) {
                        throw IllegalStateException(completed.get("error")?.asString ?: "Вход через ChatGPT не завершён.")
                    }
                    account = client.accountRead(true).get("account")
                        ?.takeIf { it.isJsonObject }?.asJsonObject
                }
                val accountDetails = account
                    ?: throw IllegalStateException("Codex не вернул данные аккаунта ChatGPT.")
                require(accountDetails.get("type")?.asString == "chatgpt") {
                    "Codex не подтвердил вход через аккаунт ChatGPT."
                }
                val email = accountDetails.get("email")?.asString.orEmpty()
                val planType = accountDetails.get("planType")?.asString.orEmpty()
                val models = client.listModels().get("data")
                    ?.takeIf { it.isJsonArray }?.asJsonArray
                val selectedModel = models?.mapNotNull { item ->
                    item.takeIf { it.isJsonObject }?.asJsonObject
                }?.firstOrNull { it.get("isDefault")?.asBoolean == true }
                    ?: models?.firstOrNull { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalStateException("Codex не вернул доступных моделей для этого аккаунта.")
                val modelId = selectedModel.get("model")?.asString
                    ?: selectedModel.get("id")?.asString
                    ?: throw IllegalStateException("Codex не вернул идентификатор доступной модели.")
                val modelName = selectedModel.get("displayName")?.asString ?: modelId
                val usedPercent = runCatching {
                    client.request("account/rateLimits/read")
                        .getAsJsonObject("rateLimits")?.getAsJsonObject("primary")
                        ?.get("usedPercent")?.asInt
                }.getOrNull()
                Triple(
                    Triple(email, planType, modelId),
                    modelName,
                    usedPercent
                )
            }
            getApplication<Application>().getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
                .edit().putString("model_id", details.first.third).apply()
            mutableState.update {
                it.copy(
                    codexConnected = true,
                    codexEmail = details.first.first,
                    codexPlanType = details.first.second,
                    codexModelId = details.first.third,
                    codexRateUsedPercent = details.third,
                    codexStatus = "ChatGPT подключён · ${details.first.second.ifBlank { "план не указан" }} · ${details.second}"
                )
            }
            setCodexPrimaryEnabled(true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update {
                it.copy(codexConnected = false, codexStatus = failure.message ?: "Не удалось подключить Codex.")
            }
        } finally {
            mutableState.update { it.copy(codexConnecting = false) }
        }
    }

    suspend fun disconnectCodex() {
        mutableState.update { it.copy(codexConnecting = true) }
        try {
            withContext(Dispatchers.IO) {
                val client = codexClient
                if (client != null) {
                    runCatching { client.logout() }
                    client.close()
                    codexClient = null
                }
                java.io.File(getApplication<Application>().noBackupFilesDir, "codex-home").deleteRecursively()
            }
            getApplication<Application>().getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
                .edit().remove("thread_id").putBoolean("use_for_primary_chat", false).apply()
            mutableState.update {
                it.copy(
                    useCodex = false,
                    codexConnected = false,
                    codexEmail = "",
                    codexPlanType = "",
                    codexRateUsedPercent = null,
                    codexApproval = null,
                    codexStatus = "Аккаунт ChatGPT отключён."
                )
            }
        } finally {
            mutableState.update { it.copy(codexConnecting = false) }
        }
    }

    fun approveCodexRequest(requestId: Long, accepted: Boolean) {
        val client = codexClient ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                client.respondToServerRequest(
                    requestId,
                    JsonObject().apply { addProperty("decision", if (accepted) "accept" else "decline") }
                )
            }.onFailure { failure ->
                mutableState.update { it.copy(error = failure.message ?: "Не удалось отправить решение Codex.") }
            }
            mutableState.update { it.copy(codexApproval = null) }
        }
    }

    private suspend fun ensureCodexClient(): CodexAppServerClient {
        codexClient?.takeIf { it.isAlive }?.let { return it }
        codexClient?.close()
        val client = CodexAppServerClient(getApplication()) { code ->
            mutableState.update {
                it.copy(
                    codexConnected = false,
                    codexStatus = "Codex остановился (код $code). Повтори подключение."
                )
            }
        }
        client.start()
        codexClient = client
        return client
    }

    fun setCodexPrimaryEnabled(enabled: Boolean) {
        val preferences = getApplication<Application>().getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
        preferences.edit().putBoolean("use_for_primary_chat", enabled).apply()
        if (enabled) {
            listOf("shev_omniroute", "shev_openai", "shev_gemini", "shev_openrouter").forEach { name ->
                getApplication<Application>().getSharedPreferences(name, Application.MODE_PRIVATE)
                    .edit().putBoolean("use_for_primary_chat", false).apply()
            }
        }
        mutableState.update {
            it.copy(
                useCodex = enabled,
                useOmniRoute = if (enabled) false else it.useOmniRoute,
                useOpenAI = if (enabled) false else it.useOpenAI,
                useGemini = if (enabled) false else it.useGemini,
                useOpenRouter = if (enabled) false else it.useOpenRouter
            )
        }
    }

    fun setOmniRoutePrimaryEnabled(enabled: Boolean) {
        getApplication<Application>()
            .getSharedPreferences("shev_omniroute", Application.MODE_PRIVATE)
            .edit()
            .putBoolean("use_for_primary_chat", enabled)
            .apply()
        if (enabled) {
            getApplication<Application>().getSharedPreferences("shev_openai", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
            getApplication<Application>().getSharedPreferences("shev_gemini", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
            getApplication<Application>().getSharedPreferences("shev_openrouter", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
            getApplication<Application>().getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
        }
        mutableState.update {
            it.copy(
                useCodex = if (enabled) false else it.useCodex,
                useOmniRoute = enabled,
                useOpenAI = if (enabled) false else it.useOpenAI,
                useGemini = if (enabled) false else it.useGemini,
                useOpenRouter = if (enabled) false else it.useOpenRouter
            )
        }
    }

    fun setOpenAIPrimaryEnabled(enabled: Boolean) {
        getApplication<Application>()
            .getSharedPreferences("shev_openai", Application.MODE_PRIVATE)
            .edit()
            .putBoolean("use_for_primary_chat", enabled)
            .apply()
        if (enabled) {
            getApplication<Application>().getSharedPreferences("shev_omniroute", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
            getApplication<Application>().getSharedPreferences("shev_gemini", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
            getApplication<Application>().getSharedPreferences("shev_openrouter", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
            getApplication<Application>().getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
        }
        mutableState.update {
            it.copy(
                useCodex = if (enabled) false else it.useCodex,
                useOpenAI = enabled,
                useOmniRoute = if (enabled) false else it.useOmniRoute,
                useGemini = if (enabled) false else it.useGemini,
                useOpenRouter = if (enabled) false else it.useOpenRouter
            )
        }
    }

    fun setOpenRouterPrimaryEnabled(enabled: Boolean) {
        getApplication<Application>().getSharedPreferences("shev_openrouter", Application.MODE_PRIVATE)
            .edit().putBoolean("use_for_primary_chat", enabled).apply()
        if (enabled) {
            listOf("shev_gemini", "shev_openai", "shev_omniroute", "shev_codex").forEach { name ->
                getApplication<Application>().getSharedPreferences(name, Application.MODE_PRIVATE)
                    .edit().putBoolean("use_for_primary_chat", false).apply()
            }
        }
        mutableState.update {
            it.copy(
                useOpenRouter = enabled,
                useCodex = if (enabled) false else it.useCodex,
                useGemini = if (enabled) false else it.useGemini,
                useOpenAI = if (enabled) false else it.useOpenAI,
                useOmniRoute = if (enabled) false else it.useOmniRoute
            )
        }
    }

    fun setGeminiPrimaryEnabled(enabled: Boolean) {
        getApplication<Application>().getSharedPreferences("shev_gemini", Application.MODE_PRIVATE)
            .edit().putBoolean("use_for_primary_chat", enabled).apply()
        if (enabled) {
            getApplication<Application>().getSharedPreferences("shev_openai", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
            getApplication<Application>().getSharedPreferences("shev_omniroute", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
            getApplication<Application>().getSharedPreferences("shev_openrouter", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
            getApplication<Application>().getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
        }
        mutableState.update { it.copy(useCodex = if (enabled) false else it.useCodex, useGemini = enabled, useOpenAI = if (enabled) false else it.useOpenAI, useOmniRoute = if (enabled) false else it.useOmniRoute, useOpenRouter = if (enabled) false else it.useOpenRouter) }
    }

    /** Cancels the active answer so voice input can take priority. */
    fun cancelResponse() {
        val client = codexClient
        val threadId = codexActiveThreadId
        val turnId = codexActiveTurnId
        if (client != null && threadId != null && turnId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { client.interrupt(threadId, turnId) }
            }
        }
        responseJob?.cancel()
        responseJob = null
        mutableState.update { it.copy(busy = false, error = null, streamingMessage = null) }
    }

    fun setPrompt(value: String) {
        mutableState.update { it.copy(prompt = value) }
    }

    fun download() {
        if (downloadJob?.isActive == true || mutableState.value.busy) return
        mutableState.update { it.copy(error = null, progress = 0f) }
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                store.download { update ->
                    mutableState.update { it.copy(progress = update.fraction ?: 0f) }
                }
                mutableState.update { it.copy(verified = true, progress = null) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(progress = null) }
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(progress = null, error = failure.message ?: "Не удалось скачать модель.")
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Select the on-device Gemma backend and disable mutually exclusive cloud providers. */
    fun selectLocalModel() {
        listOf("shev_gemini", "shev_openai", "shev_openrouter", "shev_omniroute", "shev_codex").forEach { name ->
            getApplication<Application>().getSharedPreferences(name, Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
        }
        mutableState.update {
            it.copy(useCodex = false, useGemini = false, useOpenAI = false, useOpenRouter = false, useOmniRoute = false)
        }
        if (mutableState.value.verified) {
            launchAgent()
        } else {
            mutableState.update { it.copy(error = "Сначала загрузи Gemma или выбери файл модели.") }
        }
    }

    /** Stop the native runtime before deleting its verified model and incomplete downloads. */
    fun deleteModel() {
        if (downloadJob?.isActive == true || mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val active = runtime
                runtime = null
                if (active != null) active.close()
                store.deleteModel()
                mutableState.update { it.copy(verified = false, ready = false, busy = false, progress = null, backendName = null) }
            } catch (failure: Exception) {
                mutableState.update { it.copy(busy = false, error = failure.message ?: "Не удалось удалить модель.") }
            }
        }
    }

    fun importModel(uri: Uri) {
        if (downloadJob?.isActive == true || mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, progress = null, error = null) }
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                store.importModel(uri) { update ->
                    mutableState.update { it.copy(progress = update.fraction) }
                }
                mutableState.update { it.copy(verified = true, busy = false, progress = null) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(busy = false, progress = null) }
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(busy = false, progress = null, error = failure.message ?: "Не удалось восстановить модель из файла.")
                }
            }
        }
    }

    fun launchAgent() {
        if (runtime != null || mutableState.value.busy || !mutableState.value.verified) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val registry = AgentToolRegistry().apply {
                    register(
                        AgentToolDescriptor(
                            id = SheVAgentAdkTools.TOOL_ID,
                            title = "Просмотр инструментов",
                            description = "Показывает список локально зарегистрированных инструментов",
                            capabilities = setOf(AgentCapability.ANALYZE_DATA),
                            inputSchema = ToolInputSchema(
                                listOf(ToolInputField("query", ToolInputType.STRING, required = false, maxLength = 32))
                            )
                        )
                    )
                    register(
                        AgentToolDescriptor(
                            id = SheVAgentAdkTools.CURRENT_TIME_TOOL_ID,
                            title = "Текущее местное время",
                            description = "Возвращает фактические дату, время и часовой пояс устройства",
                            capabilities = setOf(AgentCapability.ANALYZE_DATA),
                            inputSchema = ToolInputSchema(
                                listOf(ToolInputField(
                                    "query",
                                    ToolInputType.STRING,
                                    required = true,
                                    maxLength = 8,
                                    allowedValues = setOf("now")
                                ))
                            )
                        )
                    )
                    register(
                        AgentToolDescriptor(
                            id = SheVAgentAdkTools.WEATHER_TOOL_ID,
                            title = "Получить актуальную погоду",
                            description = "Получает текущую погоду в указанном городе из публичного онлайн-сервиса Open-Meteo",
                            capabilities = setOf(AgentCapability.READ_REMOTE_DATA),
                            inputSchema = ToolInputSchema(
                                listOf(ToolInputField("city", ToolInputType.STRING, required = true, maxLength = 100))
                            )
                        )
                    )
                    register(
                        AgentToolDescriptor(
                            id = SheVAgentAdkTools.WEB_SEARCH_TOOL_ID,
                            title = "Поиск в интернете",
                            description = "Ищет публичную веб-выдачу по запросу",
                            capabilities = setOf(AgentCapability.READ_REMOTE_DATA),
                            inputSchema = ToolInputSchema(
                                listOf(ToolInputField("query", ToolInputType.STRING, required = true, maxLength = 200))
                            )
                        )
                    )
                    register(
                        AgentToolDescriptor(
                            id = SheVAgentAdkTools.CREATE_TASK_DRAFT_ID,
                            title = "Создать черновик поручения",
                            description = "Сохраняет локальное поручение в ожидании подтверждения; ничего не выполняет",
                            capabilities = setOf(AgentCapability.CREATE_DRAFT),
                            inputSchema = ToolInputSchema(
                                listOf(ToolInputField("title", ToolInputType.STRING, required = true, maxLength = 2_000))
                            )
                        )
                    )
                }
                val taskDraftHandler = SuspendingAgentTool { input ->
                    val title = runCatching {
                        JsonParser.parseString(input).asJsonObject.get("title")?.asString
                    }.getOrNull()
                    if (title.isNullOrBlank()) {
                        AgentToolResult(false, "Пустое или некорректное поручение.", "INVALID_TITLE")
                    } else {
                        val now = System.currentTimeMillis()
                        // Stable for one minute: a retried tool call cannot create duplicate drafts.
                        val requestBucket = now / 60_000L
                        val requestSeed = "shev-task-draft-v1:$requestBucket:$title"
                        val taskId = UUID.nameUUIDFromBytes(requestSeed.toByteArray()).toString()
                        val eventId = UUID.nameUUIDFromBytes("event:$taskId".toByteArray()).toString()
                        when (val result = taskRepository.createDraft(
                            AgentTaskDraftRequest(
                                taskId = taskId,
                                title = title,
                                eventId = eventId,
                                idempotencyKey = "task-create:$taskId",
                                occurredAtEpochMillis = now
                            )
                        )) {
                            is AgentTaskDraftResult.Created -> AgentToolResult(
                                true,
                                "Черновик поручения «${result.state.task.title}» сохранён. Он ожидает подтверждения во вкладке «Память». Никакое действие не запускалось."
                            )
                            is AgentTaskDraftResult.Replayed -> AgentToolResult(
                                true,
                                "Это поручение уже сохранено и ожидает подтверждения во вкладке «Память». Повторная запись не создана."
                            )
                            AgentTaskDraftResult.InvalidRequest -> AgentToolResult(false, "Поручение не сохранено: некорректный запрос.", "INVALID_REQUEST")
                            AgentTaskDraftResult.Conflict -> AgentToolResult(false, "Поручение не сохранено: обнаружен конфликт записи.", "CONFLICT")
                            AgentTaskDraftResult.NotFound -> AgentToolResult(false, "Поручение не найдено после записи.", "NOT_FOUND")
                        }
                    }
                }
                val currentTimeHandler = AgentTool { _ ->
                    AgentToolResult(true, LocalTimeTool().readCurrentTime())
                }
                val executor = AgentToolExecutor(
                    registry,
                    mapOf(
                        SheVAgentAdkTools.TOOL_ID to AgentTool {
                            AgentToolResult(true, registry.list().joinToString { it.id })
                        },
                        SheVAgentAdkTools.CURRENT_TIME_TOOL_ID to currentTimeHandler
                    ),
                    suspendingHandlers = mapOf(
                        SheVAgentAdkTools.CREATE_TASK_DRAFT_ID to taskDraftHandler,
                        SheVAgentAdkTools.WEATHER_TOOL_ID to SuspendingAgentTool { input ->
                            val city = runCatching {
                                JsonParser.parseString(input).asJsonObject.get("city")?.asString
                            }.getOrNull()
                            if (city.isNullOrBlank()) {
                                AgentToolResult(false, "Укажи город.", "CITY_REQUIRED")
                            } else {
                                try {
                                    AgentToolResult(true, OpenMeteoWeatherTool().currentWeather(city))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    AgentToolResult(false, failure.message ?: "Не удалось получить погоду.", "WEATHER_UNAVAILABLE")
                                }
                            }
                        },
                        SheVAgentAdkTools.WEB_SEARCH_TOOL_ID to SuspendingAgentTool { input ->
                            val query = runCatching {
                                JsonParser.parseString(input).asJsonObject.get("query")?.asString
                            }.getOrNull()
                            if (query.isNullOrBlank()) {
                                AgentToolResult(false, "Укажи поисковый запрос.", "QUERY_REQUIRED")
                            } else {
                                try {
                                    AgentToolResult(true, PublicWebSearchTool().search(query))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    AgentToolResult(false, failure.message ?: "Не удалось выполнить поиск.", "WEB_SEARCH_UNAVAILABLE")
                                }
                            }
                        }

                    )
                )
                val tools = SheVAgentAdkTools(
                    executor,
                    AgentToolExecutionContext(
                        grantedCapabilities = setOf(
                            AgentCapability.ANALYZE_DATA,
                            AgentCapability.CREATE_DRAFT,
                            AgentCapability.READ_REMOTE_DATA
                        ),
                        explicitlyApprovedCapabilities = setOf(AgentCapability.READ_REMOTE_DATA)
                    )
                )
                runtime = SheVAgentRuntime.load(getApplication(), store, tools)
                toolExecutor = executor
                mutableState.update { it.copy(ready = true, backendName = runtime?.backendName) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                toolExecutor = null
                mutableState.update {
                    it.copy(error = failure.message ?: failure.javaClass.simpleName + ": не удалось запустить модель.")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun sendLocalTimeReply(message: String) {
        val answer = LocalTimeTool().readCurrentTime()
        mutableState.update {
            it.copy(prompt = "", busy = true, error = null, messages = it.messages + "Вы: $message")
        }
        responseJob = viewModelScope.launch(Dispatchers.IO) {
            val userMessageId = UUID.randomUUID().toString()
            try {
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = userMessageId,
                        role = ConversationContextBuilder.ROLE_USER,
                        content = LocalMessageCipher.encrypt(message),
                        status = ConversationContextBuilder.STATUS_PENDING,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = UUID.randomUUID().toString(),
                        role = ConversationContextBuilder.ROLE_ASSISTANT,
                        content = LocalMessageCipher.encrypt(answer),
                        status = ConversationContextBuilder.STATUS_COMPLETE,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                dao.updateConversationMessageStatus(
                    userMessageId,
                    ConversationContextBuilder.STATUS_COMPLETE
                )
                mutableState.update { it.copy(messages = it.messages + "SheV: $answer") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runCatching {
                    dao.updateConversationMessageStatus(
                        userMessageId,
                        ConversationContextBuilder.STATUS_FAILED
                    )
                }
                mutableState.update {
                    it.copy(error = failure.message ?: "Не удалось получить местное время устройства.")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun send() {
        val message = mutableState.value.prompt.trim()
        if (message.isEmpty() || mutableState.value.busy) return

        val preferences = getApplication<Application>().getSharedPreferences(
            "shev_omniroute",
            Application.MODE_PRIVATE
        )
        // Device-local facts must not depend on model quality or a remote provider.
        if (LocalTimeIntentRouter.isCurrentTimeRequest(message)) {
            sendLocalTimeReply(message)
            return
        }

        if (mutableState.value.useCodex) {
            sendViaCodex(message)
            return
        }
        if (mutableState.value.useOpenRouter) {
            sendViaOpenRouter(message)
            return
        }
        if (mutableState.value.useGemini) {
            sendViaGemini(message)
            return
        }
        if (mutableState.value.useOpenAI) {
            sendViaOpenAI(message)
            return
        }
        if (preferences.getBoolean("use_for_primary_chat", false)) {
            sendViaOmniRoute(message)
            return
        }

        val active = runtime
        if (active == null) {
            mutableState.update {
                it.copy(error = "Локальный агент ещё не запущен. Нажми «Запустить локального агента» или включи OmniRoute для основного чата.")
            }
            return
        }
        mutableState.update {
            it.copy(prompt = "", busy = true, error = null, messages = it.messages + "Вы: $message")
        }
        responseJob = viewModelScope.launch(Dispatchers.IO) {
            val userMessageId = UUID.randomUUID().toString()
            var eventFailed = false
            try {
                // The ADK in-memory session is not durable. Rebuild bounded context from
                // the encrypted transcript on every turn so later messages never lose
                // earlier conversation context after the first response.
                // Exclude incomplete rows in SQL before decrypting so they cannot crowd out usable context.
                val history = dao.getRecentCompletedConversationMessages(CONTEXT_HISTORY_LIMIT).asReversed()
                    .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                val summaryRead = conversationStateRepository.readSummary()
                val rollingSummary = (summaryRead as? ConversationSummaryRead.Ready)?.plaintext
                val summaryCheckpoint = (summaryRead as? ConversationSummaryRead.Ready)?.throughMessageId
                val promptWithContext = ConversationContextBuilder.build(
                    chronologicalHistory = history,
                    currentMessage = message,
                    rollingSummary = rollingSummary,
                    summaryThroughMessageId = summaryCheckpoint
                )
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = userMessageId,
                        role = ConversationContextBuilder.ROLE_USER,
                        content = LocalMessageCipher.encrypt(message),
                        status = ConversationContextBuilder.STATUS_PENDING,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                active.run(promptWithContext).collect { event: Event ->
                    val answer = event.content?.parts.orEmpty()
                        .mapNotNull { it.text }
                        .filter { it.isNotBlank() }
                        .joinToString("")
                    if (!event.partial && answer.isNotBlank()) {
                        dao.appendConversationMessage(
                            ConversationMessageEntity(
                                id = UUID.randomUUID().toString(),
                                role = ConversationContextBuilder.ROLE_ASSISTANT,
                                content = LocalMessageCipher.encrypt(answer),
                                status = ConversationContextBuilder.STATUS_COMPLETE,
                                createdAtEpochMillis = System.currentTimeMillis()
                            )
                        )
                        dao.updateConversationMessageStatus(
                            userMessageId,
                            ConversationContextBuilder.STATUS_COMPLETE
                        )
                        mutableState.update { it.copy(messages = it.messages + "SheV: $answer") }
                    }
                    if (!event.partial && !event.errorMessage.isNullOrBlank()) {
                        eventFailed = true
                        mutableState.update { it.copy(error = event.errorMessage) }
                    }
                }
                dao.updateConversationMessageStatus(
                    userMessageId,
                    if (eventFailed) ConversationContextBuilder.STATUS_FAILED
                    else ConversationContextBuilder.STATUS_COMPLETE
                )
            } catch (cancelled: CancellationException) {
                // A pending row is marked failed on the next app start if this process is interrupted.
                throw cancelled
            } catch (failure: Throwable) {
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_FAILED)
                mutableState.update {
                    it.copy(error = failure.message ?: failure.javaClass.simpleName + ": ошибка выполнения модели.")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }


    /**
     * Remote primary-chat path. Only the current user message and bounded encrypted
     * conversation context are sent after the user explicitly enables this provider.
     * Remote model output cannot directly execute local tools.
     */
    private fun sendViaOmniRoute(message: String) {
        mutableState.update {
            it.copy(prompt = "", busy = true, error = null, messages = it.messages + "Вы: $message")
        }
        responseJob = viewModelScope.launch(Dispatchers.IO) {
            val userMessageId = UUID.randomUUID().toString()
            try {
                val preferences = getApplication<Application>().getSharedPreferences(
                    "shev_omniroute",
                    Application.MODE_PRIVATE
                )
                val baseUrl = preferences.getString("base_url", null)
                    ?: throw IllegalStateException("Сначала настрой адрес OmniRoute.")
                val modelId = preferences.getString("model_id", null)
                    ?: throw IllegalStateException("Не выбрана модель OmniRoute.")
                val encryptedToken = preferences.getString("encrypted_token", null)
                    ?: throw IllegalStateException("Сначала сохрани API-ключ OmniRoute.")
                val token = OmniRouteSecretCipher.decrypt(encryptedToken)
                require(token.isNotBlank()) { "Сохранённый API-ключ пустой. Введи его заново." }

                val history = dao.getRecentCompletedConversationMessages(CONTEXT_HISTORY_LIMIT).asReversed()
                    .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                val summaryRead = conversationStateRepository.readSummary()
                val rollingSummary = (summaryRead as? ConversationSummaryRead.Ready)?.plaintext
                val summaryCheckpoint = (summaryRead as? ConversationSummaryRead.Ready)?.throughMessageId
                val promptWithContext = ConversationContextBuilder.build(
                    chronologicalHistory = history,
                    currentMessage = message,
                    rollingSummary = rollingSummary,
                    summaryThroughMessageId = summaryCheckpoint
                )

                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = userMessageId,
                        role = ConversationContextBuilder.ROLE_USER,
                        content = LocalMessageCipher.encrypt(message),
                        status = ConversationContextBuilder.STATUS_PENDING,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                val candidates = ModelFallbackRouter.candidates(modelId)
                val (usedModel, answer) = ModelFallbackRouter.firstSuccessful(candidates) { candidate ->
                    val config = OpenAiCompatibleConfig(baseUrl, candidate, token)
                    if (preferences.getBoolean("enable_agent_tools", false)) {
                        val input = JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("role", "user")
                                addProperty("content", promptWithContext)
                            })
                        }
                        val bridge = OpenAiLocalToolBridge(taskRepository)
                        try {
                            OpenAiResponsesClient(config).completeWithTools(
                                history = input,
                                instructions = "Ты — SheV, личный помощник пользователя. Отвечай по-русски. Ты можешь вызывать только явно перечисленные инструменты SheV. Используй создание черновика только когда пользователь прямо просит сохранить поручение. Не утверждай, что действие выполнено, если инструмент вернул not_executed. Не раскрывай ключи и секреты.",
                                tools = bridge.definitions,
                                executeTool = bridge::execute
                            )
                        } catch (error: IllegalStateException) {
                            val message = error.message.orEmpty()
                            val unsupported = message.contains("Responses API не найден") ||
                                message.contains("HTTP 404") ||
                                message.contains("HTTP 405") ||
                                message.contains("HTTP 501")
                            if (!unsupported) throw error
                            OpenAiCompatibleClient(config).complete(
                                promptWithContext,
                                systemInstruction = "Ты — SheV, личный помощник пользователя. Отвечай по-русски. Подключённый шлюз не поддерживает вызов инструментов; у тебя нет доступа к локальным инструментам SheV. Не утверждай, что выполнил действие. Не раскрывай ключи и секреты."
                            )
                        }
                    } else {
                        OpenAiCompatibleClient(config).complete(
                            promptWithContext,
                            systemInstruction = "Ты — SheV, личный помощник пользователя. Отвечай по-русски. Это удалённый режим без локальных инструментов: не утверждай, что выполнил действие. Не раскрывай API-ключи и секреты."
                        )
                    }
                }
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = UUID.randomUUID().toString(),
                        role = ConversationContextBuilder.ROLE_ASSISTANT,
                        content = LocalMessageCipher.encrypt(answer),
                        status = ConversationContextBuilder.STATUS_COMPLETE,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_COMPLETE)
                mutableState.update { it.copy(messages = it.messages + "SheV: $answer") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_FAILED)
                mutableState.update {
                    it.copy(error = failure.message ?: failure.javaClass.simpleName + ": ошибка удалённой модели.")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Persistent ChatGPT agent session hosted by the official embedded Codex App Server. */
    private fun sendViaCodex(message: String) {
        mutableState.update {
            it.copy(
                prompt = "",
                busy = true,
                error = null,
                streamingMessage = null,
                messages = it.messages + "Вы: $message"
            )
        }
        responseJob = viewModelScope.launch(Dispatchers.IO) {
            val userMessageId = UUID.randomUUID().toString()
            var collector: Job? = null
            try {
                val preferences = getApplication<Application>().getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
                val client = ensureCodexClient()
                val account = client.accountRead(true).get("account")
                    ?.takeIf { it.isJsonObject }?.asJsonObject
                require(account?.get("type")?.asString == "chatgpt") {
                    "Сначала подключи ChatGPT в настройках Codex."
                }
                val savedThreadId = preferences.getString("thread_id", null)
                val threadId = if (savedThreadId.isNullOrBlank()) {
                    client.startThread(
                        preferences.getString("model_id", mutableState.value.codexModelId).orEmpty()
                    )
                } else {
                    runCatching { client.resumeThread(savedThreadId) }
                        .getOrElse {
                            client.startThread(
                                preferences.getString("model_id", mutableState.value.codexModelId).orEmpty()
                            )
                        }
                }
                val isNewThread = savedThreadId.isNullOrBlank() || threadId != savedThreadId
                preferences.edit().putString("thread_id", threadId).apply()
                codexActiveThreadId = threadId

                val inputText = if (isNewThread) {
                    val history = dao.getRecentCompletedConversationMessages(CONTEXT_HISTORY_LIMIT).asReversed()
                        .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                    val summaryRead = conversationStateRepository.readSummary()
                    ConversationContextBuilder.build(
                        chronologicalHistory = history,
                        currentMessage = message,
                        rollingSummary = (summaryRead as? ConversationSummaryRead.Ready)?.plaintext,
                        summaryThroughMessageId = (summaryRead as? ConversationSummaryRead.Ready)?.throughMessageId
                    )
                } else message

                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = userMessageId,
                        role = ConversationContextBuilder.ROLE_USER,
                        content = LocalMessageCipher.encrypt(message),
                        status = ConversationContextBuilder.STATUS_PENDING,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )

                val started = client.startTurn(threadId, inputText)
                val turnId = started.getAsJsonObject("turn")?.get("id")?.asString
                    ?: throw IllegalStateException("Codex не вернул идентификатор хода.")
                codexActiveTurnId = turnId
                val answer = StringBuilder()
                val completed = CompletableDeferred<JsonObject>()

                collector = launch(Dispatchers.IO) {
                    client.events.collect { event ->
                        val method = event.get("method")?.asString.orEmpty()
                        val params = event.getAsJsonObject("params") ?: JsonObject()
                        val eventThreadId = params.get("threadId")?.asString
                        val eventTurnId = params.get("turnId")?.asString
                            ?: params.getAsJsonObject("turn")?.get("id")?.asString
                        if (eventThreadId != null && eventThreadId != threadId) return@collect
                        if (eventTurnId != null && eventTurnId != turnId) return@collect

                        when (method) {
                            "item/agentMessage/delta" -> {
                                if (eventTurnId == null || eventTurnId == turnId) {
                                    val delta = params.get("delta")?.asString.orEmpty()
                                    if (delta.isNotEmpty()) {
                                        answer.append(delta)
                                        mutableState.update {
                                            it.copy(streamingMessage = "SheV: ${answer}")
                                        }
                                    }
                                }
                            }
                            "item/completed" -> {
                                val item = params.getAsJsonObject("item")
                                if (item?.get("type")?.asString == "agentMessage" && answer.isEmpty()) {
                                    val text = item.getAsJsonArray("content")?.mapNotNull { part ->
                                        part.takeIf { it.isJsonObject }?.asJsonObject
                                            ?.get("text")?.asString
                                    }?.joinToString("").orEmpty()
                                    if (text.isNotBlank()) answer.append(text)
                                }
                            }
                            "turn/completed" -> {
                                if (eventTurnId == turnId || params.getAsJsonObject("turn")?.get("id")?.asString == turnId) {
                                    completed.complete(params)
                                }
                            }
                            "item/commandExecution/requestApproval",
                            "item/fileChange/requestApproval" -> {
                                val requestId = runCatching { event.get("id")?.asLong }.getOrNull()
                                if (requestId != null) {
                                    val command = params.get("command")?.let { value ->
                                        if (value.isJsonArray) value.asJsonArray.joinToString(" ") { it.asString }
                                        else value.asString
                                    }.orEmpty()
                                    val detail = when (method) {
                                        "item/commandExecution/requestApproval" -> buildString {
                                            append(command.ifBlank { "Команда не указана" })
                                            params.get("cwd")?.asString?.let { append("\nРабочая папка: $it") }
                                            params.get("reason")?.asString?.let { append("\nПричина: $it") }
                                        }
                                        else -> params.toString()
                                    }
                                    mutableState.update {
                                        it.copy(
                                            codexApproval = CodexApproval(
                                                requestId = requestId,
                                                kind = method,
                                                title = if (method.contains("command")) "Разрешить команду Codex?" else "Разрешить изменение файлов?",
                                                detail = detail
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val completion = withTimeout(180_000L) { completed.await() }
                collector.cancel()
                collector = null
                val turn = completion.get("turn")?.takeIf { it.isJsonObject }?.asJsonObject
                val status = turn?.get("status")?.asString.orEmpty()
                if (status.isNotBlank() && status != "completed") {
                    val turnError = turn?.get("error")?.let { error ->
                        if (error.isJsonObject) error.asJsonObject.get("message")?.asString
                        else if (error.isJsonPrimitive) error.asString
                        else null
                    }
                    throw IllegalStateException(turnError ?: "Codex завершил ход со статусом $status.")
                }
                val finalAnswer = answer.toString().trim()
                if (finalAnswer.isBlank()) {
                    throw IllegalStateException("Codex завершил ход, но не вернул текстовый ответ.")
                }
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = UUID.randomUUID().toString(),
                        role = ConversationContextBuilder.ROLE_ASSISTANT,
                        content = LocalMessageCipher.encrypt(finalAnswer),
                        status = ConversationContextBuilder.STATUS_COMPLETE,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_COMPLETE)
                mutableState.update {
                    it.copy(
                        messages = it.messages + "SheV: $finalAnswer",
                        streamingMessage = null,
                        codexApproval = null,
                        codexStatus = "Codex готов"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_FAILED)
                mutableState.update {
                    it.copy(
                        error = failure.message ?: failure.javaClass.simpleName + ": ошибка Codex App Server.",
                        streamingMessage = null,
                        codexApproval = null
                    )
                }
            } finally {
                collector?.cancel()
                codexActiveTurnId = null
                mutableState.update { it.copy(busy = false, streamingMessage = null) }
            }
        }
    }

    /** Direct OpenRouter API route using the OpenAI-compatible chat completions endpoint. */
    private fun sendViaOpenRouter(message: String) {
        mutableState.update {
            it.copy(prompt = "", busy = true, error = null, messages = it.messages + "Вы: $message")
        }
        responseJob = viewModelScope.launch(Dispatchers.IO) {
            val userMessageId = UUID.randomUUID().toString()
            try {
                val preferences = getApplication<Application>().getSharedPreferences(
                    "shev_openrouter",
                    Application.MODE_PRIVATE
                )
                val modelId = preferences.getString("model_id", "openrouter/free") ?: "openrouter/free"
                val encryptedToken = preferences.getString("encrypted_token", null)
                    ?: throw IllegalStateException("Сначала сохрани API-ключ OpenRouter в настройках.")
                val token = OpenRouterSecretCipher.decrypt(encryptedToken)
                require(token.isNotBlank()) { "Сохранённый ключ OpenRouter пустой. Введи его заново." }

                val history = dao.getRecentCompletedConversationMessages(CONTEXT_HISTORY_LIMIT).asReversed()
                    .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                val summaryRead = conversationStateRepository.readSummary()
                val rollingSummary = (summaryRead as? ConversationSummaryRead.Ready)?.plaintext
                val summaryCheckpoint = (summaryRead as? ConversationSummaryRead.Ready)?.throughMessageId
                val promptWithContext = ConversationContextBuilder.build(
                    chronologicalHistory = history,
                    currentMessage = message,
                    rollingSummary = rollingSummary,
                    summaryThroughMessageId = summaryCheckpoint
                )
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = userMessageId,
                        role = ConversationContextBuilder.ROLE_USER,
                        content = LocalMessageCipher.encrypt(message),
                        status = ConversationContextBuilder.STATUS_PENDING,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                val answer = OpenAiCompatibleClient(
                    OpenAiCompatibleConfig(
                        "https://openrouter.ai/api/v1",
                        modelId,
                        token,
                        "OpenRouter"
                    )
                ).complete(
                    promptWithContext,
                    systemInstruction = "Ты — SheV, личный AI-помощник пользователя. Всегда отвечай по-русски. Это удалённый режим: у тебя нет прямого доступа к телефону, локальным файлам или действиям приложения. Не утверждай, что выполнил действие. Не раскрывай API-ключи и секреты."
                )
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = UUID.randomUUID().toString(),
                        role = ConversationContextBuilder.ROLE_ASSISTANT,
                        content = LocalMessageCipher.encrypt(answer),
                        status = ConversationContextBuilder.STATUS_COMPLETE,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_COMPLETE)
                mutableState.update { it.copy(messages = it.messages + "SheV: $answer") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_FAILED)
                mutableState.update {
                    it.copy(error = failure.message ?: failure.javaClass.simpleName + ": ошибка OpenRouter API.")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Direct Gemini API route using Google's OpenAI-compatible endpoint. */
    private fun sendViaGemini(message: String) {
        mutableState.update {
            it.copy(prompt = "", busy = true, error = null, messages = it.messages + "Вы: $message")
        }
        responseJob = viewModelScope.launch(Dispatchers.IO) {
            val userMessageId = UUID.randomUUID().toString()
            try {
                val preferences = getApplication<Application>().getSharedPreferences(
                    "shev_gemini",
                    Application.MODE_PRIVATE
                )
                val modelId = preferences.getString("model_id", "gemini-2.5-flash") ?: "gemini-2.5-flash"
                val encryptedToken = preferences.getString("encrypted_token", null)
                    ?: throw IllegalStateException("Сначала сохрани API-ключ Gemini в настройках.")
                val token = GeminiSecretCipher.decrypt(encryptedToken)
                require(token.isNotBlank()) { "Сохранённый ключ Gemini пустой. Введи его заново." }

                val history = dao.getRecentCompletedConversationMessages(CONTEXT_HISTORY_LIMIT).asReversed()
                    .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                val summaryRead = conversationStateRepository.readSummary()
                val rollingSummary = (summaryRead as? ConversationSummaryRead.Ready)?.plaintext
                val summaryCheckpoint = (summaryRead as? ConversationSummaryRead.Ready)?.throughMessageId
                val promptWithContext = ConversationContextBuilder.build(
                    chronologicalHistory = history,
                    currentMessage = message,
                    rollingSummary = rollingSummary,
                    summaryThroughMessageId = summaryCheckpoint
                )
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = userMessageId,
                        role = ConversationContextBuilder.ROLE_USER,
                        content = LocalMessageCipher.encrypt(message),
                        status = ConversationContextBuilder.STATUS_PENDING,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                val answer = OpenAiCompatibleClient(
                    OpenAiCompatibleConfig(
                        "https://generativelanguage.googleapis.com/v1beta/openai",
                        modelId,
                        token
                    )
                ).complete(
                    promptWithContext,
                    systemInstruction = "Ты — SheV, личный AI-помощник пользователя. Всегда отвечай по-русски. Это удалённый режим: у тебя нет прямого доступа к телефону, локальным файлам или действиям приложения. Не утверждай, что выполнил действие. Не раскрывай API-ключи и секреты."
                )
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = UUID.randomUUID().toString(),
                        role = ConversationContextBuilder.ROLE_ASSISTANT,
                        content = LocalMessageCipher.encrypt(answer),
                        status = ConversationContextBuilder.STATUS_COMPLETE,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_COMPLETE)
                mutableState.update { it.copy(messages = it.messages + "SheV: $answer") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_FAILED)
                mutableState.update {
                    it.copy(error = failure.message ?: failure.javaClass.simpleName + ": ошибка Gemini API.")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Direct OpenAI Responses API route. The model may call only the explicitly
     * registered, schema-validated low-risk local tools exposed by OpenAiLocalToolBridge.
     */
    private fun sendViaOpenAI(message: String) {
        mutableState.update {
            it.copy(prompt = "", busy = true, error = null, messages = it.messages + "Вы: $message")
        }
        responseJob = viewModelScope.launch(Dispatchers.IO) {
            val userMessageId = UUID.randomUUID().toString()
            try {
                val preferences = getApplication<Application>().getSharedPreferences(
                    "shev_openai",
                    Application.MODE_PRIVATE
                )
                val modelId = preferences.getString("model_id", "gpt-4.1-mini")
                    ?: "gpt-4.1-mini"
                val encryptedToken = preferences.getString("encrypted_token", null)
                    ?: throw IllegalStateException("Сначала сохрани API-ключ OpenAI.")
                val token = OpenAISecretCipher.decrypt(encryptedToken)
                require(token.isNotBlank()) { "Сохранённый API-ключ OpenAI пустой. Введи его заново." }

                val history = dao.getRecentCompletedConversationMessages(CONTEXT_HISTORY_LIMIT).asReversed()
                    .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                val summaryRead = conversationStateRepository.readSummary()
                val rollingSummary = (summaryRead as? ConversationSummaryRead.Ready)?.plaintext
                val summaryCheckpoint = (summaryRead as? ConversationSummaryRead.Ready)?.throughMessageId
                val promptWithContext = ConversationContextBuilder.build(
                    chronologicalHistory = history,
                    currentMessage = message,
                    rollingSummary = rollingSummary,
                    summaryThroughMessageId = summaryCheckpoint
                )
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = userMessageId,
                        role = ConversationContextBuilder.ROLE_USER,
                        content = LocalMessageCipher.encrypt(message),
                        status = ConversationContextBuilder.STATUS_PENDING,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                val responseInput = JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", promptWithContext)
                    })
                }
                val localTools = OpenAiLocalToolBridge(taskRepository)
                val answer = OpenAiResponsesClient(
                    OpenAiCompatibleConfig("https://api.openai.com/v1", modelId, token)
                ).completeWithTools(
                    history = responseInput,
                    instructions = "Ты — SheV, персональный AI-помощник пользователя. Всегда отвечай по-русски. Ты работаешь внутри Android-приложения SheV Agent. В твоём распоряжении только три локальных инструмента, перечисленных в запросе: точное местное время, просмотр доступных инструментов и создание черновика поручения. Вызывай создание черновика только если пользователь явно просит сохранить или добавить поручение; черновик остаётся на вкладке «Память» и ждёт подтверждения, ничего не исполняется. Не пытайся выполнять другие действия, не придумывай результаты инструментов, не проси пользователя отправлять API-ключи в чат. Все вызовы проверяются приложением.",
                    tools = localTools.definitions,
                    executeTool = { name, argumentsJson -> localTools.execute(name, argumentsJson) }
                )
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = UUID.randomUUID().toString(),
                        role = ConversationContextBuilder.ROLE_ASSISTANT,
                        content = LocalMessageCipher.encrypt(answer),
                        status = ConversationContextBuilder.STATUS_COMPLETE,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_COMPLETE)
                mutableState.update { it.copy(messages = it.messages + "SheV: $answer") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_FAILED)
                mutableState.update {
                    it.copy(error = failure.message ?: failure.javaClass.simpleName + ": ошибка OpenAI API.")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Only descriptors backed by real, schema-validated executor handlers are exposed. */
    fun availableAgentTools(): List<AgentToolDescriptor> = toolExecutor?.availableTools().orEmpty()

    fun availableAgentToolIds(): Set<String> =
        availableAgentTools().mapTo(linkedSetOf()) { it.id }

    /** Runs only an explicitly approved, offline, tool-free specialist definition. */
    suspend fun runApprovedSpecialist(manifestJson: String, input: String): String {
        require(input.isNotBlank()) { "Опиши задачу для специалиста." }
        val manifest = AgentManifestParser.parse(manifestJson)
        val decision = AgentRunPolicy.evaluate(manifest)
        check(decision.allowed) { decision.reason ?: "Запуск специалиста запрещён политикой." }
        val active = runtime ?: throw IllegalStateException("Сначала запусти локальную модель SheV.")
        if (mutableState.value.busy) {
            throw IllegalStateException("Модель уже выполняет другую задачу.")
        }
        mutableState.update { it.copy(busy = true, error = null) }
        try {
            return active.runSpecialist(manifest, input)
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    /** Runs a bounded tool-free creator turn; it cannot execute or approve generated agents. */
    suspend fun generateAgentCreatorOutputFromPrompt(creatorPrompt: String): String {
        require(creatorPrompt.isNotBlank()) { "Пустой запрос конструктора." }
        val active = runtime ?: throw IllegalStateException(
            "Локальная модель не запущена. Сначала запусти SheV Agent."
        )
        if (mutableState.value.busy) {
            throw IllegalStateException("Модель уже выполняет другую задачу.")
        }
        mutableState.update { it.copy(busy = true, error = null) }
        try {
            return withContext(Dispatchers.IO) {
                val response = StringBuilder()
                var modelError: String? = null
                withTimeout(120_000L) {
                    active.runCreator(creatorPrompt).collect { event: Event ->
                        val answer = event.content?.parts.orEmpty()
                            .mapNotNull { it.text }
                            .filter { it.isNotBlank() }
                            .joinToString("")
                        if (!event.partial && answer.isNotBlank()) response.append(answer)
                        if (!event.partial && !event.errorMessage.isNullOrBlank()) {
                            modelError = event.errorMessage
                        }
                    }
                }
                modelError?.let { throw IllegalStateException("Ошибка локальной модели: $it") }
                response.toString().trim().also {
                    if (it.isBlank()) throw IllegalStateException("Модель не вернула паспорт агента.")
                }
            }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    private fun formatMessage(message: ConversationMessageEntity): String {
        val speaker = if (message.role == ConversationContextBuilder.ROLE_USER) "Вы" else "SheV"
        val suffix = if (message.status != ConversationContextBuilder.STATUS_COMPLETE) " (не завершено)" else ""
        return "$speaker: ${message.content}$suffix"
    }

    override fun onCleared() {
        val active = runtime
        runtime = null
        toolExecutor = null
        val codex = codexClient
        codexClient = null
        if (active != null || codex != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try { active?.close() } catch (_: Throwable) { /* best-effort native cleanup */ }
                try { codex?.close() } catch (_: Throwable) { /* best-effort process cleanup */ }
            }
        }
        super.onCleared()
    }

    private companion object {
        const val HISTORY_DISPLAY_LIMIT = 80
        const val CONTEXT_HISTORY_LIMIT = 12
    }
}
