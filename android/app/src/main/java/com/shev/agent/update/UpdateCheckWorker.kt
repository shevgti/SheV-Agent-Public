package com.shev.agent.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shev.agent.BuildConfig
import com.shev.agent.MainActivity
import java.util.concurrent.TimeUnit

internal class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val update = AppUpdater.check(BuildConfig.VERSION_CODE) ?: return Result.success()

            // Important: download happens BEFORE notification-permission checks.
            // Even with notifications disabled, the verified APK is prepared in the background.
            AppUpdater.downloadToCache(applicationContext, update)

            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return Result.success()
            if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
                return Result.success()
            }

            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_NOTIFIED, 0) >= update.versionCode) return Result.success()

            ensureNotificationChannel(applicationContext)
            val installIntent = Intent(applicationContext, MainActivity::class.java).apply {
                action = MainActivity.ACTION_INSTALL_DOWNLOADED_UPDATE
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                applicationContext,
                NOTIFICATION_ID,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Обновление SheV Agent скачано")
                .setContentText("Версия ${update.versionName} готова к установке. Нажми, чтобы продолжить.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "SheV Agent автоматически скачал версию ${update.versionName} и проверил её контрольную сумму. Нажми, чтобы открыть системную установку Android."
                    )
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
            prefs.edit().putInt(KEY_NOTIFIED, update.versionCode).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PREFS = "shev_update_notifications"
        private const val KEY_NOTIFIED = "notified_version"
        private const val CHANNEL = "shev_agent_updates_v3"
        private const val NOTIFICATION_ID = 8102
        private const val PERIODIC = "shev_agent_update_check"
        private const val IMMEDIATE = "shev_agent_update_check_now"

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL,
                "Обновления SheV Agent",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о скачанных и готовых к установке обновлениях"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(channel)
        }

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            manager.enqueueUniqueWork(
                IMMEDIATE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                    .setConstraints(constraints)
                    .build()
            )
            manager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()
            )
        }
    }
}
