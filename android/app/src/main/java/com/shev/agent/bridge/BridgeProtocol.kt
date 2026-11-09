package com.shev.agent.bridge

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.util.UUID

/** Import/export contract. This class validates proposals; it never executes tools. */
data class BridgeTask(
    val protocol: String,
    val version: Int,
    @SerializedName("request_id") val requestId: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("expires_at") val expiresAt: String,
    val intent: String,
    val tool: BridgeToolCall,
    @SerializedName("requested_capabilities") val requestedCapabilities: List<String> = emptyList(),
    val risk: String,
    @SerializedName("expected_result") val expectedResult: String = ""
)

data class BridgeToolCall(
    val id: String,
    @SerializedName("schema_version") val schemaVersion: Int,
    val arguments: Map<String, Any?> = emptyMap()
)

data class BridgeTaskResult(
    val protocol: String = "shev-agent.result",
    val version: Int = BridgeProtocol.VERSION,
    @SerializedName("request_id") val requestId: String,
    val status: String,
    @SerializedName("tool_id") val toolId: String,
    val summary: String,
    val evidence: List<String> = emptyList(),
    @SerializedName("audit_event_id") val auditEventId: String,
    @SerializedName("finished_at") val finishedAt: String = Instant.now().toString()
)

enum class BridgeTaskStatus {
    ACCEPTED, EXPIRED, INVALID, UNSUPPORTED_VERSION, DUPLICATE,
    APPROVAL_REQUIRED, DENIED
}

data class BridgeValidation(
    val status: BridgeTaskStatus,
    val task: BridgeTask? = null,
    val reason: String? = null
)

object BridgeProtocol {
    const val VERSION = 1
    const val MAX_TASK_CHARS = 32_768
    const val MAX_RESULT_CHARS = 65_536
    private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
    private const val MAX_TTL_MS = 24 * 60 * 60 * 1000L
    private const val PROTOCOL_NAME = "shev-agent.task"
    private val gson = Gson()

    /** Initial Stage 0 allow-list. Tool schemas and scopes remain local authority. */
    val readOnlyTools = setOf(
        "agent.health.read",
        "projects.list",
        "project.metadata.read",
        "memory.search"
    )

    /** Not executable in Stage 0; these names are reserved for future draft-only handlers. */
    val draftOnlyTools = setOf("project.patch.propose", "memory.note.propose")

    fun parseAndValidate(
        rawJson: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        alreadySeenRequestId: (String) -> Boolean = { false }
    ): BridgeValidation {
        if (rawJson.isBlank() || rawJson.length > MAX_TASK_CHARS) {
            return BridgeValidation(BridgeTaskStatus.INVALID, reason = "Пустое или слишком большое задание.")
        }
        val task = try {
            gson.fromJson(rawJson, BridgeTask::class.java)
        } catch (_: JsonParseException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return BridgeValidation(BridgeTaskStatus.INVALID, reason = "Некорректный JSON.")

        if (task.protocol != PROTOCOL_NAME || task.version != VERSION) {
            return BridgeValidation(BridgeTaskStatus.UNSUPPORTED_VERSION, reason = "Неизвестный протокол или версия.")
        }
        if (runCatching { UUID.fromString(task.requestId) }.isFailure ||
            task.intent.isBlank() || task.intent.length > 2_000 ||
            task.tool.id.isBlank() || task.tool.id.length > 100 ||
            task.tool.schemaVersion != 1
        ) {
            return BridgeValidation(BridgeTaskStatus.INVALID, reason = "Некорректные обязательные поля.")
        }
        val created = runCatching { Instant.parse(task.createdAt).toEpochMilli() }.getOrNull()
        val expires = runCatching { Instant.parse(task.expiresAt).toEpochMilli() }.getOrNull()
        if (created == null || expires == null || created > nowEpochMs + MAX_CLOCK_SKEW_MS ||
            expires <= nowEpochMs || expires <= created || expires - created > MAX_TTL_MS
        ) {
            return BridgeValidation(BridgeTaskStatus.EXPIRED, reason = "Срок действия задания истёк или задан неверно.")
        }
        if (alreadySeenRequestId(task.requestId)) {
            return BridgeValidation(BridgeTaskStatus.DUPLICATE, reason = "Это задание уже принималось.")
        }
        if (task.tool.id !in readOnlyTools && task.tool.id !in draftOnlyTools) {
            return BridgeValidation(BridgeTaskStatus.INVALID, reason = "Инструмент не входит в локальный белый список.")
        }
        if (task.tool.id in draftOnlyTools) {
            return BridgeValidation(BridgeTaskStatus.APPROVAL_REQUIRED, task, "Черновые изменения требуют отдельного экрана проверки.")
        }
        return BridgeValidation(BridgeTaskStatus.ACCEPTED, task)
    }

    fun encodeResult(result: BridgeTaskResult): String {
        val encoded = gson.toJson(result)
        require(encoded.length <= MAX_RESULT_CHARS) { "Результат превышает лимит протокола." }
        return encoded
    }
}
