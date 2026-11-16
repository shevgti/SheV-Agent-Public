package com.shev.chat

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatLaunchSmokeTest {
    @Test
    fun chatActivityLaunchesAndShowsItsWindow() {
        ActivityScenario.launch(ChatActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("com.shev.chat", activity.packageName)
                assertTrue("SheV Chat window should be visible", activity.window.decorView.isShown)
            }
        }
    }
}
