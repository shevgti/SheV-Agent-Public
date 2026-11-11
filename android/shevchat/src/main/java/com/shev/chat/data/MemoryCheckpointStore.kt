package com.shev.chat.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class MemorySnapshot(
    val project: String,
    val activeCheckpoint: String,
    val projectMemory: String,
    val syncedAtEpochMillis: Long
)

class MemoryCheckpointStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "shev_memory_cache",
        Context.MODE_PRIVATE
    )

    fun readCached(project: String): MemorySnapshot? {
        val key = keyFor(project)
        val active = prefs.getString("${key}_active", null) ?: return null
        val projectMemory = prefs.getString("${key}_project", null) ?: return null
        val syncedAt = prefs.getLong("${key}_synced_at", 0L)
        if (syncedAt <= 0L) return null
        return MemorySnapshot(project, active, projectMemory, syncedAt)
    }

    suspend fun refresh(project: String): MemorySnapshot = withContext(Dispatchers.IO) {
        val projectPath = projectPath(project)
        val active = fetchText("ACTIVE-CHECKPOINT.md")
        val projectMemory = fetchText(projectPath)
        if (!active.contains("ACTIVE TASK") && !active.contains("ACTIVE-CHECKPOINT")) {
            throw IOException("Ответ SheV-Memory не похож на актуальный checkpoint.")
        }
        if (projectMemory.isBlank()) throw IOException("Паспорт выбранного проекта пуст.")
        val now = System.currentTimeMillis()
        val snapshot = MemorySnapshot(project, active, projectMemory, now)
        val key = keyFor(project)
        val saved = prefs.edit()
            .putString("${key}_active", active)
            .putString("${key}_project", projectMemory)
            .putLong("${key}_synced_at", now)
            .commit()
        if (!saved) throw IOException("Не удалось сохранить локальный кэш SheV-Memory.")
        snapshot
    }

    private fun fetchText(path: String): String {
        val url = URL("https://raw.githubusercontent.com/shevgti/SheV-Memory/main/$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "SheV-Chat-Android")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("SheV-Memory вернул HTTP $code для $path.")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_FILE_BYTES) {
                        throw IOException("Файл SheV-Memory превышает безопасный лимит.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(StandardCharsets.UTF_8.name()).trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun projectPath(project: String): String = when (project) {
        "ФотоПорядок" -> "projects/Fotoporyadok/PROJECT_MEMORY.md"
        "SheV Agent" -> "projects/SheV-Agent/README.md"
        "Моя память" -> "projects/MoyaPamyat/PROJECT_MEMORY.md"
        else -> throw IllegalArgumentException("Неизвестный проект: $project")
    }

    private fun keyFor(project: String): String = when (project) {
        "ФотоПорядок" -> "fotoporyadok"
        "SheV Agent" -> "shev_agent"
        "Моя память" -> "moya_pamyat"
        else -> throw IllegalArgumentException("Неизвестный проект: $project")
    }

    companion object {
        private const val MAX_FILE_BYTES = 256 * 1024
    }
}
