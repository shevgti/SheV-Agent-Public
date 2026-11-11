package com.shev.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.shev.chat.data.ChatEntryEntity
import com.shev.chat.data.ChatHistoryDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val Mint = Color(0xFF6EE7B7)
private val Muted = Color(0xFF9CAFC5)

class ChatActivity : ComponentActivity() {
    private val history by lazy { ChatHistoryDatabase.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SheVChatHome(
                database = history,
                onOpenChatGPT = { openOfficialChatGPT() },
                onCopyContext = { project, entries ->
                    copyProjectContext(project, entries)
                    openOfficialChatGPT()
                },
                onNewChat = { project, entries -> startNewChat(project, entries) },
                onSaveEntry = { project, role, text ->
                    lifecycleScope.launch {
                        history.entries().insertAndPrune(
                            ChatEntryEntity(
                                id = UUID.randomUUID().toString(),
                                projectId = project,
                                role = role,
                                content = text.trim(),
                                createdAtEpochMillis = System.currentTimeMillis()
                            )
                        )
                    }
                },
                onEnableAutomation = { openAccessibilitySettings() }
            )
        }
    }

    private fun openOfficialChatGPT() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(
                this,
                "Официальное приложение ChatGPT не найдено. Установи его из Google Play.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startNewChat(project: String, entries: List<ChatEntryEntity>) {
        copyProjectContext(project, entries)
        getSharedPreferences(ChatGPTControlService.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(ChatGPTControlService.KEY_NEW_CHAT_REQUESTED, true)
            .putLong(ChatGPTControlService.KEY_NEW_CHAT_REQUESTED_AT, System.currentTimeMillis())
            .apply()
        openOfficialChatGPT()
        Toast.makeText(
            this,
            "Подготовленный контекст скопирован. Новый чат откроется автоматически только если ты отдельно включил службу SheV Chat.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun copyProjectContext(project: String, entries: List<ChatEntryEntity>) {
        val prompt = ChatProjectContext.build(project, entries)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Контекст SheV — $project", prompt))
        Toast.makeText(
            this,
            "Контекст и последние записи $project скопированы. Вставь их в ChatGPT.",
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"
    }
}

@Composable
private fun SheVChatHome(
    database: ChatHistoryDatabase,
    onOpenChatGPT: () -> Unit,
    onCopyContext: (String, List<ChatEntryEntity>) -> Unit,
    onNewChat: (String, List<ChatEntryEntity>) -> Unit,
    onSaveEntry: (String, String, String) -> Unit,
    onEnableAutomation: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val projectPrefs = remember(context) {
        context.getSharedPreferences("shev_chat_projects", Context.MODE_PRIVATE)
    }
    var project by rememberSaveable {
        mutableStateOf(projectPrefs.getString("selected_project", "ФотоПорядок") ?: "ФотоПорядок")
    }
    var role by rememberSaveable { mutableStateOf(ChatActivity.ROLE_USER) }
    var draft by rememberSaveable { mutableStateOf("") }
    val entries by remember(project) {
        database.entries().observeRecent(project)
    }.collectAsState(initial = emptyList())
    val orderedEntries = entries.reversed()
    val scheme = darkColorScheme(
        primary = Mint,
        onPrimary = Color(0xFF06291E),
        background = Color(0xFF0B1220),
        surface = Color(0xFF162235),
        onSurface = Color(0xFFF3F6FC),
        secondary = Color(0xFF7DD3FC)
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(42.dp).background(Color(0xFF10B981), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("S", color = Color(0xFF06291E), fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("SheV Chat", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Проектная история и быстрый переход в ChatGPT", color = Muted, fontSize = 11.sp)
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ПРОЕКТ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("ФотоПорядок", "SheV Agent", "Моя память").forEach { name ->
                                val selected = project == name
                                OutlinedButton(
                                    onClick = {
                                    project = name
                                    projectPrefs.edit().putString("selected_project", name).apply()
                                },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (selected) Color(0xFF164E45) else Color.Transparent,
                                        contentColor = if (selected) Mint else scheme.onSurface
                                    )
                                ) {
                                    Text(
                                        when (name) {
                                            "ФотоПорядок" -> "Фото"
                                            "SheV Agent" -> "Agent"
                                            else -> "Память"
                                        },
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Локальная история", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${entries.size} / 100", color = Muted, fontSize = 11.sp)
                }

                if (orderedEntries.isEmpty()) {
                    Card(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = scheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("История этого проекта пока пуста", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Сохраняй сюда свои сообщения и ответы из ChatGPT. Записи хранятся локально на устройстве и не синхронизируются с официальным ChatGPT.",
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(orderedEntries, key = { it.id }) { entry ->
                            ChatEntryBubble(entry)
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { role = ChatActivity.ROLE_USER },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (role == ChatActivity.ROLE_USER) Color(0xFF164E45) else Color.Transparent,
                                    contentColor = if (role == ChatActivity.ROLE_USER) Mint else scheme.onSurface
                                )
                            ) { Text("Моё сообщение", fontSize = 11.sp) }
                            OutlinedButton(
                                onClick = { role = ChatActivity.ROLE_ASSISTANT },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (role == ChatActivity.ROLE_ASSISTANT) Color(0xFF164E45) else Color.Transparent,
                                    contentColor = if (role == ChatActivity.ROLE_ASSISTANT) Mint else scheme.onSurface
                                )
                            ) { Text("Ответ ChatGPT", fontSize = 11.sp) }
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 150.dp),
                            placeholder = { Text("Сохрани сообщение или вставь ответ из ChatGPT…") },
                            shape = RoundedCornerShape(14.dp)
                        )
                        Button(
                            onClick = {
                                val value = draft.trim()
                                if (value.isNotEmpty()) {
                                    onSaveEntry(project, role, value)
                                    draft = ""
                                }
                            },
                            enabled = draft.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(13.dp)
                        ) { Text("Сохранить в истории проекта") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenChatGPT,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Открыть ChatGPT", fontSize = 11.sp) }
                    Button(
                        onClick = { onCopyContext(project, orderedEntries) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Контекст + ChatGPT", fontSize = 11.sp) }
                }
                OutlinedButton(
                    onClick = { onNewChat(project, orderedEntries) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Новый чат выбранного проекта", fontSize = 12.sp) }

                OutlinedButton(
                    onClick = onEnableAutomation,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Настроить открытие нового чата", fontSize = 11.sp) }

                Text(
                    "SheV Chat хранит только те записи, которые ты сам добавил. Он не читает переписку ChatGPT и не отправляет туда сообщения автоматически. Для ответа используется официальное приложение.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun ChatEntryBubble(entry: ChatEntryEntity) {
    val isAssistant = entry.role == ChatActivity.ROLE_ASSISTANT
    val color = if (isAssistant) Color(0xFF1C3145) else Color(0xFF164E45)
    val time = remember(entry.createdAtEpochMillis) {
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(entry.createdAtEpochMillis))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isAssistant) "ОТВЕТ CHATGPT" else "МОЁ СООБЩЕНИЕ",
                    color = Mint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(time, color = Muted, fontSize = 9.sp)
            }
            Text(entry.content, color = Color(0xFFF3F6FC), fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
