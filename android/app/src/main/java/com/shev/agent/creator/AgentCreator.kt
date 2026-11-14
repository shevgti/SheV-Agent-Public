package com.shev.agent.creator

import kotlinx.coroutines.CancellationException

sealed class AgentCreationResult {
    data class Created(val manifest: AgentManifest) : AgentCreationResult()
    data class Rejected(val reason: String, val detail: String? = null) : AgentCreationResult()
    data class NeedsClarification(val questions: List<String>) : AgentCreationResult()
    data class ModelFailure(val message: String) : AgentCreationResult()
}

/** Orchestrates discovery context, model design, parsing and policy validation. */
class AgentCreator {
    suspend fun createDraft(
        userRequest: String,
        knownToolIds: Set<String>,
        existingAgentIds: Set<String>,
        generate: suspend (String) -> String,
        existingAgentSummaries: List<String> = emptyList(),
        forceComplete: Boolean = false
    ): AgentCreationResult {
        if (userRequest.isBlank() || userRequest.length > AgentCreatorPromptBuilder.MAX_REQUEST_CHARS) {
            return AgentCreationResult.Rejected("INVALID_REQUEST", "Запрос пустой или превышает допустимый размер.")
        }
        val prompt = AgentCreatorPromptBuilder.build(userRequest, knownToolIds, existingAgentSummaries, forceComplete)
        val output = try {
            generate(prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return AgentCreationResult.ModelFailure(failure.message ?: "Ошибка локальной модели.")
        }
        val parsed = try {
            AgentManifestParser.parse(output)
        } catch (firstFailure: Exception) {
            // Do not ask the small model to repair a potentially huge/truncated JSON blob.
            // Start a fresh session with the compact manifest contract and force safe defaults.
            val repairPrompt = buildString {
                appendLine("Предыдущая генерация оборвалась или вернула некорректный JSON.")
                appendLine("Повтори создание с нуля. Верни короткий полный JSON в точном формате ниже.")
                appendLine("Не продолжай старый текст и не цитируй его. Не добавляй пояснений.")
                appendLine("Причина первой ошибки: ${firstFailure.message ?: "неизвестная ошибка"}")
                appendLine()
                append(AgentCreatorPromptBuilder.build(userRequest, knownToolIds, existingAgentSummaries, forceComplete = true))
            }
            val repaired = try {
                generate(repairPrompt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (repairFailure: Exception) {
                return AgentCreationResult.Rejected(
                    "INVALID_MODEL_OUTPUT",
                    "Первый ответ модели не удалось разобрать; компактная повторная генерация не удалась: ${repairFailure.message ?: firstFailure.message}"
                )
            }
            try {
                AgentManifestParser.parse(repaired)
            } catch (repairFailure: Exception) {
                val detail = repairFailure.message ?: firstFailure.message.orEmpty()
                val isTruncated = detail.contains("End of input", ignoreCase = true) ||
                    detail.contains("EOF", ignoreCase = true)
                return AgentCreationResult.Rejected(
                    "INVALID_MODEL_OUTPUT",
                    if (isTruncated) "Модель дважды оборвала JSON до завершения. Ответ слишком длинный для текущего лимита генерации; требуется увеличить лимит выходных токенов или использовать более компактный профиль."
                    else "Модель дважды вернула некорректный JSON. Детали: $detail"
                )
            }
        }
        if (!forceComplete && parsed.clarifyingQuestions.isNotEmpty()) {
            val questions = parsed.clarifyingQuestions.filter { !it.isNullOrBlank() }.take(3)
            if (questions.isEmpty()) return AgentCreationResult.Rejected("INVALID_CLARIFICATION")
            return AgentCreationResult.NeedsClarification(questions)
        }
        val safeExamples = parsed.triggeringExamples.takeIf { examples ->
            examples.size in 2..4 && examples.all {
                it.context.isNotBlank() && it.user.isNotBlank() && it.assistant.isNotBlank() &&
                    it.commentary.isNotBlank() && it.invocation.isNotBlank()
            }
        } ?: parsed.triggers.take(2).map { trigger ->
            AgentTriggerExample(
                context = "Пользователь обратился с задачей: $trigger",
                user = trigger,
                assistant = "Я выполню эту задачу в пределах своих инструкций и доступных возможностей.",
                commentary = "Запрос соответствует сценарию специалиста: $trigger",
                invocation = "Использую специалиста ${parsed.name} для выполнения задачи."
            )
        }
        val safeManifest = parsed.copy(
            clarifyingQuestions = emptyList(),
            description = "",
            triggeringExamples = safeExamples
        )
        val draft = safeManifest.copy(status = AgentManifest.STATUS_DRAFT, version = 1)
        val validation = try {
            AgentManifestValidator(knownToolIds).validate(draft, existingAgentIds)
        } catch (failure: Exception) {
            return AgentCreationResult.Rejected("INVALID_MANIFEST", failure.message)
        }
        if (!validation.accepted) {
            return AgentCreationResult.Rejected(validation.rejection?.name ?: "VALIDATION_FAILED", validation.detail)
        }
        return AgentCreationResult.Created(draft)
    }
}
