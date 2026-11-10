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
import androidx.compose.material3.lightColorScheme
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
        // Keep database initialization so the encrypted conversation history remains available.
        SheVAgentDatabaseProvider.get(applicationContext)
        val chatViewModel = ViewModelProvider(this)[LocalModelViewModel::class.java]
        setContent {
            val palette = lightColorScheme(
                primary = Color(0xFF4268D5),
                onPrimary = Color.White,
                secondary = Color(0xFF159EAD),
                background = Color(0xFFDDE1E9),
                onBackground = Color(0xFF17243B),
                surface = Color(0xFFEEF0F5),
                onSurface = Color(0xFF17243B),
                surfaceVariant = Color(0xFFD0D6E1),
                onSurfaceVariant = Color(0xFF505D73),
                outline = Color(0xFFB8C1D0)
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