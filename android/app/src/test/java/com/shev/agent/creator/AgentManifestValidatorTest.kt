package com.shev.agent.creator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentManifestValidatorTest {
    private fun validManifest() = AgentManifest(
        id = "android-researcher",
        name = "Исследователь Android",
        purpose = "Сравнивать Android-библиотеки по совместимости и лицензиям",
        role = "Технический исследователь",
        instructions = "Проверяй официальные источники и фиксируй доказательства.",
        process = "Определи версии, проверь официальные документы, сравни ограничения и зафиксируй вывод.",
        outputFormat = "Таблица сравнения с версией, лицензией, совместимостью и источниками.",
        triggers = listOf("Сравни библиотеки", "Проверь лицензию"),
        inputSchema = """{"type":"object","properties":{},"required":[]}""",
        outputSchema = """{"type":"object","properties":{},"required":[]}""",
        allowedToolIds = listOf("agent.inspect_tools"),
        memoryReadScopes = emptyList(),
        memoryWriteScopes = emptyList(),
        maxSteps = 8, timeoutSeconds = 120, maxModelCalls = 4,
        networkPolicy = "OFFLINE", sideEffectPolicy = "DRAFT_ONLY",
        verificationStrategy = "Повторно проверь ссылки и версии.",
        evaluationCases = listOf("Обычный запрос", "Неоднозначная версия"),
        limitations = listOf("Нет сетевого доступа по умолчанию"),
        description = "Use this agent when Android research is needed. Typical triggers include library review and license checks. See \"When to invoke\" in the agent body for worked scenarios.",
        triggeringExamples = listOf(
            AgentTriggerExample("Нужно сравнить библиотеки", "Сравни библиотеки", "Проверю совместимость и лицензии.", "Нужен технический исследователь.", "Я использую агента android-researcher."),
            AgentTriggerExample("Перед внедрением", "Проверь лицензию", "Сверю официальный источник.", "Требуется проверка лицензии.", "Я использую агента android-researcher.")
        )
    )

    @Test fun acceptsValidManifestWithinAllowlist() {
        assertTrue(AgentManifestValidator(setOf("agent.inspect_tools")).validate(validManifest()).accepted)
    }
    @Test fun rejectsToolOutsideAllowlist() {
        assertFalse(AgentManifestValidator(setOf("agent.inspect_tools"))
            .validate(validManifest().copy(allowedToolIds = listOf("arbitrary.shell"))).accepted)
    }
    @Test fun rejectsDuplicateAgentId() {
        val result = AgentManifestValidator(setOf("agent.inspect_tools"))
            .validate(validManifest(), setOf("android-researcher"))
        assertFalse(result.accepted)
        assertTrue(result.rejection == AgentManifestRejection.INVALID_ID)
    }
    @Test fun rejectsUnsafeExecutionPolicy() {
        assertFalse(AgentManifestValidator(setOf("agent.inspect_tools"))
            .validate(validManifest().copy(sideEffectPolicy = "EXECUTE")).accepted)
    }
    @Test fun rejectsTooFewTriggers() {
        assertFalse(AgentManifestValidator(setOf("agent.inspect_tools"))
            .validate(validManifest().copy(triggers = listOf("Один триггер"))).accepted)
    }
    @Test fun parserAcceptsNativeSchemaObjectsWithoutNestedJsonEscaping() {
        val output = """
            {
              "id":"weather-researcher",
              "name":"Исследователь погоды",
              "purpose":"Сравнивать прогнозы",
              "role":"Метеоаналитик",
              "instructions":"Проверяй источники.",
              "triggers":["Погода в городах","Сравни прогнозы"],
              "inputSchema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]},
              "outputSchema":{"type":"object","properties":{"forecast":{"type":"string"}},"required":["forecast"]},
              "allowedToolIds":[],
              "memoryReadScopes":[],
              "memoryWriteScopes":[],
              "maxSteps":4,
              "timeoutSeconds":60,
              "maxModelCalls":2,
              "networkPolicy":"OFFLINE",
              "sideEffectPolicy":"DRAFT_ONLY",
              "verificationStrategy":"Проверь поля.",
              "evaluationCases":["Обычный","Пустой город"],
              "limitations":["Нет сетевого доступа"]
            }
        """.trimIndent()
        val parsed = AgentManifestParser.parse(output)
        assertTrue(parsed.inputSchema.contains("city"))
        assertTrue(parsed.outputSchema.contains("forecast"))
        assertTrue(com.google.gson.JsonParser.parseString(parsed.inputSchema).isJsonObject)
        assertTrue(com.google.gson.JsonParser.parseString(parsed.outputSchema).isJsonObject)
    }

    @Test fun parserRepairsInvalidBackslashEscapeInModelText() {
        val valid = com.google.gson.Gson().toJson(validManifest())
        val malformed = valid.replace("Проверяй официальные", "Проверяй \\d+ официальные")
        val parsed = AgentManifestParser.parse(malformed)
        assertTrue(parsed.instructions.contains("\\d+"))
    }

    @Test fun parserExtractsJsonFromModelCodeFence() {
        val manifest = validManifest()
        val json = com.google.gson.Gson().toJson(manifest)
        val parsed = AgentManifestParser.parse("""Ответ:
```json
$json
```""")
        assertTrue(parsed.id == manifest.id)
        assertTrue(parsed.allowedToolIds == manifest.allowedToolIds)
    }

    @Test fun creatorForcesDraftEvenIfModelRequestsActivation() = runBlocking {
        val requestedActive = com.google.gson.Gson().toJson(validManifest().copy(status = AgentManifest.STATUS_ACTIVE))
        val result = AgentCreator().createDraft(
            userRequest = "Создай исследователя",
            knownToolIds = setOf("agent.inspect_tools"),
            existingAgentIds = emptySet(),
            generate = { requestedActive }
        )
        assertTrue(result is AgentCreationResult.Created)
        assertEquals(AgentManifest.STATUS_DRAFT, (result as AgentCreationResult.Created).manifest.status)
    }

    @Test fun creatorReturnsClarifyingQuestionsBeforeCreatingDraft() = runBlocking {
        val output = com.google.gson.Gson().toJson(
            validManifest().copy(clarifyingQuestions = listOf("Какой период отчёта нужен?"))
        )
        val result = AgentCreator().createDraft(
            userRequest = "Создай финансового агента",
            knownToolIds = setOf("agent.inspect_tools"),
            existingAgentIds = emptySet(),
            generate = { output }
        )
        assertTrue(result is AgentCreationResult.NeedsClarification)
        assertEquals(
            listOf("Какой период отчёта нужен?"),
            (result as AgentCreationResult.NeedsClarification).questions
        )
    }

    @Test fun creatorDoesNotAskAgainAfterClarificationRound() = runBlocking {
        val output = com.google.gson.Gson().toJson(
            validManifest().copy(clarifyingQuestions = listOf("Какой формат отчёта нужен?"))
        )
        var calls = 0
        var receivedPrompt = ""
        val result = AgentCreator().createDraft(
            userRequest = "Создай исследователя информации. Ответы на уточнения: можно выбрать самостоятельно.",
            knownToolIds = setOf("agent.inspect_tools"),
            existingAgentIds = emptySet(),
            generate = { prompt -> calls += 1; receivedPrompt = prompt; output },
            forceComplete = true
        )
        assertEquals(1, calls)
        assertTrue(receivedPrompt.contains("Не задавай вопросов."))
        assertTrue(result is AgentCreationResult.Created)
        assertTrue((result as AgentCreationResult.Created).manifest.clarifyingQuestions.isEmpty())
    }

    @Test fun finalCreatorPromptRequiresDefaultsInsteadOfMoreQuestions() {
        val prompt = AgentCreatorPromptBuilder.build(
            "Создай специалиста",
            emptySet(),
            forceComplete = true
        )
        assertTrue(prompt.contains("Не задавай вопросов."))
        assertTrue(prompt.contains("безопасные значения по умолчанию"))
        assertTrue(prompt.contains("\"clarifyingQuestions\":[]"))
    }

    @Test fun creatorIncludesExistingAgentInventory() = runBlocking {
        var receivedPrompt = ""
        val output = com.google.gson.Gson().toJson(validManifest())
        AgentCreator().createDraft(
            userRequest = "Создай другого специалиста",
            knownToolIds = setOf("agent.inspect_tools"),
            existingAgentIds = emptySet(),
            generate = { receivedPrompt = it; output },
            existingAgentSummaries = listOf("android-researcher: Исследователь Android")
        )
        assertTrue(receivedPrompt.contains("Исследователь Android"))
    }

    @Test fun rejectsMissingAnthropicWorkedExamples() {
        val result = AgentManifestValidator(setOf("agent.inspect_tools"))
            .validate(validManifest().copy(triggeringExamples = emptyList()))
        assertFalse(result.accepted)
        assertEquals(AgentManifestRejection.INVALID_EXAMPLES, result.rejection)
    }

    @Test fun rejectsInvalidAnthropicModelAndColor() {
        val validator = AgentManifestValidator(setOf("agent.inspect_tools"))
        assertEquals(AgentManifestRejection.INVALID_MODEL,
            validator.validate(validManifest().copy(model = "gpt-unknown")).rejection)
        assertEquals(AgentManifestRejection.INVALID_COLOR,
            validator.validate(validManifest().copy(color = "purple")).rejection)
    }

    @Test fun promptContainsAnthropicFrontmatterAndWorkedExampleContract() {
        val prompt = AgentCreatorPromptBuilder.build("Нужен исследователь", setOf("agent.inspect_tools"))
        assertTrue(prompt.contains("clarifyingQuestions"))
        assertTrue(prompt.contains("triggeringExamples"))
        assertTrue(prompt.contains("description"))
        assertTrue(prompt.contains("HOST TOOLS (не используй"))
        assertTrue(prompt.contains("claudeCodeTools"))
    }

    @Test fun creatorBuildsWorkedExamplesOnHostWhenModelOmitsThem() = runBlocking {
        val modelOutput = com.google.gson.Gson().toJson(
            validManifest().copy(
                description = "",
                triggeringExamples = emptyList()
            )
        )
        val result = AgentCreator().createDraft(
            userRequest = "Погода в городах мира",
            knownToolIds = setOf("agent.inspect_tools"),
            existingAgentIds = emptySet(),
            generate = { modelOutput }
        )
        assertTrue(result is AgentCreationResult.Created)
        val created = (result as AgentCreationResult.Created).manifest
        assertEquals(2, created.triggeringExamples.size)
        assertTrue(created.triggeringExamples.all { it.assistant.isNotBlank() && it.invocation.isNotBlank() })
        assertTrue(created.description.isBlank())
        assertTrue(AgentMarkdownRenderer.render(created).contains("Examples:"))
    }

    @Test fun creatorPromptDoesNotAskSmallModelToGenerateNestedWorkedExamples() {
        val prompt = AgentCreatorPromptBuilder.build("Погода в городах мира", emptySet())
        assertTrue(prompt.contains("Не генерируй triggeringExamples"))
        assertTrue(prompt.contains("triggeringExamples\": []"))
        assertTrue(prompt.contains("description\": \"\""))
    }

    @Test fun promptContainsAllowlistAndUserTask() {
        val prompt = AgentCreatorPromptBuilder.build("Нужен исследователь", setOf("agent.inspect_tools"))
        assertTrue(prompt.contains("Нужен исследователь"))
        assertTrue(prompt.contains("agent.inspect_tools"))
        assertTrue(prompt.contains("DRAFT_ONLY"))
    }

    @Test fun approvedOfflineToolFreeSpecialistCanRun() {
        val decision = AgentRunPolicy.evaluate(
            validManifest().copy(status = AgentManifest.STATUS_APPROVED, allowedToolIds = emptyList())
        )
        assertTrue(decision.allowed)
    }

    @Test fun draftSpecialistCannotRun() {
        val decision = AgentRunPolicy.evaluate(validManifest())
        assertFalse(decision.allowed)
    }

    @Test fun specialistWithRequestedToolsCannotRunBeforeBindingExists() {
        val decision = AgentRunPolicy.evaluate(
            validManifest().copy(
                status = AgentManifest.STATUS_APPROVED,
                allowedToolIds = listOf("agent.inspect_tools")
            )
        )
        assertFalse(decision.allowed)
    }

    @Test fun specialistWithNetworkOrSideEffectsCannotRun() {
        val network = AgentRunPolicy.evaluate(
            validManifest().copy(status = AgentManifest.STATUS_APPROVED, allowedToolIds = emptyList(), networkPolicy = "ONLINE")
        )
        val sideEffect = AgentRunPolicy.evaluate(
            validManifest().copy(status = AgentManifest.STATUS_APPROVED, allowedToolIds = emptyList(), sideEffectPolicy = "EXECUTE")
        )
        assertFalse(network.allowed)
        assertFalse(sideEffect.allowed)
    }


    @Test fun creatorRetriesOnceWhenModelReturnsMalformedJson() = runBlocking {
        val valid = com.google.gson.Gson().toJson(validManifest())
        var calls = 0
        val result = AgentCreator().createDraft(
            userRequest = "Изучи мой телефон",
            knownToolIds = setOf("agent.inspect_tools"),
            existingAgentIds = emptySet(),
            generate = { prompt ->
                calls += 1
                if (calls == 1) """{"triggeringExamples":[{"user":"неэкранированная "кавычка"}]}"""
                else {
                    assertTrue(prompt.contains("Повтори создание с нуля"))
                    assertTrue(prompt.contains("короткий полный JSON"))
                    valid
                }
            }
        )
        assertEquals(2, calls)
        assertTrue(result is AgentCreationResult.Created)
    }

    @Test fun creatorRejectsMalformedJsonAfterSingleRepairAttempt() = runBlocking {
        var calls = 0
        val result = AgentCreator().createDraft(
            userRequest = "Изучи мой телефон",
            knownToolIds = emptySet(),
            existingAgentIds = emptySet(),
            generate = {
                calls += 1
                """{"triggeringExamples":[{"user":"broken "quote"}]}"""
            }
        )
        assertEquals(2, calls)
        assertTrue(result is AgentCreationResult.Rejected)
        assertEquals("INVALID_MODEL_OUTPUT", (result as AgentCreationResult.Rejected).reason)
    }


    @Test fun creatorPromptFitsSmallModelBudgetAndCapsInventories() {
        val prompt = AgentCreatorPromptBuilder.build(
            userRequest = "техник",
            knownToolIds = (1..200).map { "tool.$it" }.toSet(),
            existingAgents = (1..100).map { "agent-$it: " + "x".repeat(300) }
        )
        assertTrue("Creator prompt should remain compact for a 4k-token model", prompt.length < 10_000)
        assertTrue(prompt.contains("tool.1"))
        assertFalse(prompt.contains("tool.200"))
        assertFalse(prompt.contains("agent-100"))
    }

    @Test fun creatorRejectsOverlongUserRequestBeforePromptConstruction() {
        val tooLong = "x".repeat(AgentCreatorPromptBuilder.MAX_REQUEST_CHARS + 1)
        try {
            AgentCreatorPromptBuilder.build(tooLong, emptySet())
            throw AssertionError("Expected an overlong request to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: prevent user input alone from exhausting the model context.
        }
    }

}
