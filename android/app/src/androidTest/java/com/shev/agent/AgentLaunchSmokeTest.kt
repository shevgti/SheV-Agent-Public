package com.shev.agent

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class AgentLaunchSmokeTest {
    @Test
    fun mainActivityLaunchesAndCapturesActualHomeScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("com.shev.agent", activity.packageName)
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()

            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull("The Android emulator must return a real screen capture.", screenshot)
            val outputDirectory = instrumentation.targetContext.getExternalFilesDir(null)
            requireNotNull(outputDirectory) { "App-specific external files directory is unavailable." }
            val screenshotFile = File(outputDirectory, "shev-agent-home.png")
            FileOutputStream(screenshotFile).use { stream ->
                assertTrue(
                    "The screenshot must be encoded as PNG.",
                    screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, stream)
                )
            }
            assertTrue("The captured home screen must not be empty.", screenshotFile.length() > 0L)
        }
    }
}
