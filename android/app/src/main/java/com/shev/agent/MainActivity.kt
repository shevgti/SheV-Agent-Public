package com.shev.agent

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shev.agent.creator.AgentCreatorScreen
import com.shev.agent.data.SheVAgentDatabase
import com.shev.agent.data.SheVAgentDatabaseProvider
import com.shev.agent.model.LocalModelPanel
import com.shev.agent.model.LocalModelViewModel
import com.shev.agent.tools.GitHubAuthCard
import com.shev.agent.tools.ToolWorkshopScreen
import com.shev.agent.updates.ReleaseUpdateCard
import com.shev.agent.update.UpdateCheckWorker

/**
 * The specialised-agent factory is the default screen. The primary chat and
 * safe tool workshop remain available as separate bottom-navigation destinations.
 */
class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            getSharedPreferences(PERMISSIONS_PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_NOTIFICATION_PROMPTED, true).apply()
            requestMicrophonePermissionIfNeeded()
            UpdateCheckWorker.schedule(applicationContext)
        }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            getSharedPreferences(PERMISSIONS_PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_MICROPHONE_PROMPTED, true).apply()
            UpdateCheckWorker.schedule(applicationContext)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 19, 24)
        window.navigationBarColor = android.graphics.Color.rgb(16, 19, 24)
        window.decorView.systemUiVisibility = 0
        // Create the high-importance update channel before requesting notification permission.
        UpdateCheckWorker.ensureNotificationChannel(applicationContext)
        // One Room database stores encrypted conversation history and declarative agent drafts.
        val database = SheVAgentDatabaseProvider.get(applicationContext)
        val chatViewModel = ViewModelProvider(this)[LocalModelViewModel::class.java]
        setContent {
            val palette = darkColorScheme(
                primary = Color(0xFF8EA9FF),
                onPrimary = Color(0xFF101318),
                secondary = Color(0xFF75D4D8),
                background = Color(0xFF101318),
                onBackground = Color(0xFFE8EAF0),
                surface = Color(0xFF1B2029),
                onSurface = Color(0xFFE8EAF0),
                surfaceVariant = Color(0xFF292F3A),
                onSurfaceVariant = Color(0xFFB2BAC8),
                outline = Color(0xFF424B5A)
            )
            MaterialTheme(colorScheme = palette) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SheVAgentApp(database, chatViewModel)
                }
            }
        }
        requestFirstLaunchPermissions()
        // Run one update check immediately and keep the 15-minute periodic check scheduled.
        UpdateCheckWorker.schedule(applicationContext)
    }

    /**
     * Ask for update notifications first on the first app launch after install.
     * Android owns the system dialog and the user makes the final choice.
     */
    private fun requestFirstLaunchPermissions() {
        val prefs = getSharedPreferences(PERMISSIONS_PREFS, MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted && !prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)) {
                window.decorView.post {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                return
            }
            if (granted) prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, true).apply()
        } else {
            // Android 12 and below do not have the POST_NOTIFICATIONS runtime permission.
            prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, true).apply()
        }
        requestMicrophonePermissionIfNeeded()
    }

    private fun requestMicrophonePermissionIfNeeded() {
        val prefs = getSharedPreferences(PERMISSIONS_PREFS, MODE_PRIVATE)
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            prefs.edit().putBoolean(KEY_MICROPHONE_PROMPTED, true).apply()
            return
        }
        if (!prefs.getBoolean(KEY_MICROPHONE_PROMPTED, false)) {
            window.decorView.post {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private companion object {
        const val PERMISSIONS_PREFS = "shev_agent_permissions"
        const val KEY_NOTIFICATION_PROMPTED = "notification_prompt_completed"
        const val KEY_MICROPHONE_PROMPTED = "microphone_prompt_completed"
    }
}

private enum class SheVAgentTab(val label: String, val glyph: String) {
    CREATOR("Создатель", "✧"),
    CHAT("Чат", "◉"),
    TOOLS("Настройки", "⚙")
}

@Composable
private fun SheVAgentApp(
    database: SheVAgentDatabase,
    chatViewModel: LocalModelViewModel
) {
    var tab by rememberSaveable { mutableStateOf(SheVAgentTab.CREATOR) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                SheVAgentTab.values().forEach { item ->
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
            SheVAgentTab.CREATOR -> Box(Modifier.fillMaxSize().padding(inset)) {
                AgentCreatorScreen(database.agentDao(), chatViewModel)
            }
            SheVAgentTab.CHAT -> Box(Modifier.fillMaxSize().padding(inset)) {
                LocalModelPanel(chatViewModel)
            }
            SheVAgentTab.TOOLS -> SettingsScreen(
                modifier = Modifier.fillMaxSize().padding(inset)
            )
        }
    }
}


@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    var showGitHub by rememberSaveable { mutableStateOf(false) }
    var showWorkshop by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Обновление приложения и дополнительные подключения.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ReleaseUpdateCard()
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                TextButton(onClick = { showGitHub = !showGitHub }) {
                    Text(if (showGitHub) "Скрыть подключение GitHub" else "Подключение GitHub")
                }
                if (showGitHub) GitHubAuthCard()
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                TextButton(onClick = { showWorkshop = !showWorkshop }) {
                    Text(if (showWorkshop) "Скрыть мастерскую инструментов" else "Дополнительно: мастерская инструментов")
                }
                if (showWorkshop) ToolWorkshopScreen()
            }
        }
    }
}
