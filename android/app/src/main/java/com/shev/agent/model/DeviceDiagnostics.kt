package com.shev.agent.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.util.Locale

/** Read-only device facts used to explain model/backend compatibility before download. */
object DeviceDiagnostics {
    fun summary(context: Context): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: Build.HARDWARE
        } else {
            Build.HARDWARE
        }
        val storage = StatFs(context.filesDir.absolutePath).availableBytes
        val totalRamGb = memory.totalMem / BYTES_PER_GB
        val availableRamGb = memory.availMem / BYTES_PER_GB
        val freeStorageGb = storage / BYTES_PER_GB
        return "Устройство: ${Build.MODEL} · SoC: $soc · RAM ОС: ${format(totalRamGb)} ГБ всего / ${format(availableRamGb)} ГБ свободно · место приложения: ${format(freeStorageGb)} ГБ"
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)

    private const val BYTES_PER_GB = 1_073_741_824.0
}
