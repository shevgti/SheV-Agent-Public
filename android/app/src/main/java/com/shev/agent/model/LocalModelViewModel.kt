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
import com.shev.agent.AgentPhase
import com.shev.agent.AgentTaskExecutionCoordinator
import com.shev.agent.AgentPlanJsonCodec
import com.shev.agent.AgentPlanValidator
import com.shev.agent.AgentWorkflowCommand
import com.shev.agent.data.AgentSkillLibrary
import com.shev.agent.data.ConversationMessageEntity
import com.shev.agent.data.ConversationStateRepository
import com.shev.agent.data.ConversationSummaryRead
import com.shev.agent.data.AgentTaskDraftRequest
import com.shev.agent.data.AgentTaskEntity
import com.shev.agent.data.AgentTaskTransitionRequest
import com.shev.agent.data.AgentTaskRepositoryResult
import com.shev.agent.data.AgentTaskDraftResult
import com.shev.agent.data.RoomAgentTaskAuthorizationVerifier
import com.shev.agent.data.RoomAgentTaskRepository
import com.shev.agent.data.LocalMessageCipher
import com.shev.agent.data.SheVAgentDatabaseProvider
import com.shev.agent.creator.AgentManifestParser
import com.shev.agent.creator.AgentRunPolicy
import com.shev.agent.tools.AgentTool
import com.shev.agent.tools.LocalTimeTool
import com.shev.agent.ui.LiveWeatherClient
import com.shev.agent.ui.LiveWidget
import com.shev.agent.ui.LiveWidgetAction
import com.shev.agent.ui.LiveWidgetCommand
import com.shev.agent.ui.LiveWidgetIntentRouter
import com.shev.agent.ui.LiveWidgetPolicy
import com.shev.agent.ui.LiveWidgetRepository
import com.shev.agent.ui.LiveWidgetType
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    val useDeepSeek: Boolean = false,
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
    val messages: List<String> = emptyList(),
    val taskExecutionMessage: String? = null
)

/** Owns the native runtime for the Activity lifetime, not a recyclable Compose list item. */
class LocalModelViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ModelStore(application.applicationContext)
    private val database = SheVAgentDatabaseProvider.get(application.applicationContext)
    private val dao = database.agentDao()
    private val skillLibrary = AgentSkillLibrary(dao)
    private val conversationStateRepository = ConversationStateRepository(database)
    private val liveWidgetRepository = LiveWidgetRepository(application.applicationContext)
    private val mutableLiveWidgets = MutableStateFlow(liveWidgetRepository.load())
    val liveWidgets: StateFlow<List<LiveWidget>> = mutableLiveWidgets.asStateFlow()
    private val taskRepository = RoomAgentTaskRepository(
        database,
        RoomAgentTaskAuthorizationVerifier(dao)
    )
    private val mutableState = MutableStateFlow(LocalModelUiState())
    val state: StateFlow<LocalModelUiState> = mutableState.asStateFlow()
    val tasks = dao.observeTasks()
    val taskSteps = dao.observeAllTaskSteps()
    private var runtime: SheVAgentRuntime? = null
    private var toolExecutor: AgentToolExecutor? = null
    private var agentToolRegistry: AgentToolRegistry? = null
    private var agentExecutionContext: AgentToolExecutionContext? = null
    private val taskExecutionJobs = ConcurrentHashMap<String, Job>()
    private var codexClient: CodexAppServerClient? = null
    private val codexConnectInFlight = AtomicBoolean(false)
    private var codexActiveThreadId: String? = null
    private var codexActiveTurnId: String? = null
    private var downloadJob: Job? = null
    private var responseJob: Job? = null

    init {
        val gatewayPreferences = application.getSharedPreferences("shev_omniroute", Application.MODE_PRIVATE)
        val openAiPreferences = application.getSharedPreferences("shev_openai", Application.MODE_PRIVATE)
        val geminiPreferences = application.getSharedPreferences("shev_gemini", Application.MODE_PRIVATE)
        val openRouterPreferences = application.getSharedPreferences("shev_openrouter", Application.MODE_PRIVATE)
        val deepSeekPreferences = application.getSharedPreferences("shev_deepseek", Application.MODE_PRIVATE)
        val codexPreferences = application.getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
        val useCodex = codexPreferences.getBoolean("use_for_primary_chat", false)
        val useOpenRouter = !useCodex && openRouterPreferences.getBoolean("use_for_primary_chat", false)
        val useGemini = !useCodex && !useOpenRouter && geminiPreferences.getBoolean("use_for_primary_chat", false)
        val useOpenAI = !useCodex && !useOpenRouter && !useGemini && openAiPreferences.getBoolean("use_for_primary_chat", false)
        val useDeepSeek = !useCodex && !useOpenRouter && !useGemini && !useOpenAI &&
            deepSeekPreferences.getBoolean("use_for_primary_chat", false)
        val useOmniRoute = !useCodex && !useOpenRouter && !useGemini && !useOpenAI && !useDeepSeek &&
            gatewayPreferences.getBoolean("use_for_primary_chat", false)
        mutableState.update {
            it.copy(
                useCodex = useCodex,
                useOmniRoute = useOmniRoute,
                useOpenAI = useOpenAI,
                useDeepSeek = useDeepSeek,
                useGemini = useGemini,
                useOpenRouter = useOpenRouter
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Install the curated starter playbooks once. Existing user-created or disabled
            // skills are preserved; only verified built-ins are injected into future prompts.
            runCatching { skillLibrary.seedBuiltInSkills() }
            val deviceSummary = DeviceDiagnostics.summary(getApplication())
            val available = store.findVerifiedModel() != null
            try {
                dao.markInterruptedConversationMessagesFailed()
                recoverInterruptedAgentTasks()
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
        if (!codexConnectInFlight.compareAndSet(false, true)) return
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
            codexConnectInFlight.set(false)
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
        val client = CodexAppServerClient(getApplication()) { code, diagnostics ->
            val detail = diagnostics.ifBlank { "stderr пуст." }
            mutableState.update {
                it.copy(
                    codexConnected = false,
                    codexStatus = "Codex остановился (код $code). $detail"
                )
            }
        }
        // Publish the client before startup so a failed initialize can still be closed.
        codexClient = client
        try {
            client.start()
            return client
        } catch (failure: Exception) {
            client.close()
            if (codexClient === client) codexClient = null
            throw IllegalStateException(
                "Не удалось запустить Codex App Server: ${failure.message ?: failure.javaClass.simpleName}",
                failure
            )
        }
    }

    fun setCodexPrimaryEnabled(enabled: Boolean) {
        val preferences = getApplication<Application>().getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
        preferences.edit().putBoolean("use_for_primary_chat", enabled).apply()
        if (enabled) {
            listOf("shev_omniroute", "shev_openai", "shev_gemini", "shev_openrouter", "shev_deepseek").forEach { name ->
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
                useOpenRouter = if (enabled) false else it.useOpenRouter,
                useDeepSeek = if (enabled) false else it.useDeepSeek
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
            getApplication<Application>().getSharedPreferences("shev_deepseek", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
        }
        mutableState.update {
            it.copy(
                useCodex = if (enabled) false else it.useCodex,
                useDeepSeek = if (enabled) false else it.useDeepSeek,
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
            getApplication<Application>().getSharedPreferences("shev_deepseek", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
        }
        mutableState.update {
            it.copy(
                useCodex = if (enabled) false else it.useCodex,
                useDeepSeek = if (enabled) false else it.useDeepSeek,
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
            listOf("shev_gemini", "shev_openai", "shev_omniroute", "shev_codex", "shev_deepseek").forEach { name ->
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
                useOmniRoute = if (enabled) false else it.useOmniRoute,
                useDeepSeek = if (enabled) false else it.useDeepSeek
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
            getApplication<Application>().getSharedPreferences("shev_deepseek", Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
        }
        mutableState.update {
            it.copy(
                useCodex = if (enabled) false else it.useCodex,
                useGemini = enabled,
                useOpenAI = if (enabled) false else it.useOpenAI,
                useDeepSeek = if (enabled) false else it.useDeepSeek,
                useOmniRoute = if (enabled) false else it.useOmniRoute,
                useOpenRouter = if (enabled) false else it.useOpenRouter
            )
        }
    }

    fun setDeepSeekPrimaryEnabled(enabled: Boolean) {
        getApplication<Application>().getSharedPreferences("shev_deepseek", Application.MODE_PRIVATE)
            .edit().putBoolean("use_for_primary_chat", enabled).apply()
        if (enabled) {
            listOf("shev_openai", "shev_gemini", "shev_openrouter", "shev_omniroute", "shev_codex").forEach { name ->
                getApplication<Application>().getSharedPreferences(name, Application.MODE_PRIVATE)
                    .edit().putBoolean("use_for_primary_chat", false).apply()
            }
        }
        mutableState.update {
            it.copy(
                useDeepSeek = enabled,
                useCodex = if (enabled) false else it.useCodex,
                useOpenAI = if (enabled) false else it.useOpenAI,
                useGemini = if (enabled) false else it.useGemini,
                useOpenRouter = if (enabled) false else it.useOpenRouter,
                useOmniRoute = if (enabled) false else it.useOmniRoute
            )
        }
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
        listOf("shev_gemini", "shev_openai", "shev_openrouter", "shev_omniroute", "shev_codex", "shev_deepseek").forEach { name ->
            getApplication<Application>().getSharedPreferences(name, Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
        }
        mutableState.update {
            it.copy(useCodex = false, useGemini = false, useOpenAI = false, useDeepSeek = false, useOpenRouter = false, useOmniRoute = false)
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
                val tools = createAgentToolHost()
                runtime = SheVAgentRuntime.load(getApplication(), store, tools)
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

    fun moveLiveWidget(id: String, x: Float, y: Float) {
        val changed = mutableLiveWidgets.value.map { if (it.id == id) it.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f)) else it }
        if (LiveWidgetPolicy.validate(changed) == null) {
            liveWidgetRepository.save(changed)
            mutableLiveWidgets.value = changed
        }
    }

    fun resizeLiveWidget(id: String, scale: Float) {
        val changed = mutableLiveWidgets.value.map { if (it.id == id) it.copy(scale = scale.coerceIn(0.75f, 1.5f)) else it }
        if (LiveWidgetPolicy.validate(changed) == null) {
            liveWidgetRepository.save(changed)
            mutableLiveWidgets.value = changed
        }
    }

    fun removeLiveWidget(id: String) {
        val changed = mutableLiveWidgets.value.filterNot { it.id == id }
        liveWidgetRepository.save(changed)
        mutableLiveWidgets.value = changed
    }

    /**
     * Live cards are native Compose state, not pixels the language model can inspect.
     * Attach a compact, read-only description to every model turn so SheV can answer
     * questions about what is currently on screen without pretending to use vision.
     */
    private fun liveWidgetContext(): String {
        val items = mutableLiveWidgets.value
        if (items.isEmpty()) return "\n\n[Состояние живых блоков: сейчас в чате нет активных блоков.]"
        fun position(widget: LiveWidget): String {
            val horizontal = when { widget.x < 0.22f -> "слева"; widget.x > 0.55f -> "справа"; else -> "по центру" }
            val vertical = when { widget.y < 0.22f -> "сверху"; widget.y > 0.52f -> "снизу"; else -> "посередине" }
            return "$vertical $horizontal"
        }
        val details = items.joinToString("; ") { widget ->
            val title = when (widget.type) {
                LiveWidgetType.CLOCK -> "часы (текущее локальное время и дата)"
                LiveWidgetType.WEATHER -> "погода (текущая температура, условия, ветер и влажность)"
                LiveWidgetType.BATTERY -> "заряд батареи (текущий процент заряда устройства)"
            }
            val extra = if (widget.type == LiveWidgetType.WEATHER) {
                val latest = LiveWeatherClient.latestDisplay
                if (widget.city.isBlank()) {
                    " (город не задан; карточка просит указать город)"
                } else {
                    " (город: ${widget.city}" + (latest?.let { "; сейчас на карточке: $it" } ?: "; актуальные данные ещё не загружены") + ")"
                }
            } else ""
            "$title — ${position(widget)}$extra"
        }
        return "\n\n[Состояние живых блоков в чате: $details. Это фактическое состояние интерфейса. Если пользователь спрашивает о блоках, отвечай по этому списку. Не утверждай, что видишь другие части экрана.]"
    }
    private fun sendLiveWidgetCommandReply(message: String, command: LiveWidgetCommand) {
        val before = mutableLiveWidgets.value
        val changed = LiveWidgetPolicy.apply(before, command)
        val exists = before.any { it.type == command.type }
        val success = changed != null && when (command.action) {
            LiveWidgetAction.SHOW -> changed.any { it.type == command.type }
            LiveWidgetAction.HIDE -> exists && changed.none { it.type == command.type }
            LiveWidgetAction.HIDE_ALL -> before.isNotEmpty() && changed.isEmpty()
            LiveWidgetAction.MOVE -> exists && (command.x != null || command.y != null)
            LiveWidgetAction.RESIZE -> exists && changed.firstOrNull { it.type == command.type }?.scale != before.firstOrNull { it.type == command.type }?.scale
        }
        if (changed != null && success) {
            liveWidgetRepository.save(changed)
            mutableLiveWidgets.value = changed
        }
        val name = when (command.type) {
            LiveWidgetType.CLOCK -> "часы"
            LiveWidgetType.WEATHER -> "погоду"
            LiveWidgetType.BATTERY -> "заряд батареи"
        }
        val answer = when {
            command.action == LiveWidgetAction.HIDE_ALL && before.isEmpty() -> "На экране уже нет живых блоков."
            changed == null -> "Не удалось изменить блок: проверь положение и лимит блоков."
            !success && command.action == LiveWidgetAction.HIDE -> "Блок «$name» не найден."
            !success -> "Не удалось выполнить команду с блоком «$name»."
            command.action == LiveWidgetAction.HIDE_ALL -> "Убрал все живые блоки с экрана."
            command.action == LiveWidgetAction.HIDE -> "Убрал блок «$name»."
            command.action == LiveWidgetAction.MOVE -> "Переместил блок «$name»."
            command.action == LiveWidgetAction.RESIZE -> "Изменил размер блока «$name»."
            command.type == LiveWidgetType.WEATHER && command.city.isBlank() -> "Добавил погоду. Укажи город, например: «Покажи погоду в Берлине»."
            command.type == LiveWidgetType.WEATHER -> "Добавил погоду для города ${command.city}."
            else -> "Добавил блок «$name» в чат."
        }
        mutableState.update { it.copy(prompt = "", error = null, messages = it.messages + "Вы: $message" + "SheV: $answer") }
    }

    fun send() {
        val message = mutableState.value.prompt.trim()
        if (message.isEmpty() || mutableState.value.busy) return

        val preferences = getApplication<Application>().getSharedPreferences(
            "shev_omniroute",
            Application.MODE_PRIVATE
        )
        LiveWidgetIntentRouter.parse(message, mutableLiveWidgets.value)?.let { command ->
            sendLiveWidgetCommandReply(message, command)
            return
        }
        if (LiveWidgetIntentRouter.needsDestinationClarification(message, mutableLiveWidgets.value)) {
            mutableState.update {
                it.copy(
                    prompt = "",
                    error = null,
                    messages = it.messages + "Вы: $message" +
                        "SheV: Куда переместить блок? Скажи, например: «наверх слева», «вниз справа» или «по центру»."
                )
            }
            return
        }
        if (LiveWidgetIntentRouter.needsTargetClarification(message, mutableLiveWidgets.value)) {
            val available = mutableLiveWidgets.value.joinToString(", ") { widget ->
                when (widget.type) {
                    LiveWidgetType.CLOCK -> "часы"
                    LiveWidgetType.WEATHER -> "погоду"
                    LiveWidgetType.BATTERY -> "заряд батареи"
                }
            }
            mutableState.update {
                it.copy(
                    prompt = "",
                    error = null,
                    messages = it.messages + "Вы: $message" +
                        "SheV: Уточни, какой блок изменить: $available. Или скажи «убери все блоки»."
                )
            }
            return
        }
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
        if (mutableState.value.useDeepSeek) {
            sendViaDeepSeek(message)
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
                    currentMessage = message + liveWidgetContext(),
                    rollingSummary = rollingSummary,
                    summaryThroughMessageId = summaryCheckpoint
                )
                // Retrieve only explicitly verified reusable skills. Drafts and unreviewed
                // model-generated instructions are never injected into the agent prompt.
                val verifiedSkillContext = skillLibrary.buildContext(message)
                val promptWithSkills = if (verifiedSkillContext.isBlank()) promptWithContext
                    else "$promptWithContext\n\n$verifiedSkillContext"
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = userMessageId,
                        role = ConversationContextBuilder.ROLE_USER,
                        content = LocalMessageCipher.encrypt(message),
                        status = ConversationContextBuilder.STATUS_PENDING,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                active.run(promptWithSkills).collect { event: Event ->
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
                    currentMessage = message + liveWidgetContext(),
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
                        val bridge = createOpenAiLocalToolBridge()
                        try {
                            OpenAiResponsesClient(config).completeWithTools(
                                history = input,
                                instructions = "Ты — SheV, личный помощник пользователя. Отвечай по-русски. Вызывай создание черновика поручения только по прямой просьбе пользователя. Перед созданием получи каталог доступных инструментов и приложи planJson строго по схеме {summary,steps:[{id,title,toolId,capability,input}]}, используя только разрешённые toolId и capability. План будет показан во вкладке «Поручения»; сохранение и подтверждение не запускают действия. Не заявляй о выполнении без отдельной проверки результата. Не раскрывай ключи и секреты.",
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
        if (CodexTaskDraftResponseParser.isExplicitTaskDraftRequest(message)) {
            sendViaCodexTaskDraft(message)
            return
        }
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
                        currentMessage = message + liveWidgetContext(),
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
                                if (eventTurnId == turnId) {
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
                                if (eventTurnId == turnId && item?.get("type")?.asString == "agentMessage" && answer.isEmpty()) {
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
    /**
     * Uses the authenticated ChatGPT/Codex account to propose a task plan, then validates and
     * saves it locally. Codex only plans in a separate read-only thread; the local executor runs
     * nothing until the user approves the saved plan in the task inbox.
     */
    private fun sendViaCodexTaskDraft(message: String) {
        mutableState.update {
            it.copy(
                prompt = "",
                busy = true,
                error = null,
                streamingMessage = null,
                messages = it.messages + "Вы: $message",
                codexStatus = "Готовлю план поручения…"
            )
        }
        responseJob = viewModelScope.launch(Dispatchers.IO) {
            val userMessageId = UUID.randomUUID().toString()
            try {
                val client = ensureCodexClient()
                val account = client.accountRead(true).get("account")
                    ?.takeIf { it.isJsonObject }?.asJsonObject
                require(account?.get("type")?.asString == "chatgpt") {
                    "Сначала подключи аккаунт ChatGPT в настройках Codex."
                }
                mutableState.update { it.copy(codexConnected = true, codexStatus = "Codex готов") }
                val preferences = getApplication<Application>()
                    .getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
                val modelId = preferences.getString("model_id", mutableState.value.codexModelId).orEmpty()
                require(modelId.isNotBlank()) { "Не выбрана модель ChatGPT/Codex." }

                val history = dao.getRecentCompletedConversationMessages(CONTEXT_HISTORY_LIMIT).asReversed()
                    .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                val summaryRead = conversationStateRepository.readSummary()
                val conversationContext = ConversationContextBuilder.build(
                    chronologicalHistory = history,
                    currentMessage = message + liveWidgetContext(),
                    rollingSummary = (summaryRead as? ConversationSummaryRead.Ready)?.plaintext,
                    summaryThroughMessageId = (summaryRead as? ConversationSummaryRead.Ready)?.throughMessageId
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

                createAgentToolHost()
                val registry = checkNotNull(agentToolRegistry) { "Реестр локальных инструментов не создан." }
                val blocked = setOf(
                    SheVAgentAdkTools.TOOL_ID,
                            SheVAgentAdkTools.CREATE_TASK_DRAFT_ID,
                    SheVAgentAdkTools.SAVE_SKILL_DRAFT_TOOL_ID
                )
                val allowedPlanToolIds = registry.list().mapTo(linkedSetOf()) { it.id } - blocked
                require(allowedPlanToolIds.isNotEmpty()) { "Нет доступных локальных инструментов для поручений." }
                val toolCatalog = registry.list().filter { it.id in allowedPlanToolIds }.joinToString("\n") { tool ->
                    "${tool.id} | capabilities: ${tool.capabilities.joinToString { it.name }} | " +
                        "${tool.title}: ${tool.description} | input: ${tool.inputSchema?.describe() ?: "schema unavailable"}"
                }

                val developerInstructions =
                    "Ты — планировщик поручений внутри SheV Agent. Всегда отвечай по-русски. " +
                    "Ты не исполнитель: не запускай инструменты, web search, команды, код или изменения файлов. " +
                    "Используй только каталог локальных инструментов, который будет передан в сообщении. " +
                    "Верни строго один JSON-объект без Markdown и пояснений: " +
                    "{\"title\":\"краткое название\",\"plan\":{\"summary\":\"цель\",\"steps\":[{\"id\":\"step-1\",\"title\":\"действие\",\"toolId\":\"точный id\",\"capability\":\"точное имя capability\",\"input\":\"JSON-строка аргументов\"}]}}. " +
                    "Используй только инструменты и поля input из переданного каталога. " +
                    "План должен содержать от 1 до 8 шагов. Никаких других ключей."
                val threadId = client.startThread(modelId, developerInstructions)
                val basePrompt = buildString {
                    append("Подготовь черновик поручения по явной просьбе пользователя.\n")
                    append("Контекст диалога:\n").append(conversationContext).append("\n\n")
                    append("Доступные локальные инструменты и точные схемы аргументов:\n")
                    append(toolCatalog).append("\n\n")
                    append("Сформируй конкретный безопасный план. Не включай инструменты, которых нет в каталоге. ")
                    append("Если просьба не может быть реализована перечисленными инструментами, верни тот же JSON-формат ")
                    append("с пустым массивом steps; не придумывай новые инструменты и не маскируй отсутствие возможности.")
                }

                val validator = AgentPlanValidator(registry)
                var parsed: ParsedCodexTaskDraft? = null
                var validationMessage = "Codex не вернул корректный JSON-план."
                for (attempt in 0..1) {
                    val prompt = if (attempt == 0) basePrompt else {
                        basePrompt + "\n\nПредыдущий ответ отклонён валидатором: " +
                            validationMessage + ". Исправь JSON и верни только объект по заданной схеме."
                    }
                    val raw = runCodexTaskPlanTurn(client, threadId, prompt)
                    val candidate = CodexTaskDraftResponseParser.parse(raw)
                    if (candidate == null) {
                        validationMessage = "Ответ не соответствует JSON-схеме title + plan."
                        continue
                    }
                    val plan = AgentPlanJsonCodec.decode(candidate.planJson)
                    if (plan == null) {
                        validationMessage = "Структура плана некорректна."
                        continue
                    }
                    val validation = validator.validate(plan)
                    val disallowed = plan.steps.firstOrNull { it.toolId !in allowedPlanToolIds }
                    if (!validation.accepted) {
                        validationMessage = "Шаг ${validation.stepId ?: "?"}: ${validation.rejection}."
                        continue
                    }
                    if (disallowed != null) {
                        validationMessage = "Инструмент ${disallowed.toolId} отсутствует в разрешённом каталоге."
                        continue
                    }
                    parsed = candidate
                    break
                }
                val draft = parsed ?: throw IllegalStateException(
                    "Не удалось подготовить безопасный план: $validationMessage"
                )
                val plan = AgentPlanJsonCodec.decode(draft.planJson)
                    ?: throw IllegalStateException("План не прошёл повторную проверку.")
                val canonicalPlan = AgentPlanJsonCodec.canonicalJson(plan)
                val planHash = AgentPlanJsonCodec.sha256(canonicalPlan)
                val now = System.currentTimeMillis()
                val bucket = now / 60_000L
                val taskId = UUID.nameUUIDFromBytes(
                    "shev-codex-task-v1:$bucket:${draft.title}:$planHash".toByteArray()
                ).toString()
                val draftResult = taskRepository.createDraft(
                    AgentTaskDraftRequest(
                        taskId = taskId,
                        title = draft.title,
                        eventId = UUID.randomUUID().toString(),
                        idempotencyKey = "codex-task-draft:$taskId",
                        occurredAtEpochMillis = now,
                        planJson = canonicalPlan
                    )
                )
                val assistantText = when (draftResult) {
                    is AgentTaskDraftResult.Created ->
                        "Черновик поручения «${draftResult.state.task.title}» сохранён. План из ${plan.steps.size} шагов находится во вкладке «Поручения». Проверь шаги и параметры, затем подтверди план. Ничего не запускалось."
                    is AgentTaskDraftResult.Replayed ->
                        "Этот черновик поручения уже сохранён во вкладке «Поручения». Проверь ранее сохранённый план. Ничего не запускалось."
                    AgentTaskDraftResult.InvalidRequest ->
                        throw IllegalStateException("Локальный репозиторий отклонил черновик поручения.")
                    AgentTaskDraftResult.Conflict ->
                        throw IllegalStateException("Обнаружен конфликт повторного сохранения поручения.")
                    AgentTaskDraftResult.NotFound ->
                        throw IllegalStateException("Не удалось найти сохранённое поручение.")
                }
                dao.appendConversationMessage(
                    ConversationMessageEntity(
                        id = UUID.randomUUID().toString(),
                        role = ConversationContextBuilder.ROLE_ASSISTANT,
                        content = LocalMessageCipher.encrypt(assistantText),
                        status = ConversationContextBuilder.STATUS_COMPLETE,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_COMPLETE)
                mutableState.update {
                    it.copy(
                        messages = it.messages + "SheV: $assistantText",
                        streamingMessage = null,
                        codexApproval = null,
                        codexStatus = "Codex готов",
                        codexConnected = true,
                        taskExecutionMessage = "План подготовлен ChatGPT и сохранён локально. Исполнение ожидает отдельного подтверждения."
                    )
                }
            } catch (cancelled: CancellationException) {
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_FAILED)
                throw cancelled
            } catch (failure: Exception) {
                dao.updateConversationMessageStatus(userMessageId, ConversationContextBuilder.STATUS_FAILED)
                mutableState.update {
                    it.copy(
                        error = failure.message ?: failure.javaClass.simpleName + ": не удалось подготовить поручение.",
                        streamingMessage = null,
                        codexApproval = null,
                        codexStatus = if (it.codexConnected) "Codex готов" else "Codex не подключён"
                    )
                }
            } finally {
                mutableState.update { it.copy(busy = false, streamingMessage = null) }
            }
        }
    }

    private suspend fun runCodexTaskPlanTurn(
        client: CodexAppServerClient,
        threadId: String,
        prompt: String
    ): String = coroutineScope {
        val started = client.startTurn(threadId, prompt)
        val turnId = started.getAsJsonObject("turn")?.get("id")?.asString
            ?: throw IllegalStateException("Codex не вернул идентификатор хода планирования.")
        val previousThreadId = codexActiveThreadId
        val previousTurnId = codexActiveTurnId
        codexActiveThreadId = threadId
        codexActiveTurnId = turnId
        val answer = StringBuilder()
        val completed = CompletableDeferred<JsonObject>()
        suspend fun abortPlanningTurn(reason: String) {
            if (completed.isCompleted) return
            withContext(NonCancellable) {
                try { client.interrupt(threadId, turnId) } catch (_: Exception) { }
            }
            completed.completeExceptionally(IllegalStateException(reason))
        }
        val collector = launch(Dispatchers.IO) {
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
                        if (eventTurnId == turnId) {
                            answer.append(params.get("delta")?.asString.orEmpty())
                        }
                    }
                    "item/started" -> {
                        val itemType = params.getAsJsonObject("item")?.get("type")?.asString
                        if (eventTurnId == turnId && itemType in setOf("commandExecution", "fileChange", "webSearch", "mcpToolCall", "toolCall")) {
                            abortPlanningTurn(
                                "Планировщик попытался запустить команду или изменить файлы. Запрос остановлен."
                            )
                        }
                    }
                    "item/completed" -> {
                        val item = params.getAsJsonObject("item")
                        val itemType = item?.get("type")?.asString
                        if (eventTurnId == turnId && itemType in setOf("commandExecution", "fileChange")) {
                            abortPlanningTurn(
                                "Планировщик попытался выполнить команду или изменить файлы. Запрос остановлен."
                            )
                        } else if (eventTurnId == turnId && itemType == "agentMessage" && answer.isEmpty()) {
                            val text = item.getAsJsonArray("content")?.mapNotNull { part ->
                                part.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString
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
                        abortPlanningTurn(
                            "Планировщик попытался запросить выполнение команды или изменение файлов. Запрос остановлен."
                        )
                    }
                }
            }
        }
        try {
            val completion = withTimeout(180_000L) { completed.await() }
            val turn = completion.get("turn")?.takeIf { it.isJsonObject }?.asJsonObject
            val status = turn?.get("status")?.asString.orEmpty()
            if (status != "completed") {
                val error = turn?.get("error")?.let { value ->
                    if (value.isJsonObject) value.asJsonObject.get("message")?.asString else null
                }
                throw IllegalStateException(error ?: "Codex завершил планирование со статусом $status.")
            }
            answer.toString().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Codex не вернул JSON-план.")
        } catch (timeout: TimeoutCancellationException) {
            withContext(NonCancellable) {
                try { client.interrupt(threadId, turnId) } catch (_: Exception) { }
            }
            throw IllegalStateException("Codex не подготовил план за три минуты.", timeout)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try { client.interrupt(threadId, turnId) } catch (_: Exception) { }
            }
            throw cancelled
        } finally {
            collector.cancel()
            codexActiveThreadId = previousThreadId
            codexActiveTurnId = previousTurnId
        }
    }

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
                    currentMessage = message + liveWidgetContext(),
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
                    currentMessage = message + liveWidgetContext(),
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

    /** Direct DeepSeek API route using the documented OpenAI-compatible Chat Completions endpoint. */
    private fun sendViaDeepSeek(message: String) {
        mutableState.update {
            it.copy(prompt = "", busy = true, error = null, messages = it.messages + "Вы: $message")
        }
        responseJob = viewModelScope.launch(Dispatchers.IO) {
            val userMessageId = UUID.randomUUID().toString()
            try {
                val preferences = getApplication<Application>().getSharedPreferences(
                    "shev_deepseek",
                    Application.MODE_PRIVATE
                )
                val modelId = preferences.getString("model_id", "deepseek-flash") ?: "deepseek-flash"
                val encryptedToken = preferences.getString("encrypted_token", null)
                    ?: throw IllegalStateException("Сначала сохрани API-ключ DeepSeek в настройках.")
                val token = DeepSeekSecretCipher.decrypt(encryptedToken)
                require(token.isNotBlank()) { "Сохранённый ключ DeepSeek пустой. Введи его заново." }

                val history = dao.getRecentCompletedConversationMessages(CONTEXT_HISTORY_LIMIT).asReversed()
                    .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                val summaryRead = conversationStateRepository.readSummary()
                val rollingSummary = (summaryRead as? ConversationSummaryRead.Ready)?.plaintext
                val summaryCheckpoint = (summaryRead as? ConversationSummaryRead.Ready)?.throughMessageId
                val promptWithContext = ConversationContextBuilder.build(
                    chronologicalHistory = history,
                    currentMessage = message + liveWidgetContext(),
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
                        "https://api.deepseek.com",
                        modelId,
                        token,
                        "DeepSeek",
                        appendV1IfMissing = false
                    )
                ).complete(
                    promptWithContext,
                    systemInstruction = "Ты — SheV, личный AI-помощник пользователя. Всегда отвечай по-русски. Это удалённый режим DeepSeek: у тебя нет прямого доступа к телефону, локальным файлам или действиям приложения. Не утверждай, что выполнил действие. Не раскрывай API-ключи и секреты."
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
                    it.copy(error = failure.message ?: failure.javaClass.simpleName + ": ошибка DeepSeek API.")
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
                val modelId = preferences.getString("model_id", "gpt-6.1-sol")
                    ?: "gpt-6.1-sol"
                val chatGptPlanAuth = ChatGptPlanOAuth(getApplication(), preferences)
                val planSession = runCatching { chatGptPlanAuth.load() }.getOrNull()
                val token = if (planSession != null) {
                    chatGptPlanAuth.validAccessToken()
                } else {
                    val encryptedToken = preferences.getString("encrypted_token", null)
                        ?: throw IllegalStateException("Подключи ChatGPT кнопкой «Войти через ChatGPT» — API-ключ не требуется.")
                    OpenAISecretCipher.decrypt(encryptedToken)
                }
                require(token.isNotBlank()) { "Авторизация OpenAI пуста. Подключи ChatGPT заново." }

                val history = dao.getRecentCompletedConversationMessages(CONTEXT_HISTORY_LIMIT).asReversed()
                    .map { it.copy(content = LocalMessageCipher.decrypt(it.content)) }
                val summaryRead = conversationStateRepository.readSummary()
                val rollingSummary = (summaryRead as? ConversationSummaryRead.Ready)?.plaintext
                val summaryCheckpoint = (summaryRead as? ConversationSummaryRead.Ready)?.throughMessageId
                val promptWithContext = ConversationContextBuilder.build(
                    chronologicalHistory = history,
                    currentMessage = message + liveWidgetContext(),
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
                val localTools = createOpenAiLocalToolBridge()
                val answer = OpenAiResponsesClient(
                    OpenAiCompatibleConfig("https://api.openai.com/v1", modelId, token),
                    chatGptPlanUsage = planSession != null
                ).completeWithTools(
                    history = responseInput,
                    instructions = "Ты — SheV, персональный AI-помощник пользователя. Всегда отвечай по-русски. Ты работаешь внутри Android-приложения SheV Agent. Перед созданием поручения по прямой просьбе пользователя вызови inspect_available_agent_tools и сформируй planJson строго по схеме {summary,steps:[{id,title,toolId,capability,input}]}; используй только доступные идентификаторы и возможности, а input должен соответствовать описанию инструмента. План показывается во вкладке «Поручения» и ожидает подтверждения; подтверждение плана само по себе не запускает действия. Не утверждай, что задача выполнена без независимой проверки результата. Не придумывай результаты инструментов и не раскрывай API-ключи. Все вызовы проверяются приложением.",
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
    /** Builds the real tool registry independently of the optional on-device LLM runtime. */
    private fun createAgentToolHost(): SheVAgentAdkTools {
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
                        listOf(
                            ToolInputField("title", ToolInputType.STRING, required = true, maxLength = 2_000),
                            ToolInputField("planJson", ToolInputType.STRING, required = true, maxLength = AgentPlanJsonCodec.MAX_PLAN_JSON_CHARS)
                        )
                    )
                )
            )
            register(
                AgentToolDescriptor(
                    id = SheVAgentAdkTools.SAVE_SKILL_DRAFT_TOOL_ID,
                    title = "Сохранить черновик навыка",
                    description = "Сохраняет повторно используемую инструкцию в библиотеку SheV; не активирует её",
                    capabilities = setOf(AgentCapability.CREATE_DRAFT),
                    inputSchema = ToolInputSchema(
                        listOf(
                            ToolInputField("title", ToolInputType.STRING, required = true, maxLength = 120),
                            ToolInputField("summary", ToolInputType.STRING, required = true, maxLength = 1_000),
                            ToolInputField("instructions", ToolInputType.STRING, required = true, maxLength = 8_000),
                            ToolInputField("keywords", ToolInputType.STRING, required = false, maxLength = 1_000),
                            ToolInputField("sourceUrls", ToolInputType.STRING, required = false, maxLength = 4_000)
                        )
                    )
                )
            )
        }
        val planValidator = AgentPlanValidator(registry)
        val allowedPlanToolIds = registry.list().mapTo(linkedSetOf()) { it.id } - setOf(
            SheVAgentAdkTools.TOOL_ID,
            SheVAgentAdkTools.CREATE_TASK_DRAFT_ID,
            SheVAgentAdkTools.SAVE_SKILL_DRAFT_TOOL_ID
        )
        val taskDraftHandler = SuspendingAgentTool { input ->
            val payload = runCatching { JsonParser.parseString(input).asJsonObject }.getOrNull()
            val title = payload?.get("title")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val rawPlan = payload?.get("planJson")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val plan = rawPlan?.let(AgentPlanJsonCodec::decode)
            val validation = plan?.let(planValidator::validate)
            if (title.isNullOrBlank()) {
                AgentToolResult(false, "Пустое или некорректное поручение.", "INVALID_TITLE")
            } else if (plan == null || validation?.accepted != true || plan.steps.any { it.toolId !in allowedPlanToolIds }) {
                AgentToolResult(false, "План не сохранён: используй только инструменты из каталога и корректные параметры.", "INVALID_PLAN")
            } else {
                val now = System.currentTimeMillis()
                val canonicalPlan = AgentPlanJsonCodec.canonicalJson(plan)
                val planHash = AgentPlanJsonCodec.sha256(canonicalPlan)
                // Retries of the exact same plan are idempotent; changed plans get a new draft.
                val requestBucket = now / 60_000L
                val requestSeed = "shev-task-draft-v2:$requestBucket:$title:$planHash"
                val taskId = UUID.nameUUIDFromBytes(requestSeed.toByteArray()).toString()
                val eventId = UUID.nameUUIDFromBytes("event:$taskId".toByteArray()).toString()
                when (val result = taskRepository.createDraft(
                    AgentTaskDraftRequest(
                        taskId = taskId,
                        title = title,
                        eventId = eventId,
                        idempotencyKey = "task-create:$taskId",
                        occurredAtEpochMillis = now,
                        planJson = canonicalPlan
                    )
                )) {
                    is AgentTaskDraftResult.Created -> AgentToolResult(
                        true,
                        "Черновик поручения «${result.state.task.title}» сохранён. План доступен во вкладке «Поручения» для проверки и подтверждения. Никакое действие не запускалось."
                    )
                    is AgentTaskDraftResult.Replayed -> AgentToolResult(
                        true,
                        "Это поручение уже сохранено во вкладке «Поручения». Повторная запись не создана."
                    )
                    AgentTaskDraftResult.InvalidRequest -> AgentToolResult(false, "Поручение не сохранено: некорректный запрос.", "INVALID_REQUEST")
                    AgentTaskDraftResult.Conflict -> AgentToolResult(false, "Поручение не сохранено: обнаружен конфликт записи.", "CONFLICT")
                    AgentTaskDraftResult.NotFound -> AgentToolResult(false, "Поручение не найдено после записи.", "NOT_FOUND")
                }
            }
        }
        val skillDraftHandler = SuspendingAgentTool { input ->
            val payload = runCatching { JsonParser.parseString(input).asJsonObject }.getOrNull()
            val title = payload?.get("title")?.takeIf { it.isJsonPrimitive }?.asString
            val summary = payload?.get("summary")?.takeIf { it.isJsonPrimitive }?.asString
            val instructions = payload?.get("instructions")?.takeIf { it.isJsonPrimitive }?.asString
            if (title.isNullOrBlank() || summary.isNullOrBlank() || instructions.isNullOrBlank()) {
                AgentToolResult(false, "Для навыка нужны название, назначение и инструкция.", "INVALID_SKILL")
            } else {
                try {
                    val skillId = "skill-" + UUID.nameUUIDFromBytes(
                        title.trim().lowercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8)
                    ).toString()
                    val saved = skillLibrary.saveDraft(
                        skillId = skillId,
                        title = title,
                        summary = summary,
                        instructions = instructions,
                        keywords = payload.get("keywords")?.asString.orEmpty().split(',').map(String::trim).filter(String::isNotBlank),
                        sourceUrls = payload.get("sourceUrls")?.asString.orEmpty().lines().map(String::trim).filter(String::isNotBlank),
                        verificationNotes = "Черновик создан главным агентом; ожидает проверки пользователя."
                    )
                    AgentToolResult(true, "Черновик навыка «${saved.title}» v${saved.version} сохранён. Он не активирован и не будет влиять на ответы до проверки пользователем.")
                } catch (failure: IllegalArgumentException) {
                    AgentToolResult(false, failure.message ?: "Навык не прошёл проверку.", "INVALID_SKILL")
                } catch (failure: Exception) {
                    AgentToolResult(false, failure.message ?: "Не удалось сохранить навык.", "SKILL_SAVE_FAILED")
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
                    AgentToolResult(true, registry.list().joinToString("\n") { tool -> "${tool.id} | ${tool.title} | ${tool.capabilities.joinToString { it.name }} | ${tool.description} | input: ${tool.inputSchema?.describe() ?: "schema unavailable"}" })
                },
                SheVAgentAdkTools.CURRENT_TIME_TOOL_ID to currentTimeHandler
            ),
            suspendingHandlers = mapOf(
                SheVAgentAdkTools.CREATE_TASK_DRAFT_ID to taskDraftHandler,
                SheVAgentAdkTools.SAVE_SKILL_DRAFT_TOOL_ID to skillDraftHandler,
                SheVAgentAdkTools.WEATHER_TOOL_ID to SuspendingAgentTool { input ->
                    val city = runCatching {
                        JsonParser.parseString(input).asJsonObject.get("city")?.asString
                    }.getOrNull()
                    if (city.isNullOrBlank()) {
                        AgentToolResult(false, "Укажи город.", "CITY_REQUIRED")
                    } else {
                        try {
                            AgentToolResult(
                                true,
                                LiveWeatherClient.current(city)
                            )
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
        val executionContext = AgentToolExecutionContext(
            grantedCapabilities = setOf(
                AgentCapability.ANALYZE_DATA,
                AgentCapability.CREATE_DRAFT,
                AgentCapability.READ_REMOTE_DATA
            ),
            explicitlyApprovedCapabilities = setOf(AgentCapability.READ_REMOTE_DATA)
        )
        val tools = SheVAgentAdkTools(
            executor,
            executionContext,
            skillLibrary = skillLibrary
        )
        toolExecutor = executor
        agentToolRegistry = registry
        agentExecutionContext = executionContext
        return tools
    }

    private fun createOpenAiLocalToolBridge(): OpenAiLocalToolBridge {
        if (toolExecutor == null || agentToolRegistry == null) createAgentToolHost()
        val registry = checkNotNull(agentToolRegistry) { "Реестр инструментов SheV не создан." }
        val blocked = setOf(
            SheVAgentAdkTools.TOOL_ID,
            SheVAgentAdkTools.CREATE_TASK_DRAFT_ID,
            SheVAgentAdkTools.SAVE_SKILL_DRAFT_TOOL_ID
        )
        val allowed = registry.list().mapTo(linkedSetOf()) { it.id } - blocked
        return OpenAiLocalToolBridge(
            taskRepository = taskRepository,
            planValidator = AgentPlanValidator(registry),
            planToolDescriptors = registry.list(),
            allowedPlanToolIds = allowed
        )
    }

    /** Allows ChatGPT/Codex to inspect the same host tools without downloading a local model. */
    fun prepareAgentToolCatalog() {
        if (toolExecutor == null) createAgentToolHost()
    }

    fun availableAgentTools(): List<AgentToolDescriptor> = toolExecutor?.availableTools().orEmpty()

    fun availableAgentToolIds(): Set<String> =
        availableAgentTools().mapTo(linkedSetOf()) { it.id }

    fun observeTaskSteps(taskId: String) = dao.observeTaskSteps(taskId)

    fun startTaskExecution(task: AgentTaskEntity): Job {
        taskExecutionJobs[task.id]?.takeIf { it.isActive }?.let { return it }
        if (toolExecutor == null || agentToolRegistry == null || agentExecutionContext == null) {
            createAgentToolHost()
        }
        val executor = checkNotNull(toolExecutor)
        val registry = checkNotNull(agentToolRegistry)
        val context = checkNotNull(agentExecutionContext)
        val blocked = setOf(
            SheVAgentAdkTools.TOOL_ID,
            SheVAgentAdkTools.CREATE_TASK_DRAFT_ID,
            SheVAgentAdkTools.SAVE_SKILL_DRAFT_TOOL_ID
        )
        val allowed = registry.list().mapTo(linkedSetOf()) { it.id } - blocked
        val coordinator = AgentTaskExecutionCoordinator(
            dao = dao,
            taskRepository = taskRepository,
            validator = AgentPlanValidator(registry),
            executor = executor,
            agentAllowedToolIds = allowed,
            executionContext = context
        )
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val outcome = coordinator.execute(task.id, task.revision)
                mutableState.update { it.copy(taskExecutionMessage = outcome.message) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                taskExecutionJobs.remove(task.id)
            }
        }
        taskExecutionJobs[task.id] = job
        job.start()
        return job
    }

    private suspend fun recoverInterruptedAgentTasks() {
        val interrupted = dao.getTasksByPhase(AgentPhase.RUNNING.name)
        for (task in interrupted) {
            try {
                dao.markRunningTaskStepsInterrupted(
                    taskId = task.id,
                    planHash = task.planHash,
                    finishedAtEpochMillis = System.currentTimeMillis()
                )
                taskRepository.transition(
                    AgentTaskTransitionRequest(
                        taskId = task.id,
                        command = AgentWorkflowCommand.PAUSE,
                        expectedRevision = task.revision,
                        eventId = UUID.randomUUID().toString(),
                        idempotencyKey = "recover-pause:${task.id}:${task.revision}",
                        occurredAtEpochMillis = System.currentTimeMillis()
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the durable RUNNING state visible if recovery cannot be committed.
            }
        }
    }

    /** Approval is created only by the explicit action in the task inbox UI. */
    suspend fun approveTaskFromUser(task: AgentTaskEntity): AgentTaskRepositoryResult =
        taskRepository.approveFromUser(
            taskId = task.id,
            expectedRevision = task.revision,
            eventId = UUID.randomUUID().toString(),
            idempotencyKey = "user-approve:${task.id}:${task.revision}",
            occurredAtEpochMillis = System.currentTimeMillis()
        )

    /** Declines a waiting draft or cancels an already approved/running task. */
    suspend fun cancelTaskFromUser(task: AgentTaskEntity): AgentTaskRepositoryResult {
        val result = taskRepository.transition(
            AgentTaskTransitionRequest(
                taskId = task.id,
                command = AgentWorkflowCommand.CANCEL,
                expectedRevision = task.revision,
                eventId = UUID.randomUUID().toString(),
                idempotencyKey = "user-cancel:${task.id}:${task.revision}",
                occurredAtEpochMillis = System.currentTimeMillis()
            )
        )
        if (result is AgentTaskRepositoryResult.Applied || result is AgentTaskRepositoryResult.Replayed) {
            taskExecutionJobs[task.id]?.cancel()
        }
        return result
    }

    /** Completes a VERIFYING task only after an explicit user review of all saved step results. */
    suspend fun completeTaskAfterUserReview(task: AgentTaskEntity): AgentTaskRepositoryResult =
        taskRepository.completeFromUserReview(
            taskId = task.id,
            expectedRevision = task.revision,
            eventId = UUID.randomUUID().toString(),
            idempotencyKey = "user-complete-review:${task.id}:${task.revision}",
            occurredAtEpochMillis = System.currentTimeMillis()
        )

    /** Runs an explicitly approved specialist with only its manifest-scoped tools. */
    suspend fun runApprovedSpecialist(manifestJson: String, input: String): String {
        require(input.isNotBlank()) { "Опиши задачу для специалиста." }
        val manifest = AgentManifestParser.parse(manifestJson)
        val decision = AgentRunPolicy.evaluate(
            manifest,
            availableAgentTools().associate { it.id to it.capabilities }
        )
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
    /** Generates an agent manifest through the authenticated ChatGPT/Codex account. */
    suspend fun generateAgentCreatorOutputFromCodex(creatorPrompt: String): String {
        require(creatorPrompt.isNotBlank()) { "Пустой запрос конструктора." }
        check(!mutableState.value.busy) { "Модель уже выполняет другую задачу." }
        check(mutableState.value.codexConnected) { "Сначала подключи ChatGPT в настройках Codex." }
        mutableState.update { it.copy(busy = true, error = null, codexStatus = "ChatGPT проектирует специалиста…") }
        try {
            return withContext(Dispatchers.IO) {
                val client = ensureCodexClient()
                val account = client.accountRead(true).get("account")?.takeIf { it.isJsonObject }?.asJsonObject
                require(account?.get("type")?.asString == "chatgpt") { "Сессия ChatGPT не подтверждена. Переподключи Codex." }
                val preferences = getApplication<Application>().getSharedPreferences("shev_codex", Application.MODE_PRIVATE)
                val modelId = preferences.getString("model_id", mutableState.value.codexModelId).orEmpty()
                require(modelId.isNotBlank()) { "Не выбрана доступная модель ChatGPT/Codex." }
                val threadId = client.startThread(modelId)
                val started = client.startTurn(threadId, buildString {
                    appendLine("Ты — конструктор специалистов SheV Agent. Подготовь паспорт агента строго по заданному контракту.")
                    appendLine("Верни только полный JSON-объект без Markdown и пояснений. Не вызывай инструменты и не выполняй команды.")
                    appendLine("Все предложения и разрешения должны быть минимальными и соответствовать задаче.")
                    appendLine()
                    append(creatorPrompt)
                })
                val turnId = started.getAsJsonObject("turn")?.get("id")?.asString
                    ?: throw IllegalStateException("Codex не вернул идентификатор генерации.")
                val answer = StringBuilder()
                val completed = CompletableDeferred<JsonObject>()
                kotlinx.coroutines.coroutineScope {
                    val collector = launch(Dispatchers.IO) {
                        client.events.collect { event ->
                            val methodName = event.get("method")?.asString.orEmpty()
                            val params = event.getAsJsonObject("params") ?: JsonObject()
                            val eventThreadId = params.get("threadId")?.asString
                            val eventTurnId = params.get("turnId")?.asString ?: params.getAsJsonObject("turn")?.get("id")?.asString
                            if (eventThreadId != null && eventThreadId != threadId) return@collect
                            if (eventTurnId != null && eventTurnId != turnId) return@collect
                            when (methodName) {
                                "item/agentMessage/delta" -> answer.append(params.get("delta")?.asString.orEmpty())
                                "item/completed" -> {
                                    val item = params.getAsJsonObject("item")
                                    if (item?.get("type")?.asString == "agentMessage" && answer.isEmpty()) {
                                        item.getAsJsonArray("content")?.forEach { part ->
                                            if (part.isJsonObject) answer.append(part.asJsonObject.get("text")?.asString.orEmpty())
                                        }
                                    }
                                }
                                "turn/completed" -> if (eventTurnId == turnId || params.getAsJsonObject("turn")?.get("id")?.asString == turnId) completed.complete(params)
                                "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                                    val requestId = runCatching { event.get("id")?.asLong }.getOrNull()
                                    if (requestId != null) client.respondToServerRequest(requestId, JsonObject().apply { addProperty("decision", "decline") })
                                }
                            }
                        }
                    }
                    try {
                        val completion = withTimeout(180_000L) { completed.await() }
                        val turn = completion.get("turn")?.takeIf { it.isJsonObject }?.asJsonObject
                        val status = turn?.get("status")?.asString.orEmpty()
                        if (status.isNotBlank() && status != "completed") {
                            throw IllegalStateException(turn?.getAsJsonObject("error")?.get("message")?.asString ?: "Codex завершил генерацию со статусом $status.")
                        }
                    } finally { collector.cancel() }
                }
                answer.toString().trim().also { if (it.isBlank()) throw IllegalStateException("ChatGPT/Codex не вернул паспорт специалиста.") }
            }
        } finally {
            mutableState.update { it.copy(busy = false, codexStatus = if (it.codexConnected) "ChatGPT подключён" else it.codexStatus) }
        }
    }

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
