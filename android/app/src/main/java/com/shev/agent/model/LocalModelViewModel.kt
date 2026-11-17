package com.shev.agent.model

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.adk.kt.events.Event
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.shev.agent.AgentCapability
import com.shev.agent.AgentPhase
import com.shev.agent.AgentWorkflowCommand
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.PlanStep
import com.shev.agent.core.ToolRisk
import com.shev.agent.data.ConversationMessageEntity
import com.shev.agent.data.ConversationStateRepository
import com.shev.agent.data.ConversationSummaryRead
import com.shev.agent.data.AgentTaskDraftRequest
import com.shev.agent.data.AgentTaskDraftResult
import com.shev.agent.data.AgentTaskEntity
import com.shev.agent.data.AgentTaskRepositoryResult
import com.shev.agent.data.AgentTaskRepositoryRejection
import com.shev.agent.data.AgentTaskTransitionRequest
import com.shev.agent.data.PersistedAgentPlan
import com.shev.agent.data.AgentPlanSaveResult
import com.shev.agent.data.McpInvocationApprovalRequestEntity
import com.shev.agent.data.McpInvocationApprovalResult
import com.shev.agent.data.RoomMcpInvocationApprovalRepository
import com.shev.agent.data.RoomAgentPlanRepository
import com.shev.agent.data.RoomAgentPlanExecutionRepository
import com.shev.agent.data.RoomAgentTaskAuthorizationVerifier
import com.shev.agent.data.RoomAgentTaskRepository
import com.shev.agent.data.LocalMessageCipher
import com.shev.agent.data.SheVAgentDatabaseProvider
import com.shev.agent.tools.AgentPlanToolValidator
import com.shev.agent.tools.AgentPlanExecutor
import com.shev.agent.tools.PlanExecutionAttemptStatus
import com.shev.agent.data.StartPlanExecutionResult
import com.shev.agent.tools.AgentTool
import com.shev.agent.tools.LocalTimeTool
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingTaskApproval(
    val task: AgentTaskEntity,
    val plan: PersistedAgentPlan?
)

data class PendingMcpInvocationApproval(
    val request: McpInvocationApprovalRequestEntity,
    val toolDefinitionJson: String?,
    val argumentsJson: String?
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
    val backendName: String? = null,
    val deviceSummary: String = "Проверяю характеристики устройства…",
    val prompt: String = "",
    val messages: List<String> = emptyList(),
    val pendingTaskApprovals: List<PendingTaskApproval> = emptyList(),
    val taskActionBusyId: String? = null,
    val taskActionMessage: String? = null,
    val planExecutionMessage: String? = null,
    val pendingMcpInvocationApprovals: List<PendingMcpInvocationApproval> = emptyList(),
    val mcpInvocationActionBusyId: String? = null,
    val mcpInvocationActionMessage: String? = null
)

/** Owns the native runtime for the Activity lifetime, not a recyclable Compose list item. */
class LocalModelViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ModelStore(application.applicationContext)
    private val database = SheVAgentDatabaseProvider.get(application.applicationContext)
    private val dao = database.agentDao()
    private val conversationStateRepository = ConversationStateRepository(database)
    private val mcpInvocationApprovalRepository = RoomMcpInvocationApprovalRepository(database)
    private val taskRepository = RoomAgentTaskRepository(
        database,
        RoomAgentTaskAuthorizationVerifier(dao)
    )
    private val mutableState = MutableStateFlow(LocalModelUiState())
    val state: StateFlow<LocalModelUiState> = mutableState.asStateFlow()
    private var runtime: SheVAgentRuntime? = null
    @Volatile private var planExecutor: AgentPlanExecutor? = null
    private var downloadJob: Job? = null
    private var responseJob: Job? = null
    @Volatile private var pendingPlanTaskId: String? = null

    init {
        val gatewayPreferences = application.getSharedPreferences("shev_omniroute", Application.MODE_PRIVATE)
        val openAiPreferences = application.getSharedPreferences("shev_openai", Application.MODE_PRIVATE)
        val geminiPreferences = application.getSharedPreferences("shev_gemini", Application.MODE_PRIVATE)
        val openRouterPreferences = application.getSharedPreferences("shev_openrouter", Application.MODE_PRIVATE)
        val useOpenRouter = openRouterPreferences.getBoolean("use_for_primary_chat", false)
        val useGemini = !useOpenRouter && geminiPreferences.getBoolean("use_for_primary_chat", false)
        val useOpenAI = !useOpenRouter && !useGemini && openAiPreferences.getBoolean("use_for_primary_chat", false)
        val useOmniRoute = !useOpenRouter && !useGemini && !useOpenAI && gatewayPreferences.getBoolean("use_for_primary_chat", false)
        mutableState.update { it.copy(useOmniRoute = useOmniRoute, useOpenAI = useOpenAI, useGemini = useGemini, useOpenRouter = useOpenRouter) }
        viewModelScope.launch(Dispatchers.IO) {
            val planRepository = RoomAgentPlanRepository(database)
            dao.observeTasks().collect { tasks ->
                val pending = tasks
                    .filter { it.phase in setOf(
                        AgentPhase.WAITING_APPROVAL.name,
                        AgentPhase.APPROVED.name,
                        AgentPhase.RUNNING.name,
                        AgentPhase.VERIFYING.name
                    ) }
                    .map { task -> PendingTaskApproval(task, planRepository.loadPlan(task.id)) }
                mutableState.update { it.copy(pendingTaskApprovals = pending) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            dao.observePendingMcpInvocationApprovalRequests().collect { requests ->
                val pending = requests.map { request ->
                    val content = mcpInvocationApprovalRepository.readSensitiveReviewContent(request.invocationId)
                    PendingMcpInvocationApproval(
                        request = request,
                        toolDefinitionJson = content?.first,
                        argumentsJson = content?.second
                    )
                }
                mutableState.update { it.copy(pendingMcpInvocationApprovals = pending) }
            }
        }
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

    /** Approves only a persisted task that already has a canonical validated plan. */
    fun approveTask(taskId: String, expectedRevision: Long) {
        if (taskId.isBlank() || expectedRevision < 0L || mutableState.value.taskActionBusyId != null) return
        mutableState.update { it.copy(taskActionBusyId = taskId, taskActionMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                val plan = RoomAgentPlanRepository(database).loadPlan(taskId)
                if (plan == null) {
                    "Подтверждение отклонено: у поручения ещё нет сохранённого плана."
                } else {
                    val now = System.currentTimeMillis()
                    val result = taskRepository.approveFromUser(
                        taskId = taskId,
                        expectedRevision = expectedRevision,
                        eventId = UUID.nameUUIDFromBytes("approve:$taskId:$expectedRevision".toByteArray()).toString(),
                        idempotencyKey = "task-approve:$taskId:$expectedRevision",
                        occurredAtEpochMillis = now
                    )
                    when (result) {
                        is AgentTaskRepositoryResult.Applied ->
                            startAndRunApprovedPlan(taskId, result.state.revision)
                        is AgentTaskRepositoryResult.Replayed ->
                            "Это подтверждение уже было зарегистрировано ранее."
                        is AgentTaskRepositoryResult.Rejected ->
                            when (result.reason) {
                                AgentTaskRepositoryRejection.INVALID_REVISION -> "План изменился. Обнови список и проверь его заново."
                                AgentTaskRepositoryRejection.INVALID_PHASE -> "Это поручение уже нельзя подтвердить в текущем состоянии."
                                AgentTaskRepositoryRejection.INVALID_APPROVAL_EVIDENCE -> "Не удалось проверить одноразовое подтверждение."
                                AgentTaskRepositoryRejection.PLAN_REQUIRED -> "Сначала нужно сформировать и сохранить план действий."
                                else -> "Подтверждение отклонено: ${result.reason.name}."
                            }
                        AgentTaskRepositoryResult.NotFound -> "Поручение больше не найдено."
                        AgentTaskRepositoryResult.IdempotencyConflict -> "Обнаружен конфликт повторного подтверждения."
                    }
                }
            } catch (failure: Exception) {
                "Не удалось подтвердить поручение: ${failure.message ?: failure.javaClass.simpleName}"
            }
            mutableState.update { it.copy(taskActionBusyId = null, taskActionMessage = message) }
        }
    }

    /** Rejects a waiting task without dispatching any tool or external action. */
    fun cancelTask(taskId: String, expectedRevision: Long) {
        if (taskId.isBlank() || expectedRevision < 0L || mutableState.value.taskActionBusyId != null) return
        mutableState.update { it.copy(taskActionBusyId = taskId, taskActionMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val message = runCatching {
                val currentTask = dao.getTask(taskId)
                    ?: return@runCatching "Поручение больше не найдено."
                val engine = planExecutor
                when (currentTask.phase) {
                    AgentPhase.RUNNING.name -> {
                        val cancelled = if (engine != null) {
                            engine.cancelTask(taskId, expectedRevision, now)
                        } else {
                            RoomAgentPlanExecutionRepository(database).cancelTask(taskId, expectedRevision, now)
                        }
                        if (cancelled) "Поручение остановлено. Неотправленные шаги отменены; отправленные с неизвестным результатом не будут повторены."
                        else "Не удалось остановить поручение: состояние изменилось или отмена уже невозможна."
                    }
                    AgentPhase.APPROVED.name -> {
                        val cancelled = engine?.cancelTask(taskId, expectedRevision, now)
                        if (cancelled == true) "Подтверждённое поручение остановлено."
                        else if (cancelled == false) "Не удалось остановить поручение: состояние изменилось."
                        else "Не удалось остановить поручение: исполнитель ещё не подключён."
                    }
                    else -> when (val result = taskRepository.transition(
                        AgentTaskTransitionRequest(
                            taskId = taskId,
                            command = AgentWorkflowCommand.CANCEL,
                            expectedRevision = expectedRevision,
                            eventId = UUID.nameUUIDFromBytes("cancel:$taskId:$expectedRevision".toByteArray()).toString(),
                            idempotencyKey = "task-cancel:$taskId:$expectedRevision",
                            occurredAtEpochMillis = now
                        )
                    )) {
                        is AgentTaskRepositoryResult.Applied -> "Поручение отклонено. Никакие действия не выполнялись."
                        is AgentTaskRepositoryResult.Replayed -> "Отклонение этого поручения уже зарегистрировано."
                        is AgentTaskRepositoryResult.Rejected -> "Не удалось отклонить поручение: ${result.reason.name}."
                        AgentTaskRepositoryResult.NotFound -> "Поручение больше не найдено."
                        AgentTaskRepositoryResult.IdempotencyConflict -> "Обнаружен конфликт отклонения поручения."
                    }
                }
            }.getOrElse { failure ->
                "Не удалось остановить поручение: ${failure.message ?: failure.javaClass.simpleName}."
            }
            mutableState.update { it.copy(taskActionBusyId = null, taskActionMessage = message) }
        }
    }

    /** Approves one exact remote MCP action after checking its persisted task/plan/step binding. */
    fun approveMcpInvocation(invocationId: String) {
        if (invocationId.isBlank() || mutableState.value.mcpInvocationActionBusyId != null) return
        mutableState.update { it.copy(mcpInvocationActionBusyId = invocationId, mcpInvocationActionMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                mcpInvocationApprovalRepository.approve(invocationId, System.currentTimeMillis())
            }.getOrElse { McpInvocationApprovalResult.CONFLICT }
            val message = when (result) {
                McpInvocationApprovalResult.APPROVED -> "Удалённый вызов подтверждён. Он продолжится только в рамках этого точного разрешения."
                McpInvocationApprovalResult.EXPIRED -> "Срок запроса истёк. Нужно запросить новое подтверждение."
                McpInvocationApprovalResult.STALE -> "Задача или шаг изменились. Старое подтверждение недействительно."
                McpInvocationApprovalResult.NOT_FOUND -> "Запрос подтверждения больше не найден."
                else -> "Не удалось подтвердить удалённый вызов: ${result.name}."
            }
            mutableState.update { it.copy(mcpInvocationActionBusyId = null, mcpInvocationActionMessage = message) }
        }
    }

    /** Rejects one exact remote MCP action; no remote call is dispatched. */
    fun rejectMcpInvocation(invocationId: String) {
        if (invocationId.isBlank() || mutableState.value.mcpInvocationActionBusyId != null) return
        mutableState.update { it.copy(mcpInvocationActionBusyId = invocationId, mcpInvocationActionMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                mcpInvocationApprovalRepository.reject(invocationId, System.currentTimeMillis())
            }.getOrElse { McpInvocationApprovalResult.CONFLICT }
            val message = when (result) {
                McpInvocationApprovalResult.REJECTED -> "Удалённый вызов отклонён. Инструмент не запускался."
                McpInvocationApprovalResult.EXPIRED -> "Срок запроса уже истёк."
                McpInvocationApprovalResult.STALE -> "Задача или шаг изменились. Запрос больше нельзя отклонить как актуальный."
                McpInvocationApprovalResult.NOT_FOUND -> "Запрос подтверждения больше не найден."
                else -> "Не удалось отклонить удалённый вызов: ${result.name}."
            }
            mutableState.update { it.copy(mcpInvocationActionBusyId = null, mcpInvocationActionMessage = message) }
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
        }
        mutableState.update {
            it.copy(
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
        }
        mutableState.update {
            it.copy(
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
            listOf("shev_gemini", "shev_openai", "shev_omniroute").forEach { name ->
                getApplication<Application>().getSharedPreferences(name, Application.MODE_PRIVATE)
                    .edit().putBoolean("use_for_primary_chat", false).apply()
            }
        }
        mutableState.update {
            it.copy(
                useOpenRouter = enabled,
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
        }
        mutableState.update { it.copy(useGemini = enabled, useOpenAI = if (enabled) false else it.useOpenAI, useOmniRoute = if (enabled) false else it.useOmniRoute, useOpenRouter = if (enabled) false else it.useOpenRouter) }
    }

    /** Cancels the active answer so voice input can take priority. */
    fun cancelResponse() {
        responseJob?.cancel()
        responseJob = null
        mutableState.update { it.copy(busy = false, error = null) }
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
        listOf("shev_gemini", "shev_openai", "shev_openrouter", "shev_omniroute").forEach { name ->
            getApplication<Application>().getSharedPreferences(name, Application.MODE_PRIVATE)
                .edit().putBoolean("use_for_primary_chat", false).apply()
        }
        mutableState.update {
            it.copy(useGemini = false, useOpenAI = false, useOpenRouter = false, useOmniRoute = false)
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
                            ),
                            planExecutable = false
                        )
                    )
                    register(
                        AgentToolDescriptor(
                            id = SheVAgentAdkTools.CREATE_TASK_PLAN_ID,
                            title = "Сохранить план поручения",
                            description = "Проверяет и сохраняет план для последнего созданного черновика; ничего не запускает",
                            capabilities = setOf(AgentCapability.CREATE_DRAFT),
                            inputSchema = ToolInputSchema(
                                listOf(
                                    ToolInputField("task_id", ToolInputType.STRING, required = true, maxLength = 64),
                                    ToolInputField("summary", ToolInputType.STRING, required = true, maxLength = 2_000),
                                    ToolInputField("steps_json", ToolInputType.STRING, required = true, maxLength = 6_000)
                                )
                            ),
                            planExecutable = false
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
                            ),
                            planExecutable = true
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
                            ),
                            planExecutable = false
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
                            is AgentTaskDraftResult.Created -> {
                                pendingPlanTaskId = taskId
                                AgentToolResult(
                                    true,
                                    "Черновик поручения «${result.state.task.title}» сохранён. ID поручения: $taskId. Теперь подготовь и сохрани план через save_task_plan. Никакое действие не запускалось."
                                )
                            }
                            is AgentTaskDraftResult.Replayed -> {
                                pendingPlanTaskId = taskId
                                AgentToolResult(
                                    true,
                                    "Это поручение уже сохранено. ID поручения: $taskId. Подготовь и сохрани план через save_task_plan. Повторная запись не создана."
                                )
                            }
                            AgentTaskDraftResult.InvalidRequest -> AgentToolResult(false, "Поручение не сохранено: некорректный запрос.", "INVALID_REQUEST")
                            AgentTaskDraftResult.Conflict -> AgentToolResult(false, "Поручение не сохранено: обнаружен конфликт записи.", "CONFLICT")
                            AgentTaskDraftResult.NotFound -> AgentToolResult(false, "Поручение не найдено после записи.", "NOT_FOUND")
                        }
                    }
                }
                val taskPlanHandler = SuspendingAgentTool { input ->
                    val payload = runCatching { JsonParser.parseString(input).asJsonObject }.getOrNull()
                    val taskId = payload?.get("task_id")?.takeIf { it.isJsonPrimitive }?.asString
                    val summary = payload?.get("summary")?.takeIf { it.isJsonPrimitive }?.asString
                    val stepsJson = payload?.get("steps_json")?.takeIf { it.isJsonPrimitive }?.asString
                    if (taskId.isNullOrBlank() || summary.isNullOrBlank() || stepsJson.isNullOrBlank()) {
                        AgentToolResult(false, "План не сохранён: отсутствуют обязательные поля.", "INVALID_PLAN_REQUEST")
                    } else if (taskId != pendingPlanTaskId) {
                        AgentToolResult(false, "План можно сохранить только для последнего черновика, созданного в этом сеансе.", "TASK_DRAFT_MISMATCH")
                    } else {
                        val steps = runCatching {
                            val array = JsonParser.parseString(stepsJson).asJsonArray
                            array.map { element ->
                                val step = element.asJsonObject
                                require(step.keySet().all { it in setOf(
                                    "id", "toolId", "argumentsJson", "dependsOn", "risk",
                                    "purpose", "expectedOutcome", "timeoutMillis"
                                ) }) { "Unknown plan step field" }
                                val dependencyValue = step.get("dependsOn")
                                require(dependencyValue == null || dependencyValue.isJsonArray) {
                                    "dependsOn must be a JSON array"
                                }
                                val dependencies = dependencyValue?.asJsonArray
                                    ?.map { it.asString }?.toSet().orEmpty()
                                PlanStep(
                                    id = step.get("id").asString,
                                    toolId = step.get("toolId").asString,
                                    argumentsJson = step.get("argumentsJson").asString,
                                    dependsOn = dependencies,
                                    risk = ToolRisk.valueOf(step.get("risk").asString),
                                    purpose = step.get("purpose").asString,
                                    expectedOutcome = step.get("expectedOutcome").asString,
                                    timeoutMillis = step.get("timeoutMillis")?.asLong ?: 30_000L
                                )
                            }
                        }.getOrNull()
                        if (steps.isNullOrEmpty()) {
                            AgentToolResult(false, "План не сохранён: список шагов пуст или имеет неверный формат.", "INVALID_PLAN")
                        } else {
                            val previousPlan = dao.getAgentPlan(taskId)
                            val nextRevision = if (previousPlan == null) 0L else {
                                if (previousPlan.planRevision == Long.MAX_VALUE) Long.MAX_VALUE
                                else previousPlan.planRevision + 1L
                            }
                            val plan = runCatching {
                                AgentPlan(
                                    taskId = taskId,
                                    revision = nextRevision,
                                    summary = summary,
                                    steps = steps
                                )
                            }.getOrNull()
                            if (plan == null || nextRevision == Long.MAX_VALUE) {
                                AgentToolResult(false, "План не сохранён: нарушена структура плана или исчерпана ревизия.", "INVALID_PLAN")
                            } else {
                                val validation = AgentPlanToolValidator.validate(plan, registry)
                                if (!validation.accepted) {
                                    AgentToolResult(
                                        false,
                                        "${validation.reason ?: "Проверка плана не пройдена."} Шаг: ${validation.stepId ?: "неизвестен"}.",
                                        "PLAN_VALIDATION_FAILED"
                                    )
                                } else {
                                    when (val saved = RoomAgentPlanRepository(database).saveValidatedPlan(plan, System.currentTimeMillis())) {
                                        AgentPlanSaveResult.STORED -> {
                                            pendingPlanTaskId = null
                                            val currentTask = dao.getTask(taskId)
                                            AgentToolResult(
                                                true,
                                                "План сохранён и проверен. Ревизия плана: ${plan.revision}; ревизия поручения: ${currentTask?.revision ?: "неизвестна"}. Покажи пользователю план в разделе «Поручения» и дождись его подтверждения. Никакие инструменты не запускались."
                                            )
                                        }
                                        AgentPlanSaveResult.TASK_NOT_FOUND -> AgentToolResult(false, "План не сохранён: поручение не найдено.", "TASK_NOT_FOUND")
                                        AgentPlanSaveResult.INVALID_TASK_PHASE -> AgentToolResult(false, "План не сохранён: поручение уже не находится на этапе планирования или ожидания подтверждения.", "INVALID_TASK_PHASE")
                                        AgentPlanSaveResult.STALE_REVISION -> AgentToolResult(false, "План не сохранён: его ревизия устарела. Перечитай поручение и подготовь новый план.", "STALE_PLAN_REVISION")
                                        AgentPlanSaveResult.INVALID_TIMESTAMP -> AgentToolResult(false, "План не сохранён: некорректное время.", "INVALID_TIMESTAMP")
                                    }
                                }
                            }
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
                            AgentToolResult(true, registry.list().filter { it.planExecutable }.joinToString { it.id })
                        },
                        SheVAgentAdkTools.CURRENT_TIME_TOOL_ID to currentTimeHandler
                    ),
                    maxInputChars = 24_000,
                    suspendingHandlers = mapOf(
                        SheVAgentAdkTools.CREATE_TASK_DRAFT_ID to taskDraftHandler,
                        SheVAgentAdkTools.CREATE_TASK_PLAN_ID to taskPlanHandler
                    )
                )
                planExecutor = AgentPlanExecutor(database, registry, executor)
                val tools = SheVAgentAdkTools(
                    executor,
                    AgentToolExecutionContext(
                        setOf(AgentCapability.ANALYZE_DATA, AgentCapability.CREATE_DRAFT)
                    )
                )
                runtime = SheVAgentRuntime.load(getApplication(), store, tools)
                mutableState.update { it.copy(ready = true, backendName = runtime?.backendName) }
                viewModelScope.launch(Dispatchers.IO) { recoverAndResumePersistedPlans() }
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


    /**
     * Starts execution only after the explicit task approval has been persisted.
     * The current runtime allowlist contains only the local read-only clock tool;
     * every other capability remains unavailable until its own approval flow exists.
     */
    private suspend fun startAndRunApprovedPlan(taskId: String, approvedRevision: Long): String {
        val engine = planExecutor
            ?: return "План подтверждён и сохранён. Запусти SheV, чтобы подключить исполнитель и продолжить."
        return when (engine.startPlan(taskId, approvedRevision, System.currentTimeMillis())) {
            StartPlanExecutionResult.STARTED,
            StartPlanExecutionResult.ALREADY_STARTED -> executePersistedPlan(taskId, engine)
            StartPlanExecutionResult.STALE_TASK -> "План подтверждён, но ревизия поручения изменилась. Обнови список и проверь состояние."
            StartPlanExecutionResult.NOT_FOUND -> "Не удалось запустить план: поручение не найдено."
            StartPlanExecutionResult.INVALID_PHASE -> "Не удалось запустить план: поручение находится не на этапе запуска."
            StartPlanExecutionResult.PLAN_NOT_FOUND -> "Не удалось запустить план: сохранённый план не найден."
            StartPlanExecutionResult.INVALID_PLAN -> "Не удалось запустить план: сохранённая структура плана не прошла проверку."
            StartPlanExecutionResult.CONFLICT -> "Не удалось запустить план: обнаружен конфликт состояния выполнения."
        }
    }

    private suspend fun executePersistedPlan(
        taskId: String,
        engine: AgentPlanExecutor
    ): String {
        repeat(MAX_PLAN_STEPS_PER_PUMP) {
            val attempt = engine.executeNext(
                taskId = taskId,
                grantedCapabilities = setOf(AgentCapability.ANALYZE_DATA),
                explicitlyApprovedCapabilities = setOf(AgentCapability.ANALYZE_DATA)
            )
            when (attempt.status) {
                    PlanExecutionAttemptStatus.STEP_COMPLETED -> Unit
                    PlanExecutionAttemptStatus.PLAN_COMPLETE -> {
                        val task = dao.getTask(taskId)
                            ?: return "Шаги выполнены, но поручение не найдено для перехода к проверке."
                        val transition = taskRepository.transition(
                            AgentTaskTransitionRequest(
                                taskId = taskId,
                                command = AgentWorkflowCommand.BEGIN_VERIFICATION,
                                expectedRevision = task.revision,
                                eventId = UUID.nameUUIDFromBytes("verify:$taskId:${task.revision}".toByteArray()).toString(),
                                idempotencyKey = "plan-verify:$taskId:${task.revision}",
                                occurredAtEpochMillis = System.currentTimeMillis()
                            )
                        )
                        return when (transition) {
                            is AgentTaskRepositoryResult.Applied -> "Все шаги плана выполнены. Поручение переведено на этап проверки результата."
                            is AgentTaskRepositoryResult.Replayed -> "Шаги плана выполнены. Проверка результата уже была запущена."
                            else -> "Шаги плана выполнены, но не удалось безопасно начать проверку: ${transition.javaClass.simpleName}."
                        }
                    }
                    PlanExecutionAttemptStatus.BLOCKED ->
                        return "Выполнение остановлено: следующий шаг заблокирован или ожидает отдельного разрешения."
                    PlanExecutionAttemptStatus.TASK_NOT_RUNNING ->
                        return "Выполнение остановлено: поручение больше не находится в состоянии RUNNING."
                    PlanExecutionAttemptStatus.TOOL_NOT_AVAILABLE ->
                        return "Выполнение остановлено: инструмент шага не зарегистрирован в этом запуске SheV."
                    PlanExecutionAttemptStatus.TOOL_CONFIGURATION_INVALID ->
                        return "Выполнение остановлено: инструмент не разрешён для планового запуска."
                    PlanExecutionAttemptStatus.STEP_FAILED ->
                        return "Выполнение остановлено на шаге ${attempt.stepId ?: "?"}: ${attempt.errorCode ?: "ошибка шага"}."
                    PlanExecutionAttemptStatus.OUTCOME_UNKNOWN ->
                        return "Результат шага ${attempt.stepId ?: "?"} неизвестен. Повторный запуск заблокирован до ручной проверки."
            }
        }
        return "Выполнение приостановлено после ${MAX_PLAN_STEPS_PER_PUMP} шагов. Продолжение будет выполнено отдельным циклом."
    }

    /** Reconciles interrupted dispatches before resuming any still-undispatched work. */
    private suspend fun recoverAndResumePersistedPlans() {
        val engine = planExecutor ?: return
        val tasks = dao.observeTasks().first()
        tasks.filter { it.phase == AgentPhase.RUNNING.name }.forEach { task ->
            val uncertain = engine.recoverAfterProcessRestart(task.id, System.currentTimeMillis())
            if (uncertain > 0) {
                mutableState.update {
                    it.copy(planExecutionMessage = "Поручение «${task.title}»: найдено ${uncertain} незавершённых отправок. Их результат неизвестен; автоматический повтор запрещён.")
                }
            } else {
                val message = executePersistedPlan(task.id, engine)
                mutableState.update { it.copy(planExecutionMessage = "Поручение «${task.title}»: $message") }
            }
        }
        tasks.filter { it.phase == AgentPhase.APPROVED.name }.forEach { task ->
            val message = startAndRunApprovedPlan(task.id, task.revision)
            mutableState.update { it.copy(planExecutionMessage = "Поручение «${task.title}»: $message") }
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
        const val MAX_PLAN_STEPS_PER_PUMP = 50
        const val CONTEXT_HISTORY_LIMIT = 12
    }
}
