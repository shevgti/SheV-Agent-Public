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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shev.agent.data.SheVAgentDatabaseProvider
import com.shev.agent.model.LocalModelPanel
import com.shev.agent.model.LocalModelViewModel
import com.shev.agent.update.UpdateCheckWorker

/**
 * SheV Agent starts directly in the conversation.
 * Projects, task dashboards, memory browsers and tool catalogs are intentionally
 * kept out of the first screen; they are not part of the initial chat experience.
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
        // Keep database initialization so the encrypted conversation history remains available.
        SheVAgentDatabaseProvider.get(applicationContext)
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
                    LocalModelPanel(chatViewModel)
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
