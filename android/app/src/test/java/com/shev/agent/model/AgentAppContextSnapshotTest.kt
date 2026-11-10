package com.shev.agent.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAppContextSnapshotTest {
    @Test fun serializesOnlyExplicitlyProvidedAppFacts() {
        val snapshot = AgentAppContextSnapshot(
            appName = "SheV Agent",
            packageName = "com.shev.agent",
            appVersion = "0.2.7",
            versionCode = 18,
            localModelVerified = true,
            runtimeReady = true,
            selectedPrimaryBackend = "Локальная модель",
            localRuntimeBackend = "GPU",
            deviceSummary = "Устройство: тестовое",
            omniRouteEnabled = false,
            omniRouteConfigured = true,
            omniRouteModel = "auto",
            grantedCapabilities = listOf("ANALYZE_DATA"),
            registeredTools = listOf(
                AppToolSnapshot(
                    "agent.current_local_time",
                    "Текущее местное время",
                    "Возвращает фактическое время устройства",
                    listOf("ANALYZE_DATA")
                )
            ),
            androidPermissions = listOf(
                AppPermissionSnapshot("android.permission.RECORD_AUDIO", "Микрофон", true, "разрешено")
            ),
            securityNote = "Вызовы проходят через локальную политику."
        )
        val json = Gson().toJson(snapshot)
        assertTrue(json.contains("\"appName\":\"SheV Agent\""))
        assertTrue(json.contains("\"runtimeReady\":true"))
        assertTrue(json.contains("\"selectedPrimaryBackend\":\"Локальная модель\""))
        assertTrue(json.contains("\"localRuntimeBackend\":\"GPU\""))
        assertTrue(json.contains("\"deviceSummary\":\"Устройство: тестовое\""))
        assertTrue(json.contains("agent.current_local_time"))
        assertTrue(json.contains("android.permission.RECORD_AUDIO"))
        assertFalse(json.contains("apiToken"))
        assertFalse(json.contains("encrypted_token"))
    }

    @Test fun keepsMissingPermissionDistinctFromGrantedPermission() {
        val granted = AppPermissionSnapshot("record_audio", "Микрофон", true, "разрешено")
        val missing = AppPermissionSnapshot("post_notifications", "Уведомления", false, "не разрешено")
        assertTrue(granted.granted)
        assertFalse(missing.granted)
        assertEquals("не разрешено", missing.status)
    }
}
