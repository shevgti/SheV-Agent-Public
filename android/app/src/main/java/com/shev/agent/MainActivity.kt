package com.shev.agent

import android.os.Bundle
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
                background = Color(0xFFF5F6FA),
                onBackground = Color(0xFF17243B),
                surface = Color.White,
                onSurface = Color(0xFF17243B),
                surfaceVariant = Color(0xFFE9EEF8),
                onSurfaceVariant = Color(0xFF65738D),
                outline = Color(0xFFDCE3F0)
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
    }
}
