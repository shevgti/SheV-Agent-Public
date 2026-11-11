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
        existingAgentSummaries: List<String> = emptyList()
    ): AgentCreationResult {
        if (userRequest.isBlank() || userRequest.length > AgentCreatorPromptBuilder.MAX_REQUEST_CHARS) {
            return AgentCreationResult.Rejected("INVALID_REQUEST", "Запрос пустой или превышает допустимый размер.")
        }
        val prompt = AgentCreatorPromptBuilder.build(userRequest, knownToolIds, existingAgentSummaries)
        val output = try {
            generate(prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return AgentCreationResult.ModelFailure(failure.message ?: "Ошибка локальной модели.")
        }
        val parsed = try {
            AgentManifestParser.parse(output)
        } catch (failure: Exception) {
            return AgentCreationResult.Rejected("INVALID_MODEL_OUTPUT", failure.message)
        }
        if (parsed.clarifyingQuestions.isNotEmpty()) {
            val questions = parsed.clarifyingQuestions.filter { !it.isNullOrBlank() }.take(3)
            if (questions.isEmpty()) return AgentCreationResult.Rejected("INVALID_CLARIFICATION")
            return AgentCreationResult.NeedsClarification(questions)
        }
        // Model output can never approve or activate itself.
        val draft = parsed.copy(status = AgentManifest.STATUS_DRAFT, version = 1)
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
