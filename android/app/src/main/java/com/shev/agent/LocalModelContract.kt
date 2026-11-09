package com.shev.agent

/**
 * Contract between Agent Core and an on-device language-model runtime.
 *
 * This file deliberately contains no inference engine. A runtime adapter must be
 * added and verified before the app can report that a model is ready.
 */
enum class LocalModelState {
    NOT_CONFIGURED,
    DOWNLOADING,
    INITIALIZING,
    READY,
    UNAVAILABLE,
    FAILED
}

data class LocalModelStatus(
    val state: LocalModelState,
    val modelId: String? = null,
    val detail: String? = null,
    val runsOnDevice: Boolean = false
)

data class ModelGenerationRequest(
    val prompt: String,
    val maxOutputTokens: Int = 512
)

sealed class ModelGenerationResult {
    data class Success(val text: String, val modelId: String) : ModelGenerationResult()
    data class Unavailable(val reason: String) : ModelGenerationResult()
    data class Failure(val reason: String) : ModelGenerationResult()
}

/** Implemented by a concrete LiteRT-LM (or other approved local) adapter. */
interface LocalLanguageModel {
    suspend fun status(): LocalModelStatus
    suspend fun generate(request: ModelGenerationRequest): ModelGenerationResult
}

/**
 * Fail-closed policy for local inference. Cloud-backed or unidentified runtimes
 * are never treated as the device's local model.
 */
object LocalModelPolicy {
    fun canGenerate(status: LocalModelStatus): Boolean =
        status.state == LocalModelState.READY &&
            status.runsOnDevice &&
            !status.modelId.isNullOrBlank()

    fun unavailableReason(status: LocalModelStatus): String {
        if (canGenerate(status)) return ""
        return when (status.state) {
            LocalModelState.NOT_CONFIGURED -> "Локальная модель ещё не настроена."
            LocalModelState.DOWNLOADING -> "Модель ещё загружается."
            LocalModelState.INITIALIZING -> "Локальная модель запускается."
            LocalModelState.READY -> when {
                !status.runsOnDevice -> "Облачная модель не считается локальным режимом."
                status.modelId.isNullOrBlank() -> "Не удалось определить модель."
                else -> "Локальная модель пока недоступна."
            }
            LocalModelState.UNAVAILABLE -> status.detail?.takeIf { it.isNotBlank() }
                ?: "Локальная модель недоступна на этом устройстве."
            LocalModelState.FAILED -> status.detail?.takeIf { it.isNotBlank() }
                ?: "Не удалось запустить локальную модель."
        }
    }
}
