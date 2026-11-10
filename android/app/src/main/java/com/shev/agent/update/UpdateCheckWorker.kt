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
import com.shev.agent.MainActivity
import com.shev.agent.R
import com.shev.agent.BuildConfig
import java.util.concurrent.TimeUnit

internal class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val update = AppUpdater.check(BuildConfig.VERSION_CODE) ?: return Result.success()
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return Result.success()
            if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return Result.success()

            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_NOTIFIED, 0) >= update.versionCode) return Result.success()

            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Обновления SheV Agent", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val launch = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                applicationContext, 8102, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Доступно обновление SheV Agent")
                .setContentText("Новая версия ${update.versionName}. Нажми, чтобы открыть чат и установить её.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Новая версия SheV Agent ${update.versionName} доступна. Открой приложение, чтобы скачать и установить обновление."))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
        private const val CHANNEL = "shev_agent_updates"
        private const val NOTIFICATION_ID = 8102
        private const val PERIODIC = "shev_agent_update_check"
        private const val IMMEDIATE = "shev_agent_update_check_now"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            manager.enqueueUniqueWork(
                IMMEDIATE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UpdateCheckWorker>().setConstraints(constraints).build()
            )
            manager.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints).build()
            )
        }
    }
}
