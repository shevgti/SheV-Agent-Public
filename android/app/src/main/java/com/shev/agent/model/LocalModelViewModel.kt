package com.shev.agent.model

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.adk.kt.events.Event
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
import com.shev.agent.tools.AgentTool
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalModelUiState(
    val verified: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val ready: Boolean = false,
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
    private var downloadJob: Job? = null

    init {
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
                val executor = AgentToolExecutor(
                    registry,
                    mapOf(SheVAgentAdkTools.TOOL_ID to AgentTool {
                        AgentToolResult(true, registry.list().joinToString { it.id })
                    }),
                    suspendingHandlers = mapOf(
                        SheVAgentAdkTools.CREATE_TASK_DRAFT_ID to taskDraftHandler
                    )
                )
                val tools = SheVAgentAdkTools(
                    executor,
                    AgentToolExecutionContext(
                        setOf(AgentCapability.ANALYZE_DATA, AgentCapability.CREATE_DRAFT)
                    )
                )
                runtime = SheVAgentRuntime.load(getApplication(), store, tools)
                mutableState.update { it.copy(ready = true, backendName = runtime?.backendName) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.update {
                    it.copy(error = failure.message ?: failure.javaClass.simpleName + ": не удалось запустить модель.")
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
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
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
                ) + "\\n\\nСистемное ограничение: ты отвечаешь как SheV. У тебя нет прямого доступа к локальным инструментам и действиям телефона в этом удалённом режиме. Не утверждай, что выполнил локальное действие. Для выполнения используй только функции, явно предоставленные приложением; если их нет, объясни ограничение."

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
                    OpenAiCompatibleConfig(baseUrl, modelId, token)
                ).complete(promptWithContext)
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

    private fun formatMessage(message: ConversationMessageEntity): String {
        val speaker = if (message.role == ConversationContextBuilder.ROLE_USER) "Вы" else "SheV"
        val suffix = if (message.status != ConversationContextBuilder.STATUS_COMPLETE) " (не завершено)" else ""
        return "$speaker: ${message.content}$suffix"
    }

    override fun onCleared() {
        val active = runtime
        runtime = null
        if (active != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try { active.close() } catch (_: Throwable) { /* best-effort native cleanup */ }
            }
        }
        super.onCleared()
    }

    private companion object {
        const val HISTORY_DISPLAY_LIMIT = 80
        const val CONTEXT_HISTORY_LIMIT = 12
    }
}
