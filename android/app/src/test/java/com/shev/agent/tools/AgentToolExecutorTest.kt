package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.GrantScopeType
import com.shev.agent.ScopedCapabilityGrant
import org.junit.Assert.*
import org.junit.Test

class AgentToolExecutorTest {
    private fun setup(
        handler: AgentTool = AgentTool { AgentToolResult(true, "ok:$it") },
        maxInputChars: Int = AgentToolExecutor.DEFAULT_MAX_INPUT_CHARS
    ): AgentToolExecutor {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "local.echo", "Тестовый инструмент", "Возвращает входные данные",
                setOf(AgentCapability.ANALYZE_DATA, AgentCapability.WRITE_PROJECT_FILES, AgentCapability.DELETE_OR_OVERWRITE_DATA),
                ToolInputSchema(listOf(ToolInputField("text", ToolInputType.STRING, maxLength = 128)))
            )
        )
        return AgentToolExecutor(registry, mapOf("local.echo" to handler), maxInputChars)
    }

    private fun lowRiskContext() =
        AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))

    @Test fun lowRiskGrantedCapabilityExecutesHandler() {
        val result = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"hello"}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.COMPLETED, result.status)
        assertEquals("""ok:{"text":"hello"}""", result.result?.output)
    }

    @Test fun missingGrantBlocksExecution() {
        val result = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"hello"}"""),
            AgentToolExecutionContext(emptySet())
        )
        assertEquals(AgentToolExecutionStatus.ACCESS_DENIED, result.status)
    }

    @Test fun highRiskOperationRequiresExplicitApproval() {
        val result = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.WRITE_PROJECT_FILES, """{"text":"change"}"""),
            AgentToolExecutionContext(setOf(AgentCapability.WRITE_PROJECT_FILES))
        )
        assertEquals(AgentToolExecutionStatus.APPROVAL_REQUIRED, result.status)
    }

    @Test fun explicitApprovalAllowsGrantedHighRiskOperation() {
        val result = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.WRITE_PROJECT_FILES, """{"text":"change"}"""),
            AgentToolExecutionContext(
                grantedCapabilities = setOf(AgentCapability.WRITE_PROJECT_FILES),
                explicitlyApprovedCapabilities = setOf(AgentCapability.WRITE_PROJECT_FILES)
            )
        )
        assertEquals(AgentToolExecutionStatus.COMPLETED, result.status)
    }

    @Test fun toolCannotUseCapabilityItDidNotDeclare() {
        val result = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.SPEND_MONEY, """{"text":"pay"}"""),
            AgentToolExecutionContext(setOf(AgentCapability.SPEND_MONEY), setOf(AgentCapability.SPEND_MONEY))
        )
        assertEquals(AgentToolExecutionStatus.CAPABILITY_NOT_DECLARED, result.status)
    }

    @Test fun unknownToolIsRejected() {
        val result = setup().execute(
            AgentToolRequest("unknown.tool", AgentCapability.ANALYZE_DATA, """{"text":"hello"}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.TOOL_NOT_FOUND, result.status)
    }

    @Test fun missingHandlerDoesNotPretendToExecute() {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor("local.noop", "Нет обработчика", "Только описание", setOf(AgentCapability.ANALYZE_DATA), ToolInputSchema(listOf(ToolInputField("text", ToolInputType.STRING))))
        )
        val result = AgentToolExecutor(registry, emptyMap()).execute(
            AgentToolRequest("local.noop", AgentCapability.ANALYZE_DATA, """{"text":"x"}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.HANDLER_NOT_REGISTERED, result.status)
    }

    @Test fun oversizedInputIsRejectedBeforeHandlerRuns() {
        var called = false
        val executor = setup(
            handler = AgentTool {
                called = true
                AgentToolResult(true, "unexpected")
            },
            maxInputChars = 21
        )
        val result = executor.execute(
            AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"123456789012345"}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.INPUT_TOO_LARGE, result.status)
        assertFalse(called)
    }

    @Test fun inputAtLimitIsAccepted() {
        val result = setup(maxInputChars = 21).execute(
            AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"1234567890"}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.COMPLETED, result.status)
    }

    @Test fun handlerExceptionIsContainedAndReported() {
        val result = setup(handler = AgentTool { throw IllegalStateException("private detail") }).execute(
            AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"hello"}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.HANDLER_FAILED, result.status)
        assertEquals("HANDLER_FAILED", result.result?.errorCode)
        assertFalse(result.result?.output?.contains("private detail") == true)
    }

    @Test fun invalidInputLimitIsRejectedAtConstruction() {
        try {
            setup(maxInputChars = AgentToolExecutor.MAX_ALLOWED_INPUT_CHARS + 1)
            fail("Expected invalid limit to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun strictSchemaRejectsUnknownFieldBeforeHandlerRuns() {
        var called = false
        val executor = setup(handler = AgentTool {
            called = true
            AgentToolResult(true, "unexpected")
        })
        val result = executor.execute(
            AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"hello","extra":true}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.INVALID_INPUT, result.status)
        assertFalse(called)
    }

    @Test fun strictSchemaRejectsWrongFieldType() {
        val result = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":42}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.INVALID_INPUT, result.status)
    }

    @Test fun toolWithoutSchemaFailsClosed() {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor("local.unsafe", "Без схемы", "Тест", setOf(AgentCapability.ANALYZE_DATA))
        )
        var called = false
        val executor = AgentToolExecutor(registry, mapOf("local.unsafe" to AgentTool {
            called = true
            AgentToolResult(true, "unexpected")
        }))
        val result = executor.execute(
            AgentToolRequest("local.unsafe", AgentCapability.ANALYZE_DATA, """{"anything":true}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.INPUT_SCHEMA_NOT_REGISTERED, result.status)
        assertFalse(called)
    }



    @Test fun validPersistentGrantAllowsMatchingHighRiskResourceWithoutPrompt() {
        val grant = ScopedCapabilityGrant(
            grantId = "grant-1",
            capability = AgentCapability.WRITE_PROJECT_FILES,
            scopeType = GrantScopeType.PROJECT,
            scopeValue = "repo:SheV-Memory",
            createdAtMillis = 1_000,
            expiresAtMillis = 20_000
        )
        val result = setup().execute(
            AgentToolRequest(
                "local.echo", AgentCapability.WRITE_PROJECT_FILES,
                """{"text":"change"}""", resourceTarget = "repo:SheV-Memory"
            ),
            AgentToolExecutionContext(
                grantedCapabilities = emptySet(),
                persistentGrants = listOf(grant),
                nowMillis = 5_000
            )
        )
        assertEquals(AgentToolExecutionStatus.COMPLETED, result.status)
    }

    @Test fun persistentGrantCannotAuthorizeDifferentResourceOrExpiredGrant() {
        val grant = ScopedCapabilityGrant(
            grantId = "grant-1",
            capability = AgentCapability.WRITE_PROJECT_FILES,
            scopeType = GrantScopeType.PROJECT,
            scopeValue = "repo:SheV-Memory",
            createdAtMillis = 1_000,
            expiresAtMillis = 5_000
        )
        val differentTarget = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.WRITE_PROJECT_FILES, """{"text":"x"}""", "repo:Other"),
            AgentToolExecutionContext(emptySet(), persistentGrants = listOf(grant), nowMillis = 3_000)
        )
        assertEquals(AgentToolExecutionStatus.ACCESS_DENIED, differentTarget.status)

        val expired = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.WRITE_PROJECT_FILES, """{"text":"x"}""", "repo:SheV-Memory"),
            AgentToolExecutionContext(emptySet(), persistentGrants = listOf(grant), nowMillis = 5_000)
        )
        assertEquals(AgentToolExecutionStatus.ACCESS_DENIED, expired.status)
    }

    @Test fun persistentGrantNeverBypassesCriticalPerActionApproval() {
        val grant = ScopedCapabilityGrant(
            grantId = "grant-critical",
            capability = AgentCapability.DELETE_OR_OVERWRITE_DATA,
            scopeType = GrantScopeType.PROJECT,
            scopeValue = "repo:SheV-Memory",
            createdAtMillis = 1_000,
            expiresAtMillis = null
        )
        val result = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.DELETE_OR_OVERWRITE_DATA, """{"text":"delete"}""", "repo:SheV-Memory"),
            AgentToolExecutionContext(
                grantedCapabilities = setOf(AgentCapability.DELETE_OR_OVERWRITE_DATA),
                persistentGrants = listOf(grant),
                nowMillis = 5_000
            )
        )
        assertEquals(AgentToolExecutionStatus.APPROVAL_REQUIRED, result.status)
    }

    @Test fun strictSchemaRejectsMalformedJson() {
        val result = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.INVALID_INPUT, result.status)
    }

    @Test fun strictSchemaRejectsMissingRequiredField() {
        val result = setup().execute(
            AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{}"""),
            lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.INVALID_INPUT, result.status)
    }

    @Test fun suspendingHandlerUsesTheSameSchemaAndCapabilityBoundary() = kotlinx.coroutines.runBlocking {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "local.suspend",
                "Асинхронный инструмент",
                "Тестирует suspend-исполнитель",
                setOf(AgentCapability.CREATE_DRAFT),
                ToolInputSchema(listOf(ToolInputField("title", ToolInputType.STRING, maxLength = 100)))
            )
        )
        var called = false
        val executor = AgentToolExecutor(
            registry,
            emptyMap(),
            suspendingHandlers = mapOf("local.suspend" to SuspendingAgentTool {
                called = true
                AgentToolResult(true, "saved:$it")
            })
        )
        val result = executor.executeSuspending(
            AgentToolRequest("local.suspend", AgentCapability.CREATE_DRAFT, """{"title":"Оплатить квартиру"}"""),
            AgentToolExecutionContext(setOf(AgentCapability.CREATE_DRAFT))
        )
        assertEquals(AgentToolExecutionStatus.COMPLETED, result.status)
        assertTrue(called)
    }

    @Test fun suspendingHandlerCannotRunWithoutCapabilityGrant() = kotlinx.coroutines.runBlocking {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "local.suspend",
                "Асинхронный инструмент",
                "Тестирует проверку разрешений",
                setOf(AgentCapability.CREATE_DRAFT),
                ToolInputSchema(listOf(ToolInputField("title", ToolInputType.STRING)))
            )
        )
        var called = false
        val executor = AgentToolExecutor(
            registry,
            emptyMap(),
            suspendingHandlers = mapOf("local.suspend" to SuspendingAgentTool {
                called = true
                AgentToolResult(true, "unexpected")
            })
        )
        val result = executor.executeSuspending(
            AgentToolRequest("local.suspend", AgentCapability.CREATE_DRAFT, """{"title":"x"}"""),
            AgentToolExecutionContext(emptySet())
        )
        assertEquals(AgentToolExecutionStatus.ACCESS_DENIED, result.status)
        assertFalse(called)
    }

    @Test fun suspendingHandlerRejectsMalformedInputBeforeCallingHandler() = kotlinx.coroutines.runBlocking {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "local.suspend",
                "Асинхронный инструмент",
                "Тестирует схему",
                setOf(AgentCapability.CREATE_DRAFT),
                ToolInputSchema(listOf(ToolInputField("title", ToolInputType.STRING)))
            )
        )
        var called = false
        val executor = AgentToolExecutor(
            registry,
            emptyMap(),
            suspendingHandlers = mapOf("local.suspend" to SuspendingAgentTool {
                called = true
                AgentToolResult(true, "unexpected")
            })
        )
        val result = executor.executeSuspending(
            AgentToolRequest("local.suspend", AgentCapability.CREATE_DRAFT, """{"title":42}"""),
            AgentToolExecutionContext(setOf(AgentCapability.CREATE_DRAFT))
        )
        assertEquals(AgentToolExecutionStatus.INVALID_INPUT, result.status)
        assertFalse(called)
    }


    @Test fun availableToolsExcludesDescriptorsWithoutHandlersOrSchemas() {
        val registry = AgentToolRegistry()
        val schema = ToolInputSchema(listOf(ToolInputField("text", ToolInputType.STRING)))
        registry.register(AgentToolDescriptor("local.ready", "Готовый", "Есть обработчик", setOf(AgentCapability.ANALYZE_DATA), schema))
        registry.register(AgentToolDescriptor("local.unbound", "Без обработчика", "Только описание", setOf(AgentCapability.ANALYZE_DATA), schema))
        registry.register(AgentToolDescriptor("local.no-schema", "Без схемы", "Не может пройти проверку входа", setOf(AgentCapability.ANALYZE_DATA)))
        val executor = AgentToolExecutor(
            registry,
            mapOf("local.ready" to AgentTool { AgentToolResult(true, "ok") })
        )
        assertEquals(listOf("local.ready"), executor.availableTools().map { it.id })
    }

    @Test fun availableToolsIncludesRegisteredSuspendingHandlers() {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "local.async",
                "Асинхронный",
                "Есть suspend-обработчик",
                setOf(AgentCapability.CREATE_DRAFT),
                ToolInputSchema(listOf(ToolInputField("title", ToolInputType.STRING)))
            )
        )
        val executor = AgentToolExecutor(
            registry,
            emptyMap(),
            suspendingHandlers = mapOf("local.async" to SuspendingAgentTool {
                AgentToolResult(true, "ok")
            })
        )
        assertEquals(listOf("local.async"), executor.availableTools().map { it.id })
    }


    @Test fun agentAllowlistBlocksToolEvenWhenGlobalCapabilityIsGranted() {
        var called = false
        val executor = setup(handler = AgentTool {
            called = true
            AgentToolResult(true, "unexpected")
        })
        val result = executor.executeForAgent(
            agentAllowedToolIds = setOf("local.other"),
            request = AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"hello"}"""),
            context = lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.AGENT_ALLOWLIST_DENIED, result.status)
        assertFalse(called)
    }

    @Test fun agentAllowlistAllowsOnlySelectedToolThroughExistingCapabilityChecks() {
        val result = setup().executeForAgent(
            agentAllowedToolIds = setOf("local.echo"),
            request = AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"hello"}"""),
            context = lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.COMPLETED, result.status)
        assertEquals("""ok:{"text":"hello"}""", result.result?.output)
    }

    @Test fun emptyAgentAllowlistDeniesEveryTool() {
        var called = false
        val executor = setup(handler = AgentTool {
            called = true
            AgentToolResult(true, "unexpected")
        })
        val result = executor.executeForAgent(
            agentAllowedToolIds = emptySet(),
            request = AgentToolRequest("local.echo", AgentCapability.ANALYZE_DATA, """{"text":"hello"}"""),
            context = lowRiskContext()
        )
        assertEquals(AgentToolExecutionStatus.AGENT_ALLOWLIST_DENIED, result.status)
        assertFalse(called)
    }

    @Test fun suspendingExecutionAlsoEnforcesAgentAllowlist() = kotlinx.coroutines.runBlocking {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "local.suspend", "Асинхронный", "Тест allowlist",
                setOf(AgentCapability.CREATE_DRAFT),
                ToolInputSchema(listOf(ToolInputField("title", ToolInputType.STRING)))
            )
        )
        var called = false
        val executor = AgentToolExecutor(
            registry, emptyMap(),
            suspendingHandlers = mapOf("local.suspend" to SuspendingAgentTool {
                called = true
                AgentToolResult(true, "unexpected")
            })
        )
        val result = executor.executeSuspendingForAgent(
            agentAllowedToolIds = setOf("local.other"),
            request = AgentToolRequest("local.suspend", AgentCapability.CREATE_DRAFT, """{"title":"x"}"""),
            context = AgentToolExecutionContext(setOf(AgentCapability.CREATE_DRAFT))
        )
        assertEquals(AgentToolExecutionStatus.AGENT_ALLOWLIST_DENIED, result.status)
        assertFalse(called)
    }


    @Test fun executionContextDefensivelyCopiesMutablePermissionCollections() {
        val granted = mutableSetOf(AgentCapability.ANALYZE_DATA)
        val approved = mutableSetOf(AgentCapability.WRITE_PROJECT_FILES)
        val grants = mutableListOf<ScopedCapabilityGrant>()
        val context = AgentToolExecutionContext(granted, approved, grants)

        granted.clear()
        approved.clear()
        grants += ScopedCapabilityGrant(
            grantId = "late-grant",
            capability = AgentCapability.DELETE_OR_OVERWRITE_DATA,
            scopeType = GrantScopeType.PROJECT,
            scopeValue = "repo:unexpected",
            createdAtMillis = 1,
            expiresAtMillis = null
        )

        assertTrue(AgentCapability.ANALYZE_DATA in context.grantedCapabilities)
        assertTrue(AgentCapability.WRITE_PROJECT_FILES in context.explicitlyApprovedCapabilities)
        assertTrue(context.persistentGrants.isEmpty())
    }

    @Test fun executionContextPermissionSnapshotsCannotBeMutatedThroughCasts() {
        val context = AgentToolExecutionContext(
            setOf(AgentCapability.ANALYZE_DATA),
            setOf(AgentCapability.WRITE_PROJECT_FILES)
        )

        try {
            (context.grantedCapabilities as MutableSet).clear()
            fail("Granted capabilities must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected: the execution context exposes an immutable snapshot.
        }
        try {
            (context.explicitlyApprovedCapabilities as MutableSet).clear()
            fail("Explicit approvals must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected: the execution context exposes an immutable snapshot.
        }
    }

    @Test fun persistentGrantExpiryUsesCurrentTimeWhenNoFixedTestClockIsProvided() {
        val grant = ScopedCapabilityGrant(
            grantId = "already-expired",
            capability = AgentCapability.WRITE_PROJECT_FILES,
            scopeType = GrantScopeType.PROJECT,
            scopeValue = "repo:SheV-Agent-Public",
            createdAtMillis = 1,
            expiresAtMillis = 2
        )
        val result = setup().execute(
            AgentToolRequest(
                "local.echo",
                AgentCapability.WRITE_PROJECT_FILES,
                """{"text":"change"}""",
                "repo:SheV-Agent-Public"
            ),
            AgentToolExecutionContext(
                grantedCapabilities = emptySet(),
                persistentGrants = listOf(grant)
            )
        )
        assertEquals(AgentToolExecutionStatus.ACCESS_DENIED, result.status)
    }

}
