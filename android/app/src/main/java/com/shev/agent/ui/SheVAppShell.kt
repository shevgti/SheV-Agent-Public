package com.shev.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shev.agent.model.LocalModelViewModel
import com.shev.agent.browser.SheVBrowserScreen
import com.shev.agent.model.LocalModelPanel

private enum class SheVPrimaryDestination { CHAT, BROWSER }

/** Chat-first shell with a first-class embedded browser destination. */
@Composable
fun SheVAppShell(chatViewModel: LocalModelViewModel) {
    var destination by remember { mutableStateOf(SheVPrimaryDestination.CHAT) }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { destination = SheVPrimaryDestination.CHAT },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (destination == SheVPrimaryDestination.CHAT) "● Чат" else "Чат")
                }
                OutlinedButton(
                    onClick = { destination = SheVPrimaryDestination.BROWSER },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (destination == SheVPrimaryDestination.BROWSER) "● Браузер" else "Браузер")
                }
            }
        }
        when (destination) {
            SheVPrimaryDestination.CHAT -> LocalModelPanel(chatViewModel)
            SheVPrimaryDestination.BROWSER -> SheVBrowserScreen()
        }
    }
}
