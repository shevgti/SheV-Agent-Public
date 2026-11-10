package com.shev.agent.model

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.shev.agent.AgentCapability
import com.shev.agent.BuildConfig
import com.shev.agent.tools.AgentToolDescriptor

data class AppPermissionSnapshot(
    val permission: String,
    val title: String,
    val granted: Boolean,
    val status: String
)

data class AppToolSnapshot(
    val id: String,
    val title: String,
    val description: String,
    val capabilities: List<String>
)

data class AgentAppContextSnapshot(
    val appName: String,
    val packageName: String,
    val appVersion: String,
    val versionCode: Int,
    val localModelVerified: Boolean,
    val runtimeReady: Boolean,
    val activeBackend: String,
    val omniRouteEnabled: Boolean,
    val omniRouteConfigured: Boolean,
    val omniRouteModel: String?,
    val grantedCapabilities: List<String>,
    val registeredTools: List<AppToolSnapshot>,
    val androidPermissions: List<AppPermissionSnapshot>,
    val securityNote: String
)

/**
 * Builds factual, live app context for the model. Secrets, API tokens, and
 * encrypted credential values are never included in this snapshot.
 */
object AgentAppContextReader {
    fun read(
        context: Context,
        registeredTools: List<AgentToolDescriptor>,
        localModelVerified: Boolean,
        runtimeReady: Boolean,
        backendName: String?,
        grantedCapabilities: Set<AgentCapability>
    ): String {
        val app = context.applicationContext
        val packageManager = app.packageManager
        val appInfo = app.applicationInfo
        val preferences = app.getSharedPreferences("shev_omniroute", Context.MODE_PRIVATE)
        val omniRouteConfigured = !preferences.getString("encrypted_token", null).isNullOrBlank()
        val omniRouteEnabled = preferences.getBoolean("use_for_primary_chat", false)
        val permissions = requestedPermissions(app).map { permission ->
            val granted = isPermissionGranted(app, permission)
            AppPermissionSnapshot(
                permission = permission,
                title = permissionTitle(permission),
                granted = granted,
                status = if (granted) "разрешено" else "не разрешено"
            )
        }
        val snapshot = AgentAppContextSnapshot(
            appName = appInfo.loadLabel(packageManager).toString(),
            packageName = app.packageName,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            localModelVerified = localModelVerified,
            runtimeReady = runtimeReady,
            activeBackend = backendName ?: if (runtimeReady) "неизвестный backend" else "не запущен",
            omniRouteEnabled = omniRouteEnabled,
            omniRouteConfigured = omniRouteConfigured,
            omniRouteModel = preferences.getString("model_id", null)?.takeIf { omniRouteConfigured },
            grantedCapabilities = grantedCapabilities.map { it.name }.sorted(),
            registeredTools = registeredTools.map { descriptor ->
                AppToolSnapshot(
                    id = descriptor.id,
                    title = descriptor.title,
                    description = descriptor.description,
                    capabilities = descriptor.capabilities.map { it.name }.sorted()
                )
            },
            androidPermissions = permissions,
            securityNote = "Наличие разрешения не означает автоматического доступа модели. Все действия выполняются только зарегистрированными инструментами через локальную CapabilityPolicy и AgentToolExecutor."
        )
        return Gson().toJson(snapshot)
    }

    @Suppress("DEPRECATION")
    private fun requestedPermissions(context: Context): List<String> =
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().distinct()

    private fun isPermissionGranted(context: Context, permission: String): Boolean {
        if (permission == Manifest.permission.POST_NOTIFICATIONS &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) return true
        if (permission == Manifest.permission.REQUEST_INSTALL_PACKAGES) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                context.packageManager.canRequestPackageInstalls()
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun permissionTitle(permission: String): String = when (permission) {
        Manifest.permission.INTERNET -> "Доступ к интернету"
        Manifest.permission.RECORD_AUDIO -> "Микрофон"
        Manifest.permission.POST_NOTIFICATIONS -> "Уведомления"
        Manifest.permission.REQUEST_INSTALL_PACKAGES -> "Установка обновлений APK"
        else -> permission.substringAfterLast('.')
    }
}
