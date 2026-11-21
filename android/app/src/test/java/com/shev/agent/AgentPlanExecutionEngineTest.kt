package com.shev.agent

import com.shev.agent.tools.AgentTool
import com.shev.agent.tools.AgentToolDescriptor
import com.shev.agent.tools.AgentToolExecutionContext
import com.shev.agent.tools.AgentToolExecutor
import com.shev.agent.tools.AgentToolRegistry
import com.shev.agent.tools.AgentToolResult
import com.shev.agent.tools.ToolInputField
import com.shev.agent.tools.ToolInputSchema
import com.shev.agent.tools.ToolInputType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanExecutionEngineTest {
    private fun registry(): AgentToolRegistry = AgentToolRegistry().apply {
        register(
            AgentToolDescriptor(
                id = "research.lookup",
                title = "Поиск",
                description = "Ищет информацию",
                capabilities = setOf(AgentCapability.ANALYZE_DATA),
                inputSchema = ToolInputSchema(
                    listOf(ToolInputField("query", ToolInputType.STRING, maxLength = 200))
                )
            )
        )
    }

    private fun plan() = AgentPlan(
        summary = "Найти и проверить сведения",
        steps = listOf(
            AgentPlanStep(
                id = "search-1",
                title = "Найти источники",
                toolId = "research.lookup",
                capability = AgentCapability.ANALYZE_DATA,
                input = """{"query":"актуальные сведения"}"""
            )
        )
    )

    @Test fun executesApprovedPlanSequentiallyButRequiresSeparateVerification() = runBlocking {
        var calls = 0
        val registry = registry()
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool {
                calls++
                AgentToolResult(true, "Источник: https://example.org")
            })
        )
        val engine = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor)

        val result = engine.executeApprovedPlan(
            plan(),
            agentAllowedToolIds = setOf("research.lookup"),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))
        )

        assertEquals(1, calls)
        assertEquals(AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION, result.status)
        assertEquals("search-1", result.steps.single().stepId)
        assertTrue(result.steps.single().output.contains("https://example.org"))
    }

    @Test fun persistsStepStartAndFinishCallbacksInOrder() = runBlocking {
        val registry = registry()
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool { AgentToolResult(true, "verified output") })
        )
        val engine = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor)
        val events = mutableListOf<String>()

        val result = engine.executeApprovedPlan(
            plan(),
            agentAllowedToolIds = setOf("research.lookup"),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA)),
            onStepStarted = { events += "started:${it.id}" },
            onStepFinished = { events += "finished:${it.stepId}:${it.success}" }
        )

        assertEquals(AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION, result.status)
        assertEquals(listOf("started:search-1", "finished:search-1:true"), events)
    }

    @Test fun failedStepIsReportedToDurableProgressCallback() = runBlocking {
        val registry = registry()
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool {
                AgentToolResult(false, "upstream unavailable", "UPSTREAM_ERROR")
            })
        )
        val engine = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor)
        var finished: AgentPlanStepExecution? = null

        val result = engine.executeApprovedPlan(
            plan(),
            agentAllowedToolIds = setOf("research.lookup"),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA)),
            onStepFinished = { finished = it }
        )

        assertEquals(AgentPlanExecutionStatus.STEP_FAILED, result.status)
        assertEquals(false, finished?.success)
        assertEquals("UPSTREAM_ERROR", finished?.errorCode)
        assertEquals("search-1", finished?.stepId)
    }

    @Test fun agentAllowlistDenialStopsBeforeHandlerAndDoesNotClaimCompletion() = runBlocking {
        var calls = 0
        val registry = registry()
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool {
                calls++
                AgentToolResult(true, "unexpected")
            })
        )
        val engine = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor)

        val result = engine.executeApprovedPlan(
            plan(),
            agentAllowedToolIds = emptySet(),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))
        )

        assertEquals(0, calls)
        assertEquals(AgentPlanExecutionStatus.AGENT_ALLOWLIST_DENIED, result.status)
        assertFalse(result.status == AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION)
    }

    @Test fun missingExecutableHandlerIsDetectedBeforeAnyStepRuns() = runBlocking {
        var calls = 0
        val registry = registry().apply {
            register(
                AgentToolDescriptor(
                    id = "research.open",
                    title = "Открыть страницу",
                    description = "Читает выбранную веб-страницу",
                    capabilities = setOf(AgentCapability.ANALYZE_DATA),
                    inputSchema = ToolInputSchema(
                        listOf(ToolInputField("query", ToolInputType.STRING, maxLength = 200))
                    )
                )
            )
        }
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool {
                calls++
                AgentToolResult(true, "first")
            })
        )
        val twoStepPlan = plan().copy(
            steps = plan().steps + AgentPlanStep(
                id = "search-2",
                title = "Открыть страницу",
                toolId = "research.open",
                capability = AgentCapability.ANALYZE_DATA,
                input = """{"query":"https://example.org"}"""
            )
        )
        val engine = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor)

        val result = engine.executeApprovedPlan(
            twoStepPlan,
            agentAllowedToolIds = setOf("research.lookup", "research.open"),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))
        )

        assertEquals(0, calls)
        assertEquals(AgentPlanExecutionStatus.TOOL_NOT_EXECUTABLE, result.status)
    }

    @Test fun invalidLaterStepIsRejectedBeforeFirstHandlerRuns() = runBlocking {
        var calls = 0
        val registry = registry()
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool {
                calls++
                AgentToolResult(true, "unexpected")
            })
        )
        val twoStepPlan = plan().copy(
            steps = listOf(
                plan().steps.single(),
                plan().steps.single().copy(id = "search-2", input = """{"unexpected":"bad"}""")
            )
        )
        val engine = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor)

        val result = engine.executeApprovedPlan(
            twoStepPlan,
            agentAllowedToolIds = setOf("research.lookup"),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))
        )

        assertEquals(0, calls)
        assertEquals(AgentPlanExecutionStatus.INVALID_PLAN, result.status)
        assertEquals(AgentPlanRejection.INVALID_TOOL_INPUT, result.validationRejection)
    }

    @Test fun writeStepStopsAtApprovalBoundary() = runBlocking {
        val registry = AgentToolRegistry().apply {
            register(
                AgentToolDescriptor(
                    id = "project.write_draft",
                    title = "Черновик изменения",
                    description = "Готовит черновик изменения проекта",
                    capabilities = setOf(AgentCapability.WRITE_PROJECT_FILES),
                    inputSchema = ToolInputSchema(
                        listOf(ToolInputField("content", ToolInputType.STRING, maxLength = 200))
                    )
                )
            )
        }
        var calls = 0
        val executor = AgentToolExecutor(
            registry,
            mapOf("project.write_draft" to AgentTool {
                calls++
                AgentToolResult(true, "written")
            })
        )
        val plan = AgentPlan(
            "Подготовить черновик",
            listOf(
                AgentPlanStep(
                    "write-1",
                    "Подготовить файл",
                    "project.write_draft",
                    AgentCapability.WRITE_PROJECT_FILES,
                    """{"content":"draft"}"""
                )
            )
        )
        val engine = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor)

        val result = engine.executeApprovedPlan(
            plan,
            agentAllowedToolIds = setOf("project.write_draft"),
            context = AgentToolExecutionContext(
                grantedCapabilities = setOf(AgentCapability.WRITE_PROJECT_FILES)
            )
        )

        assertEquals(0, calls)
        assertEquals(AgentPlanExecutionStatus.APPROVAL_REQUIRED, result.status)
        assertEquals("write-1", result.failedStepId)
    }

    @Test fun missingCapabilityStopsExecutionBeforeHandler() = runBlocking {
        var calls = 0
        val registry = registry()
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool {
                calls++
                AgentToolResult(true, "unexpected")
            })
        )
        val engine = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor)

        val result = engine.executeApprovedPlan(
            plan(),
            agentAllowedToolIds = setOf("research.lookup"),
            context = AgentToolExecutionContext(emptySet())
        )

        assertEquals(0, calls)
        assertEquals(AgentPlanExecutionStatus.ACCESS_DENIED, result.status)
    }

    @Test fun failedToolStopsTheRemainingPlan() = runBlocking {
        var calls = 0
        val registry = registry()
        val twoStepPlan = plan().copy(
            steps = listOf(
                plan().steps.single(),
                plan().steps.single().copy(id = "search-2")
            )
        )
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool {
                calls++
                AgentToolResult(false, "failed", "UPSTREAM_ERROR")
            })
        )
        val engine = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor)

        val result = engine.executeApprovedPlan(
            twoStepPlan,
            agentAllowedToolIds = setOf("research.lookup"),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))
        )

        assertEquals(1, calls)
        assertEquals(AgentPlanExecutionStatus.STEP_FAILED, result.status)
        assertEquals("search-1", result.failedStepId)
    }

    @Test fun repeatedIdenticalToolCallIsBlockedWithinOneApprovedPlan() = runBlocking {
        var calls = 0
        val registry = registry()
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool {
                calls++
                AgentToolResult(true, "Источник найден")
            })
        )
        val duplicatePlan = plan().copy(
            steps = listOf(
                plan().steps.single(),
                plan().steps.single().copy(id = "search-duplicate", title = "Повторить тот же поиск")
            )
        )
        val result = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor).executeApprovedPlan(
            duplicatePlan,
            agentAllowedToolIds = setOf("research.lookup"),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))
        )
        assertEquals(1, calls)
        assertEquals(AgentPlanExecutionStatus.DUPLICATE_TOOL_CALL, result.status)
        assertEquals("search-duplicate", result.failedStepId)
        assertEquals(1, result.steps.size)
        assertTrue(result.steps.single().success)
    }

    @Test fun sameToolWithDifferentInputIsNotTreatedAsDuplicate() = runBlocking {
        var calls = 0
        val registry = registry()
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool {
                calls++
                AgentToolResult(true, "ok")
            })
        )
        val twoQueries = plan().copy(
            steps = listOf(
                plan().steps.single(),
                plan().steps.single().copy(id = "search-2", input = "{\"query\":\"другая тема\"}")
            )
        )
        val result = AgentPlanExecutionEngine(AgentPlanValidator(registry), executor).executeApprovedPlan(
            twoQueries,
            agentAllowedToolIds = setOf("research.lookup"),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))
        )
        assertEquals(2, calls)
        assertEquals(AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION, result.status)
    }

    @Test fun outputIsBounded() = runBlocking {
        val registry = registry()
        val executor = AgentToolExecutor(
            registry,
            mapOf("research.lookup" to AgentTool { AgentToolResult(true, "x".repeat(1000)) })
        )
        val engine = AgentPlanExecutionEngine(
            AgentPlanValidator(registry),
            executor,
            maxOutputChars = 256
        )

        val result = engine.executeApprovedPlan(
            plan(),
            agentAllowedToolIds = setOf("research.lookup"),
            context = AgentToolExecutionContext(setOf(AgentCapability.ANALYZE_DATA))
        )

        assertEquals(AgentPlanExecutionStatus.EXECUTED_NEEDS_VERIFICATION, result.status)
        assertEquals(256 + "\n[результат сокращён]".length, result.steps.single().output.length)
    }
}
