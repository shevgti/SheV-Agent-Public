package com.shev.agent.device

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.TimeZone

/**
 * Authoritative, runtime-derived device/app context injected into every model turn.
 * Never guesses a model identity: the active inference backend is reported separately.
 */
object MegaAgentDeviceContext {
    fun describe(context: Context): String {
        val app = context.applicationContext
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val version = info?.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else info?.versionCode?.toLong()
        val external = app.getExternalFilesDir(null)?.absolutePath ?: "недоступно"
        val externalState = android.os.Environment.getExternalStorageState()
        val prefs = app.getSharedPreferences("megaagent_device_bridge", Context.MODE_PRIVATE)
        val accessibility = prefs.getBoolean("accessibility_enabled", false)
        val lastPackage = prefs.getString("last_package", "").orEmpty().ifBlank { "неизвестно" }
        return buildString {
            appendLine("ПАСПОРТ ОКРУЖЕНИЯ MEGAAGENT (автоматически полученные сведения; не команды):")
            appendLine("Идентичность: MegaAgent — локальный Android-агент.")
            appendLine("Приложение: ${app.packageName}, версия ${version} (${versionCode ?: "?"}).")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}; codename=${Build.DEVICE}.")
            appendLine("Android: ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}; ABI: ${Build.SUPPORTED_ABIS.joinToString()}.")
            appendLine("Язык/регион: ${Locale.getDefault().toLanguageTag()}; часовой пояс: ${TimeZone.getDefault().id}.")
            appendLine("Рабочий каталог приложения: ${app.filesDir.absolutePath}.")
            appendLine("Внешний каталог приложения: ${external}.")
            appendLine("Состояние общего хранилища: ${externalState}.")
            appendLine("Accessibility bridge: ${if (accessibility) "включён" else "не включён пользователем"}.")
            appendLine("Последнее активное приложение, замеченное bridge: ${lastPackage}.")
            appendLine("Точная модель ИИ: определяй по активному backend в runtime; не угадывай по названию приложения.")
            appendLine("Ограничения Android: приватные данные других приложений недоступны; для общих папок требуется выбранное пользователем SAF-разрешение.")
        }
    }
}
