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

/**
 * SheV Agent starts directly in the conversation.
 * Projects, task dashboards, memory browsers and tool catalogs are intentionally
 * kept out of the first screen; they are not part of the initial chat experience.
 */
class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        getSharedPreferences("shev_agent_permissions", MODE_PRIVATE).edit()
            .putBoolean("initial_prompt_completed", true).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 19, 24)
        window.navigationBarColor = android.graphics.Color.rgb(16, 19, 24)
        window.decorView.systemUiVisibility = 0
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
        requestEssentialPermissionsOnFirstLaunch()
    }

    // Android runtime permissions are requested on first launch, not during APK installation.
    // Only ask for microphone (voice chat) and notifications (update/status alerts).
    private fun requestEssentialPermissionsOnFirstLaunch() {
        val prefs = getSharedPreferences("shev_agent_permissions", MODE_PRIVATE)
        if (prefs.getBoolean("initial_prompt_completed", false)) return
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            prefs.edit().putBoolean("initial_prompt_completed", true).apply()
            return
        }
        window.decorView.post { permissionLauncher.launch(missing.toTypedArray()) }
    }
}