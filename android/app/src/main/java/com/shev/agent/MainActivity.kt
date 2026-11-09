package com.shev.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.shev.agent.data.AgentEventEntity
import com.shev.agent.data.AgentTaskEntity
import com.shev.agent.data.AgentTaskRepositoryResult
import com.shev.agent.data.AgentTaskTransitionRequest
import com.shev.agent.data.RoomAgentTaskRepository
import com.shev.agent.data.RoomAgentTaskAuthorizationVerifier
import com.shev.agent.data.SheVAgentDatabase
import com.shev.agent.data.SheVAgentDatabaseProvider
import com.shev.agent.data.WorkspaceLayoutEntity
import com.shev.agent.ui.AgentWorkspaceLayout
import com.shev.agent.ui.AgentWorkspaceLayoutPolicy
import com.shev.agent.ui.WorkspaceComponent
import com.shev.agent.ui.WorkspaceComponentType
import com.shev.agent.ui.WorkspaceEdit
import com.shev.agent.ui.WorkspaceEditStatus
import com.shev.agent.tools.GitHubAuthCard
import com.shev.agent.tools.GitHubTokenStore
import com.shev.agent.tools.ImprovementLabScreen
import com.shev.agent.tools.ToolWorkshopScreen
import com.shev.agent.model.LocalModelPanel
import com.shev.agent.model.LocalModelViewModel
import com.shev.agent.model.OmniRoutePanel
import kotlinx.coroutines.launch
import java.util.UUID

private enum class AgentTab(val label: String, val glyph: String) {
    HOME("Чат", "◉"), PROJECTS("Проекты", "▦"),
    TOOLS("Инструменты", "⚙"), MEMORY("Память", "◷")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = SheVAgentDatabaseProvider.get(applicationContext)
        val tokenStore = GitHubTokenStore(applicationContext)
        val taskRepository = RoomAgentTaskRepository(
            db,
            RoomAgentTaskAuthorizationVerifier(db.agentDao())
        )
        val localModelViewModel = ViewModelProvider(this)[LocalModelViewModel::class.java]
        setContent { SheVAgentApp(db, tokenStore, taskRepository, localModelViewModel) }
    }
}

@Composable
private fun SheVAgentApp(db: SheVAgentDatabase, tokenStore: GitHubTokenStore, taskRepository: RoomAgentTaskRepository, localModelViewModel: LocalModelViewModel) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("shev_agent_preferences", android.content.Context.MODE_PRIVATE)
    }
    var autonomousEnabled by remember { mutableStateOf(!preferences.getBoolean("agent_paused", false)) }
    var showAutonomyInfo by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var taskSaveMessage by remember { mutableStateOf<String?>(null) }
    var taskSaving by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(AgentTab.HOME) }

    LaunchedEffect(autonomousEnabled) {
        preferences.edit().putBoolean("agent_paused", !autonomousEnabled).apply()
    }
    var tasks by remember { mutableStateOf(emptyList<AgentTaskEntity>()) }
    var events by remember { mutableStateOf(emptyList<AgentEventEntity>()) }
    var conversationMessages by remember { mutableStateOf(emptyList<com.shev.agent.data.ConversationMessageEntity>()) }
    val dao = remember(db) { db.agentDao() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(dao) {
        dao.observeTasks().collect { tasks = it }
    }
    LaunchedEffect(dao) { dao.observeEvents().collect { events = it } }
    LaunchedEffect(dao) { dao.observeConversationMessages().collect { conversationMessages = it } }

    val palette = lightColorScheme(
        primary = Color(0xFF4268D5), onPrimary = Color.White,
        secondary = Color(0xFF159EAD), background = Color(0xFFF3F6FC),
        onBackground = Color(0xFF17243B), surface = Color.White,
        onSurface = Color(0xFF17243B), surfaceVariant = Color(0xFFE9EEF8),
        onSurfaceVariant = Color(0xFF65738D), outline = Color(0xFFDCE3F0)
    )

    MaterialTheme(colorScheme = palette) {
        if (showAutonomyInfo) {
            AlertDialog(
                onDismissRequest = { showAutonomyInfo = false },
                title = { Text("Автономная работа") },
                text = { Text("Когда режим включён, агент принимает новые задачи. Когда выключен — создание новых задач блокируется. Сейчас приложение сохраняет поручения как черновики: автоматическое исполнение ещё подключается. В будущем уже начатая операция должна безопасно завершиться или остановиться в контрольной точке. Разрешения и подтверждение важных действий обязательны в любом режиме.") },
                confirmButton = { TextButton(onClick = { showAutonomyInfo = false }) { Text("Понятно") } }
            )
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    AgentTab.values().forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(item.glyph, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(item.label, maxLines = 1) }
                        )
                    }
                }
            }
        ) { inset ->
            when (tab) {
                AgentTab.HOME -> HomeScreen(
                    Modifier.padding(inset), dao, autonomousEnabled, { autonomousEnabled = it }, { showAutonomyInfo = true },
                    prompt, taskSaveMessage, taskSaving, { prompt = it },
                    tasks, taskRepository, localModelViewModel, { tab = AgentTab.TOOLS }, {
                        val title = prompt.trim()
                        if (title.isNotEmpty() && autonomousEnabled && !taskSaving) {
                            taskSaving = true
                            val now = System.currentTimeMillis()
                            val id = UUID.randomUUID().toString()
                            taskSaveMessage = "Сохраняю поручение…"
                            scope.launch {
                                val result = try {
                                    taskRepository.createDraft(
                                        com.shev.agent.data.AgentTaskDraftRequest(
                                            taskId = id,
                                            title = title,
                                            eventId = UUID.randomUUID().toString(),
                                            idempotencyKey = "task-create:$id",
                                            occurredAtEpochMillis = now
                                        )
                                    )
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    taskSaving = false
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                                when (result) {
                                    is com.shev.agent.data.AgentTaskDraftResult.Created -> {
                                        taskSaveMessage = "Поручение сохранено и ожидает подтверждения."
                                        prompt = ""
                                    }
                                    is com.shev.agent.data.AgentTaskDraftResult.Replayed -> {
                                        taskSaveMessage = "Поручение уже сохранено. Повторная запись не создана."
                                        prompt = ""
                                    }
                                    com.shev.agent.data.AgentTaskDraftResult.InvalidRequest ->
                                        taskSaveMessage = "Проверь текст поручения и попробуй ещё раз."
                                    com.shev.agent.data.AgentTaskDraftResult.Conflict ->
                                        taskSaveMessage = "Конфликт записи. Поручение не создано."
                                    com.shev.agent.data.AgentTaskDraftResult.NotFound ->
                                        taskSaveMessage = "Запись события найдена, но задача отсутствует. Нужна проверка журнала."
                                    null ->
                                        taskSaveMessage = "Не удалось сохранить поручение. Текст оставлен для повторной попытки."
                                }
                                taskSaving = false
                            }
                        }
                    }
                )
                AgentTab.PROJECTS -> ProjectsScreen(Modifier.padding(inset))
                AgentTab.TOOLS -> ToolsScreen(Modifier.padding(inset), dao, tokenStore)
                AgentTab.MEMORY -> MemoryScreen(Modifier.padding(inset), tasks, events, conversationMessages, taskRepository)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    dao: com.shev.agent.data.AgentDao,
    autonomousEnabled: Boolean,
    onAutonomyChange: (Boolean) -> Unit,
    onAutonomyInfo: () -> Unit,
    prompt: String,
    taskSaveMessage: String?,
    taskSaving: Boolean,
    onPrompt: (String) -> Unit,
    tasks: List<AgentTaskEntity>,
    taskRepository: RoomAgentTaskRepository,
    localModelViewModel: LocalModelViewModel,
    onOpenTools: () -> Unit,
    onSave: () -> Unit
) {
    val gson = remember { com.google.gson.Gson() }
    var workspace by remember {
        mutableStateOf(AgentWorkspaceLayout(components = listOf(
            WorkspaceComponent("tasks", WorkspaceComponentType.TASKS, "Задачи", position = 0),
            WorkspaceComponent("projects", WorkspaceComponentType.PROJECTS, "Проекты", position = 1),
            WorkspaceComponent("memory", WorkspaceComponentType.MEMORY, "Память", position = 2),
            WorkspaceComponent("tools", WorkspaceComponentType.TOOLS, "Инструменты", position = 3),
            WorkspaceComponent("status", WorkspaceComponentType.STATUS, "Состояние агента", position = 4),
            WorkspaceComponent("quick", WorkspaceComponentType.QUICK_ACTIONS, "Быстрые действия", position = 5),
            WorkspaceComponent("permissions", WorkspaceComponentType.PERMISSIONS, "Разрешения", position = 6),
            WorkspaceComponent("stop", WorkspaceComponentType.STOP_CONTROL, "Экстренная остановка", position = 7)
        )))
    }
    LaunchedEffect(dao) {
        dao.observeWorkspaceLayout("default").collect { saved ->
            if (saved == null) {
                dao.saveWorkspaceLayout(
                    WorkspaceLayoutEntity("default", 1, gson.toJson(workspace), System.currentTimeMillis())
                )
            } else {
                runCatching { gson.fromJson(saved.layoutJson, AgentWorkspaceLayout::class.java) }
                    .getOrNull()
                    ?.takeIf { AgentWorkspaceLayoutPolicy.validate(it) == null }
                    ?.let { workspace = it }
            }
        }
    }
    val visibleComponents = workspace.components.filter { it.visible }.sortedBy { it.position }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("SheV Agent", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Твой личный ИИ-помощник", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { OmniRoutePanel() }
        item { LocalModelPanel(localModelViewModel) }
        items(visibleComponents, key = { it.id }) { component ->
            when (component.type) {
                WorkspaceComponentType.TASKS -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(component.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (tasks.isEmpty()) Text("Пока задач нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tasks.take(5).forEach { TaskCard(it, taskRepository) }
                }
                WorkspaceComponentType.PROJECTS -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(component.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    ProjectCard("ФотоПорядок", "Организация фотографий и видео.", "https://github.com/shevgti/Fotoporyadok")
                    ProjectCard("Моя память", "Календарь, события и напоминания.", "https://github.com/shevgti/moya-pamyat")
                }
                WorkspaceComponentType.MEMORY -> Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(component.title, fontWeight = FontWeight.SemiBold)
                        Text("Сохранено задач: ${tasks.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("История событий и поиск доступны на вкладке «Память».", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                WorkspaceComponentType.TOOLS -> Card(shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(component.title, fontWeight = FontWeight.SemiBold)
                            Text("GitHub, разрешения и настройка агента", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onOpenTools) { Text("Открыть") }
                    }
                }
                WorkspaceComponentType.STATUS -> Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EEFF))
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (autonomousEnabled) "Создание поручений включено" else "Создание поручений приостановлено", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(if (autonomousEnabled) "Поручения сохраняются в ожидании подтверждения. Автоматическое выполнение ещё подключается." else "Создание новых поручений приостановлено.", color = Color(0xFF52617D))
                            TextButton(onClick = onAutonomyInfo) { Text("Подробнее") }
                        }
                        Switch(checked = autonomousEnabled, onCheckedChange = onAutonomyChange)
                    }
                }
                WorkspaceComponentType.QUICK_ACTIONS -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(component.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = prompt, onValueChange = onPrompt, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Что нужно сделать?") }, minLines = 3,
                        shape = RoundedCornerShape(18.dp)
                    )
                    taskSaveMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Button(
                        onClick = onSave, enabled = prompt.isNotBlank() && autonomousEnabled && !taskSaving,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) { Text(if (taskSaving) "Сохраняю…" else "Сохранить задачу") }
                }
                WorkspaceComponentType.PERMISSIONS -> Card(shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Разрешения защищены", fontWeight = FontWeight.SemiBold)
                            Text("Проверка доступа и отзыв разрешений", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onOpenTools) { Text("Управлять") }
                    }
                }
                WorkspaceComponentType.STOP_CONTROL -> Card(shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Пауза приёма задач", fontWeight = FontWeight.SemiBold)
                            Text("Системный переключатель нельзя скрыть", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = !autonomousEnabled, onCheckedChange = { onAutonomyChange(!it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: AgentTaskEntity, taskRepository: RoomAgentTaskRepository) {
    var detailsOpen by remember(task.id) { mutableStateOf(false) }
    var cancelConfirmationOpen by remember(task.id) { mutableStateOf(false) }
    var approvalConfirmationOpen by remember(task.id) { mutableStateOf(false) }
    var actionMessage by remember(task.id) { mutableStateOf<String?>(null) }
    var actionBusy by remember(task.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canPause = task.phase in setOf(
        AgentPhase.PLANNING.name, AgentPhase.WAITING_APPROVAL.name, AgentPhase.APPROVED.name,
        AgentPhase.RUNNING.name, AgentPhase.VERIFYING.name
    )
    val canResume = task.phase == AgentPhase.PAUSED.name
    val canApprove = task.phase == AgentPhase.WAITING_APPROVAL.name
    val canCancel = task.phase in setOf(
        AgentPhase.PLANNING.name, AgentPhase.WAITING_APPROVAL.name,
        AgentPhase.APPROVED.name, AgentPhase.RUNNING.name, AgentPhase.VERIFYING.name, AgentPhase.PAUSED.name
    )
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text("Статус: ${AgentTaskPresentation.statusLabel(task.phase)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Версия задачи: ${task.revision}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { detailsOpen = true }) { Text("Подробнее") }
                if (canApprove) {
                    TextButton(enabled = !actionBusy, onClick = { approvalConfirmationOpen = true }) {
                        Text("Одобрить")
                    }
                }
                if (canPause) {
                    TextButton(enabled = !actionBusy, onClick = {
                        actionBusy = true
                        scope.launch {
                            val result = runCatching {
                                taskRepository.transition(
                                    AgentTaskTransitionRequest(
                                        taskId = task.id,
                                        command = AgentWorkflowCommand.PAUSE,
                                        expectedRevision = task.revision,
                                        eventId = UUID.randomUUID().toString(),
                                        idempotencyKey = "pause:${task.id}:${task.revision}",
                                        occurredAtEpochMillis = System.currentTimeMillis()
                                    )
                                )
                            }.getOrNull()
                            actionMessage = when (result) {
                                is AgentTaskRepositoryResult.Applied -> "Задача приостановлена"
                                is AgentTaskRepositoryResult.Replayed -> "Команда уже была применена"
                                is AgentTaskRepositoryResult.Rejected -> "Пауза отклонена: ${result.reason}"
                                AgentTaskRepositoryResult.NotFound -> "Задача не найдена"
                                AgentTaskRepositoryResult.IdempotencyConflict -> "Конфликт повторной команды"
                                null -> "Не удалось сохранить изменение. Попробуй ещё раз."
                            }
                            actionBusy = false
                        }
                    }) { Text("Пауза") }
                } else if (canResume) {
                    TextButton(enabled = !actionBusy, onClick = {
                        actionBusy = true
                        scope.launch {
                            val result = runCatching {
                                taskRepository.transition(
                                    AgentTaskTransitionRequest(
                                        taskId = task.id,
                                        command = AgentWorkflowCommand.RESUME,
                                        expectedRevision = task.revision,
                                        eventId = UUID.randomUUID().toString(),
                                        idempotencyKey = "resume:${task.id}:${task.revision}",
                                        occurredAtEpochMillis = System.currentTimeMillis()
                                    )
                                )
                            }.getOrNull()
                            actionMessage = when (result) {
                                is AgentTaskRepositoryResult.Applied -> "Задача возобновлена"
                                is AgentTaskRepositoryResult.Replayed -> "Команда уже была применена"
                                is AgentTaskRepositoryResult.Rejected -> "Возобновление отклонено: ${result.reason}"
                                AgentTaskRepositoryResult.NotFound -> "Задача не найдена"
                                AgentTaskRepositoryResult.IdempotencyConflict -> "Конфликт повторной команды"
                                null -> "Не удалось сохранить изменение. Попробуй ещё раз."
                            }
                            actionBusy = false
                        }
                    }) { Text("Продолжить") }
                }
                if (canCancel) {
                    TextButton(enabled = !actionBusy, onClick = { cancelConfirmationOpen = true }) {
                        Text("Отменить")
                    }
                }
            }
            actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (approvalConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { if (!actionBusy) approvalConfirmationOpen = false },
            title = { Text("Одобрить поручение?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(task.title, fontWeight = FontWeight.SemiBold)
                    Text("Ревизия задачи: ${task.revision}")
                    Text(
                        "Одобрение будет привязано только к этой задаче и этой версии. Оно не запускает исполнителя и не выполняет никаких действий."
                    )
                    Text("Одноразовое подтверждение действует 2 минуты.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = !actionBusy, onClick = {
                    actionBusy = true
                    scope.launch {
                        val result = try {
                            taskRepository.approveFromUser(
                                taskId = task.id,
                                expectedRevision = task.revision,
                                eventId = UUID.randomUUID().toString(),
                                idempotencyKey = "approve:${task.id}:${task.revision}",
                                occurredAtEpochMillis = System.currentTimeMillis()
                            )
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            actionBusy = false
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        actionMessage = when (result) {
                            is AgentTaskRepositoryResult.Applied -> "Одобрено. Поручение ждёт исполнителя; действия не запускались."
                            is AgentTaskRepositoryResult.Replayed -> "Это поручение уже было одобрено."
                            is AgentTaskRepositoryResult.Rejected -> "Одобрение отклонено: ${result.reason}"
                            AgentTaskRepositoryResult.NotFound -> "Задача не найдена"
                            AgentTaskRepositoryResult.IdempotencyConflict -> "Конфликт повторного подтверждения"
                            null -> "Не удалось сохранить подтверждение. Попробуй ещё раз."
                        }
                        actionBusy = false
                        approvalConfirmationOpen = false
                    }
                }) { Text(if (actionBusy) "Сохраняю…" else "Подтвердить одобрение") }
            },
            dismissButton = {
                TextButton(enabled = !actionBusy, onClick = { approvalConfirmationOpen = false }) {
                    Text("Назад")
                }
            }
        )
    }
    if (cancelConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { if (!actionBusy) cancelConfirmationOpen = false },
            title = { Text("Отменить поручение?") },
            text = { Text("Задача будет помечена как отменённая. Возобновить её после этого нельзя.") },
            confirmButton = {
                TextButton(enabled = !actionBusy, onClick = {
                    actionBusy = true
                    scope.launch {
                        val result = try {
                            taskRepository.transition(
                                AgentTaskTransitionRequest(
                                    taskId = task.id,
                                    command = AgentWorkflowCommand.CANCEL,
                                    expectedRevision = task.revision,
                                    eventId = UUID.randomUUID().toString(),
                                    idempotencyKey = "cancel:${task.id}:${task.revision}",
                                    occurredAtEpochMillis = System.currentTimeMillis()
                                )
                            )
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            actionBusy = false
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        actionMessage = when (result) {
                            is AgentTaskRepositoryResult.Applied -> "Поручение отменено"
                            is AgentTaskRepositoryResult.Replayed -> "Отмена уже была применена"
                            is AgentTaskRepositoryResult.Rejected -> "Отмена отклонена: ${result.reason}"
                            AgentTaskRepositoryResult.NotFound -> "Задача не найдена"
                            AgentTaskRepositoryResult.IdempotencyConflict -> "Конфликт повторной команды"
                            null -> "Не удалось отменить задачу. Попробуй ещё раз."
                        }
                        actionBusy = false
                        cancelConfirmationOpen = false
                    }
                }) { Text(if (actionBusy) "Отменяю…" else "Отменить") }
            },
            dismissButton = {
                TextButton(enabled = !actionBusy, onClick = { cancelConfirmationOpen = false }) {
                    Text("Оставить")
                }
            }
        )
    }
    if (detailsOpen) {
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text("Карточка задачи") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(task.title, fontWeight = FontWeight.SemiBold)
                    Text("Состояние: ${AgentTaskPresentation.statusLabel(task.phase)}")
                    Text("Ревизия: ${task.revision}")
                    Text("Создана: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(task.createdAtEpochMillis))}")
                    Text("Последнее изменение: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(task.updatedAtEpochMillis))}")
                    if (task.phase == AgentPhase.WAITING_APPROVAL.name) {
                        Text(
                            "Для продолжения требуется отдельное подтверждение. После одобрения поручение будет ждать исполнителя; само одобрение не запускает действия.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (task.phase == AgentPhase.APPROVED.name) {
                        Text("Подтверждение сохранено. Задача ожидает подключения исполнителя; никаких действий ещё не запущено.")
                    }
                    if (task.phase == AgentPhase.COMPLETED.name) {
                        Text("Задача завершена.")
                    }
                    if (task.phase == AgentPhase.CANCELLED.name) {
                        Text("Поручение отменено. Возобновление недоступно.")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun ProjectsScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Мои проекты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Отдельные рабочие пространства твоих приложений.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProjectCard("ФотоПорядок", "Организация фотографий и видео.", "https://github.com/shevgti/Fotoporyadok")
        ProjectCard("Моя память", "Календарь, события и личные напоминания.", "https://github.com/shevgti/moya-pamyat")
        ProjectCard("SheV Agent", "Разработка, тестирование и развитие агента.", "https://github.com/shevgti/SheV-Agent")
    }
}

@Composable
private fun ProjectCard(title: String, description: String, repositoryUrl: String) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(repositoryUrl)
                    )
                    context.startActivity(intent)
                }) {
                    Text("Открыть репозиторий")
                    Text("↗")
                }
            }
        }
    }
}

@Composable
private fun ToolsScreen(modifier: Modifier, dao: com.shev.agent.data.AgentDao, tokenStore: GitHubTokenStore) {
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Инструменты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Text("GitHub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Подключение репозиториев, сборки и результаты тестов.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            GitHubAuthCard()
        }
        item { CapabilityCatalogCard() }
        item { WorkspaceCustomizationCard(dao) }
        item { PermissionManagementCard(dao) }
        item { ImprovementLabScreen(dao, tokenStore) }
        item { ToolWorkshopScreen() }
    }
}


@Composable
private fun CapabilityCatalogCard() {
    var expanded by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Каталог возможностей", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Какие типы действий предусмотрены в архитектуре агента.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Показать")
                }
            }
            if (expanded) {
                AgentCapability.values().forEach { capability ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        val riskLabel = when (capability.risk) {
                            CapabilityRisk.LOW -> "Низкий риск"
                            CapabilityRisk.MEDIUM -> "Средний риск"
                            CapabilityRisk.HIGH -> "Высокий риск"
                            CapabilityRisk.CRITICAL -> "Критический риск"
                        }
                        Text(capability.title, fontWeight = FontWeight.Medium)
                        Text(capability.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(riskLabel, style = MaterialTheme.typography.labelMedium, color = when (capability.risk) {
                            CapabilityRisk.LOW -> MaterialTheme.colorScheme.primary
                            CapabilityRisk.MEDIUM -> MaterialTheme.colorScheme.tertiary
                            CapabilityRisk.HIGH, CapabilityRisk.CRITICAL -> MaterialTheme.colorScheme.error
                        })
                    }
                }
                Text(
                    "Важно: это каталог архитектурных возможностей, а не список уже работающих адаптеров. Каждый инструмент подключается отдельно, а доступ выдаётся по разрешениям.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WorkspaceCustomizationCard(dao: com.shev.agent.data.AgentDao) {
    val scope = rememberCoroutineScope()
    val gson = remember { com.google.gson.Gson() }
    var layout by remember {
        mutableStateOf(
            AgentWorkspaceLayout(components = listOf(
                WorkspaceComponent("tasks", WorkspaceComponentType.TASKS, "Задачи", position = 0),
                WorkspaceComponent("projects", WorkspaceComponentType.PROJECTS, "Проекты", position = 1),
                WorkspaceComponent("memory", WorkspaceComponentType.MEMORY, "Память", position = 2),
                WorkspaceComponent("tools", WorkspaceComponentType.TOOLS, "Инструменты", position = 3),
                WorkspaceComponent("status", WorkspaceComponentType.STATUS, "Состояние агента", position = 4),
                WorkspaceComponent("quick", WorkspaceComponentType.QUICK_ACTIONS, "Быстрые действия", position = 5),
                WorkspaceComponent("permissions", WorkspaceComponentType.PERMISSIONS, "Разрешения", position = 6),
                WorkspaceComponent("stop", WorkspaceComponentType.STOP_CONTROL, "Экстренная остановка", position = 7)
            ))
        )
    }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dao) {
        dao.observeWorkspaceLayout("default").collect { saved ->
            if (saved != null) {
                runCatching { gson.fromJson(saved.layoutJson, AgentWorkspaceLayout::class.java) }
                    .getOrNull()
                    ?.takeIf { AgentWorkspaceLayoutPolicy.validate(it) == null }
                    ?.let { layout = it }
            }
            loaded = true
        }
    }
    LaunchedEffect(loaded) {
        if (loaded) {
            val json = gson.toJson(layout)
            dao.saveWorkspaceLayout(
                WorkspaceLayoutEntity(
                    layoutId = "default",
                    schemaVersion = layout.version,
                    layoutJson = json,
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
        }
    }
    fun applyEdit(edit: WorkspaceEdit) {
        val result = AgentWorkspaceLayoutPolicy.apply(layout, edit)
        if (result.status == WorkspaceEditStatus.APPLIED) {
            layout = result.layout
            message = "Изменение сохранено"
            scope.launch {
                dao.saveWorkspaceLayout(
                    WorkspaceLayoutEntity(
                        layoutId = "default",
                        schemaVersion = layout.version,
                        layoutJson = gson.toJson(layout),
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                )
            }
        } else {
            message = result.reason
        }
    }
    fun label(type: WorkspaceComponentType): String = when (type) {
        WorkspaceComponentType.TASKS -> "Задачи"
        WorkspaceComponentType.PROJECTS -> "Проекты"
        WorkspaceComponentType.MEMORY -> "Память"
        WorkspaceComponentType.TOOLS -> "Инструменты"
        WorkspaceComponentType.STATUS -> "Состояние агента"
        WorkspaceComponentType.QUICK_ACTIONS -> "Быстрые действия"
        WorkspaceComponentType.PERMISSIONS -> "Разрешения"
        WorkspaceComponentType.STOP_CONTROL -> "Экстренная остановка"
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Моё рабочее пространство", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Настройка порядка и видимости модулей. В дальнейшем этими же безопасными командами сможет пользоваться ИИ-агент.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            layout.components.sortedBy { it.position }.forEach { component ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(component.title, fontWeight = FontWeight.Medium)
                        Text(
                            if (component.type == WorkspaceComponentType.PERMISSIONS || component.type == WorkspaceComponentType.STOP_CONTROL)
                                "Системный элемент · всегда видим"
                            else if (component.visible) "Отображается" else "Скрыт",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (component.type != WorkspaceComponentType.PERMISSIONS && component.type != WorkspaceComponentType.STOP_CONTROL) {
                        TextButton(onClick = {
                            applyEdit(WorkspaceEdit.SetVisibility(component.id, !component.visible))
                        }) { Text(if (component.visible) "Скрыть" else "Показать") }
                        TextButton(onClick = { applyEdit(WorkspaceEdit.Remove(component.id)) }) { Text("Убрать") }
                        TextButton(
                            enabled = component.position > 0,
                            onClick = { applyEdit(WorkspaceEdit.Move(component.id, component.position - 1)) }
                        ) { Text("↑") }
                        TextButton(
                            enabled = component.position < layout.components.size - 1,
                            onClick = { applyEdit(WorkspaceEdit.Move(component.id, component.position + 1)) }
                        ) { Text("↓") }
                    }
                }
            }
            val addable = listOf(
                WorkspaceComponentType.TASKS, WorkspaceComponentType.PROJECTS,
                WorkspaceComponentType.MEMORY, WorkspaceComponentType.TOOLS,
                WorkspaceComponentType.STATUS, WorkspaceComponentType.QUICK_ACTIONS
            ).firstOrNull { type -> layout.components.none { it.type == type } }
            if (addable != null) {
                OutlinedButton(
                    onClick = {
                        val id = "custom-${addable.name.lowercase()}-${System.currentTimeMillis()}"
                        applyEdit(WorkspaceEdit.Add(
                            WorkspaceComponent(id, addable, label(addable), position = layout.components.size)
                        ))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Добавить модуль: ${label(addable)}") }
            }
            TextButton(
                onClick = {
                    val defaults = AgentWorkspaceLayout(components = listOf(
                        WorkspaceComponent("tasks", WorkspaceComponentType.TASKS, "Задачи", position = 0),
                        WorkspaceComponent("projects", WorkspaceComponentType.PROJECTS, "Проекты", position = 1),
                        WorkspaceComponent("memory", WorkspaceComponentType.MEMORY, "Память", position = 2),
                        WorkspaceComponent("tools", WorkspaceComponentType.TOOLS, "Инструменты", position = 3),
                        WorkspaceComponent("status", WorkspaceComponentType.STATUS, "Состояние агента", position = 4),
                        WorkspaceComponent("quick", WorkspaceComponentType.QUICK_ACTIONS, "Быстрые действия", position = 5),
                        WorkspaceComponent("permissions", WorkspaceComponentType.PERMISSIONS, "Разрешения", position = 6),
                        WorkspaceComponent("stop", WorkspaceComponentType.STOP_CONTROL, "Экстренная остановка", position = 7)
                    ))
                    layout = defaults
                    scope.launch {
                        dao.saveWorkspaceLayout(WorkspaceLayoutEntity("default", 1, gson.toJson(defaults), System.currentTimeMillis()))
                    }
                    message = "Исходная раскладка восстановлена"
                }
            ) { Text("Восстановить исходную раскладку") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun PermissionManagementCard(dao: com.shev.agent.data.AgentDao) {
    var grants by remember { mutableStateOf(emptyList<com.shev.agent.data.CapabilityGrantEntity>()) }
    var revokeTarget by remember { mutableStateOf<com.shev.agent.data.CapabilityGrantEntity?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(dao) {
        dao.observeCapabilityGrants().collect { grants = it }
    }
    val now = System.currentTimeMillis()
    val active = grants.filter {
        it.revokedAtEpochMillis == null && (it.expiresAtEpochMillis == null || it.expiresAtEpochMillis > now)
    }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Постоянные разрешения", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Здесь можно проверить и отозвать ранее выданные разрешения. Новые постоянные разрешения пока выдаются только после отдельного запроса агента.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (grants.isEmpty()) {
                Text("Постоянных разрешений пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Действующих: ${active.size} · Всего записей: ${grants.size}", style = MaterialTheme.typography.labelLarge)
                grants.forEach { grant ->
                    val isActive = grant.revokedAtEpochMillis == null &&
                        (grant.expiresAtEpochMillis == null || grant.expiresAtEpochMillis > now)
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(grant.capability, fontWeight = FontWeight.Medium)
                        Text("Область: ${grant.scopeType} · ${grant.scopeValue}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            when {
                                grant.revokedAtEpochMillis != null -> "Отозвано"
                                grant.expiresAtEpochMillis != null && grant.expiresAtEpochMillis <= now -> "Срок истёк"
                                else -> "Действует"
                            },
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (isActive) {
                            TextButton(onClick = { revokeTarget = grant }) { Text("Отозвать разрешение") }
                        }
                    }
                }
            }
        }
    }
    revokeTarget?.let { grant ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Отозвать разрешение?") },
            text = { Text("Агент больше не сможет использовать это постоянное разрешение. Для повторного доступа потребуется новый запрос.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        dao.revokeCapabilityGrant(grant.grantId, System.currentTimeMillis())
                    }
                    revokeTarget = null
                }) { Text("Отозвать") }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun MemoryScreen(modifier: Modifier, tasks: List<AgentTaskEntity>, events: List<AgentEventEntity>, messages: List<com.shev.agent.data.ConversationMessageEntity>, taskRepository: RoomAgentTaskRepository) {
    var filter by remember { mutableStateOf("Все") }
    var search by remember { mutableStateOf("") }
    val visibleTasks = if (filter == "События" || filter == "Диалог") emptyList() else AgentTaskPresentation.filter(tasks, search, filter)
    val visibleEvents = if (filter == "Задачи" || filter == "Диалог" || AgentTaskPresentation.filters.contains(filter) && filter != "Все") emptyList()
        else events.filter { search.isBlank() || it.eventType.contains(search, true) || it.message.contains(search, true) }.take(50)
    val visibleMessages = if (filter == "Диалог") messages.asReversed().filter { message ->
        search.isBlank() || runCatching { com.shev.agent.data.LocalMessageCipher.decrypt(message.content) }.getOrDefault("").contains(search, true) || message.role.contains(search, true)
    } else emptyList()
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Память агента", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Рабочая история SheV Agent. Это не проект «Моя память».", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Задачи: ${tasks.size} · события: ${events.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        item {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("Поиск по задачам, событиям и диалогу") },
                singleLine = true, shape = RoundedCornerShape(16.dp)
            )
        }
        item {
            Text("Фильтр задач", fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AgentTaskPresentation.filters.chunked(2).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { option ->
                            FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == "События", onClick = { filter = "События" }, label = { Text("События") })
                    FilterChip(selected = filter == "Диалог", onClick = { filter = "Диалог" }, label = { Text("Диалог") })
                }
            }
        }
        if (visibleTasks.isEmpty() && filter != "События" && filter != "Диалог") item {
            Text(if (tasks.isEmpty()) "Сохранённых задач пока нет." else "По этому фильтру задач нет.")
        }
        items(visibleTasks, key = { it.id }) { TaskCard(it, taskRepository) }
        if (filter == "Диалог") item { Text("История общения с ИИ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (filter == "Диалог" && visibleMessages.isEmpty()) item { Text("Сохранённых сообщений пока нет.") }
        if (filter == "Диалог") items(visibleMessages, key = { it.id }) { message ->
            val decrypted = runCatching { com.shev.agent.data.LocalMessageCipher.decrypt(message.content) }
                .getOrElse { "Не удалось расшифровать сообщение на этом устройстве." }
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (message.role == "user") "Вы" else "SheV", fontWeight = FontWeight.SemiBold)
                    Text(decrypted)
                    Text(
                        "${message.status} · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(message.createdAtEpochMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (filter == "Все" || filter == "События") item { Text("Журнал событий", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (visibleEvents.isEmpty() && (filter == "Все" || filter == "События")) item { Text("Событий пока нет.") }
        if (events.size > 50 && (filter == "Все" || filter == "События")) item { Text("Показаны последние 50 событий.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (filter == "Все" || filter == "События") items(visibleEvents, key = { it.id }) { event ->
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(event.eventType, fontWeight = FontWeight.SemiBold)
                    Text(event.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.createdAtEpochMillis)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
