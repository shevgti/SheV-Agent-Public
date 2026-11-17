package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.ApprovalDecision
import com.shev.agent.CapabilityPolicy
import com.shev.agent.ScopedCapabilityGrant
import com.shev.agent.PersistentGrantPolicy
import com.shev.agent.PersistentGrantDecision
import com.shev.agent.core.McpInvocationExecutionContext

data class AgentToolRequest(
    val toolId: String,
    val capability: AgentCapability,
    val input: String,
    val resourceTarget: String? = null
)

data class AgentToolResult(
    val success: Boolean,
    val output: String,
    val errorCode: String? = null
)

fun interface AgentTool {
    fun execute(input: String): AgentToolResult
}

fun interface SuspendingAgentTool {
    suspend fun execute(input: String): AgentToolResult
}

/** Context-aware adapter for operations that must bind to a persisted task step. */
fun interface ContextualSuspendingAgentTool {
    suspend fun execute(input: String, context: AgentToolExecutionContext): AgentToolResult
}

data class AgentToolExecutionContext(
    val grantedCapabilities: Set<AgentCapability>,
    val explicitlyApprovedCapabilities: Set<AgentCapability> = emptySet(),
    val persistentGrants: List<ScopedCapabilityGrant> = emptyList(),
    val nowMillis: Long = System.currentTimeMillis(),
    /** Set only by the trusted task executor from persisted plan/step state. */
    val mcpInvocationContext: McpInvocationExecutionContext? = null,
    /** Persist the step as DISPATCHED after approval consumption and before network I/O. */
    val beforeMcpNetworkCall: (suspend (McpInvocationExecutionContext) -> Boolean)? = null,
    /** Persist a non-MCP step as DISPATCHED after policy/schema checks and before its handler runs. */
    val beforeToolDispatch: (suspend () -> Boolean)? = null
)

enum class AgentToolExecutionStatus {
    COMPLETED, TOOL_NOT_FOUND, CAPABILITY_NOT_DECLARED,
    ACCESS_DENIED, APPROVAL_REQUIRED, HANDLER_NOT_REGISTERED,
    INPUT_TOO_LARGE, INPUT_SCHEMA_NOT_REGISTERED, INVALID_INPUT, HANDLER_FAILED
}

data class AgentToolExecutionResult(
    val status: AgentToolExecutionStatus,
    val result: AgentToolResult? = null
)

/**
 * Безопасная точка входа для вызова адаптеров.
 * Реестр и исполнитель не выдают системные разрешения; grants передаются вызывающей стороной.
 */
class AgentToolExecutor(
    private val registry: AgentToolRegistry,
    private val handlers: Map<String, AgentTool>,
    private val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
    private val suspendingHandlers: Map<String, SuspendingAgentTool> = emptyMap(),
    private val contextualSuspendingHandlers: Map<String, ContextualSuspendingAgentTool> = emptyMap()
) {
    init {
        require(maxInputChars in 1..MAX_ALLOWED_INPUT_CHARS) {
            "Лимит входных данных должен быть от 1 до $MAX_ALLOWED_INPUT_CHARS символов"
        }
    }

    fun execute(
        request: AgentToolRequest,
        context: AgentToolExecutionContext
    ): AgentToolExecutionResult {
        val descriptor = registry.find(request.toolId)
            ?: return AgentToolExecutionResult(AgentToolExecutionStatus.TOOL_NOT_FOUND)

        // Remote MCP must use the contextual suspending bridge and its per-call durable gate.
        if (request.capability == AgentCapability.REMOTE_MCP_OPERATION) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.ACCESS_DENIED)
        }

        if (request.capability !in descriptor.capabilities) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.CAPABILITY_NOT_DECLARED)
        }

        val persistentGrantMatches = request.resourceTarget?.let { target ->
            PersistentGrantPolicy.decide(
                request.capability, target, context.persistentGrants, context.nowMillis
            ) == PersistentGrantDecision.ALLOW
        } ?: false
        val hasGrant = request.capability in context.grantedCapabilities ||
            (persistentGrantMatches && request.capability != AgentCapability.REMOTE_MCP_OPERATION)
        val approved = request.capability in context.explicitlyApprovedCapabilities ||
            (persistentGrantMatches && request.capability != AgentCapability.REMOTE_MCP_OPERATION)
        when (CapabilityPolicy.decide(request.capability, approved, hasGrant)) {
            ApprovalDecision.DENY ->
                return AgentToolExecutionResult(AgentToolExecutionStatus.ACCESS_DENIED)
            ApprovalDecision.REQUIRE_APPROVAL ->
                return AgentToolExecutionResult(AgentToolExecutionStatus.APPROVAL_REQUIRED)
            ApprovalDecision.ALLOW -> Unit
        }

        if (request.input.length > maxInputChars) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.INPUT_TOO_LARGE)
        }

        val schema = descriptor.inputSchema
            ?: return AgentToolExecutionResult(AgentToolExecutionStatus.INPUT_SCHEMA_NOT_REGISTERED)
        if (!schema.validate(request.input).accepted) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.INVALID_INPUT)
        }

        val handler = handlers[request.toolId]
            ?: return AgentToolExecutionResult(AgentToolExecutionStatus.HANDLER_NOT_REGISTERED)

        return try {
            AgentToolExecutionResult(
                AgentToolExecutionStatus.COMPLETED,
                handler.execute(request.input)
            )
        } catch (_: Exception) {
            AgentToolExecutionResult(
                AgentToolExecutionStatus.HANDLER_FAILED,
                AgentToolResult(false, "Инструмент завершился с ошибкой", "HANDLER_FAILED")
            )
        }
    }

    /**
     * Coroutine-friendly execution path for ADK suspend tools. It repeats the same
     * schema, capability and scoped-grant checks as execute(); cancellation is never
     * converted into a tool failure.
     */
    suspend fun executeSuspending(
        request: AgentToolRequest,
        context: AgentToolExecutionContext
    ): AgentToolExecutionResult {
        val descriptor = registry.find(request.toolId)
            ?: return AgentToolExecutionResult(AgentToolExecutionStatus.TOOL_NOT_FOUND)

        val contextualHandler = contextualSuspendingHandlers[request.toolId]
        val hasDurableRemoteGate = request.capability == AgentCapability.REMOTE_MCP_OPERATION &&
            contextualHandler != null &&
            context.mcpInvocationContext != null &&
            context.beforeMcpNetworkCall != null
        if (request.capability == AgentCapability.REMOTE_MCP_OPERATION && !hasDurableRemoteGate) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.ACCESS_DENIED)
        }

        if (request.capability !in descriptor.capabilities) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.CAPABILITY_NOT_DECLARED)
        }

        val persistentGrantMatches = request.resourceTarget?.let { target ->
            PersistentGrantPolicy.decide(
                request.capability, target, context.persistentGrants, context.nowMillis
            ) == PersistentGrantDecision.ALLOW
        } ?: false
        val hasGrant = request.capability in context.grantedCapabilities ||
            (persistentGrantMatches && request.capability != AgentCapability.REMOTE_MCP_OPERATION)
        val approved = request.capability in context.explicitlyApprovedCapabilities ||
            (persistentGrantMatches && request.capability != AgentCapability.REMOTE_MCP_OPERATION) ||
            hasDurableRemoteGate
        when (CapabilityPolicy.decide(request.capability, approved, hasGrant)) {
            ApprovalDecision.DENY ->
                return AgentToolExecutionResult(AgentToolExecutionStatus.ACCESS_DENIED)
            ApprovalDecision.REQUIRE_APPROVAL ->
                return AgentToolExecutionResult(AgentToolExecutionStatus.APPROVAL_REQUIRED)
            ApprovalDecision.ALLOW -> Unit
        }

        if (request.input.length > maxInputChars) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.INPUT_TOO_LARGE)
        }

        val schema = descriptor.inputSchema
            ?: return AgentToolExecutionResult(AgentToolExecutionStatus.INPUT_SCHEMA_NOT_REGISTERED)
        if (!schema.validate(request.input).accepted) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.INVALID_INPUT)
        }

        val handler = suspendingHandlers[request.toolId]
        val synchronousHandler = handlers[request.toolId]
        if (contextualHandler == null && handler == null && synchronousHandler == null) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.HANDLER_NOT_REGISTERED)
        }

        return try {
            if (request.capability != AgentCapability.REMOTE_MCP_OPERATION &&
                context.beforeToolDispatch != null &&
                !context.beforeToolDispatch.invoke()
            ) {
                return AgentToolExecutionResult(
                    AgentToolExecutionStatus.HANDLER_FAILED,
                    AgentToolResult(false, "Не удалось сохранить состояние шага перед запуском", "STEP_DISPATCH_NOT_PERSISTED")
                )
            }
            AgentToolExecutionResult(
                AgentToolExecutionStatus.COMPLETED,
                contextualHandler?.execute(request.input, context)
                    ?: handler?.execute(request.input)
                    ?: synchronousHandler!!.execute(request.input)
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolExecutionResult(
                AgentToolExecutionStatus.HANDLER_FAILED,
                AgentToolResult(false, "Инструмент завершился с ошибкой", "HANDLER_FAILED")
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_INPUT_CHARS = 8192
        const val MAX_ALLOWED_INPUT_CHARS = 65536
    }
}
