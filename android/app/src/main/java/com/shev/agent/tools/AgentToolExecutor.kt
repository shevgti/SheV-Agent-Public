package com.shev.agent.tools

import com.shev.agent.AgentCapability
import com.shev.agent.ApprovalDecision
import com.shev.agent.CapabilityPolicy
import com.shev.agent.ScopedCapabilityGrant
import com.shev.agent.PersistentGrantPolicy
import com.shev.agent.PersistentGrantDecision
import java.util.Collections

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

/**
 * Immutable permission snapshot for one agent session.
 *
 * Collections are defensively copied and wrapped so a caller cannot mutate grants or
 * approvals after the ADK adapter has been created. A fixed clock is injectable for
 * deterministic tests; production calls use the current time at each tool invocation.
 *
 * Persistent-grant revocation must be reflected by constructing a fresh session context
 * from the authoritative grant store; this snapshot is not a live grant database.
 */
class AgentToolExecutionContext(
    grantedCapabilities: Set<AgentCapability>,
    explicitlyApprovedCapabilities: Set<AgentCapability> = emptySet(),
    persistentGrants: List<ScopedCapabilityGrant> = emptyList(),
    val nowMillis: Long? = null
) {
    val grantedCapabilities: Set<AgentCapability> =
        Collections.unmodifiableSet(LinkedHashSet(grantedCapabilities))
    val explicitlyApprovedCapabilities: Set<AgentCapability> =
        Collections.unmodifiableSet(LinkedHashSet(explicitlyApprovedCapabilities))
    val persistentGrants: List<ScopedCapabilityGrant> =
        Collections.unmodifiableList(ArrayList(persistentGrants))

    fun currentTimeMillis(): Long = nowMillis ?: System.currentTimeMillis()
}

enum class AgentToolExecutionStatus {
    COMPLETED, TOOL_NOT_FOUND, CAPABILITY_NOT_DECLARED,
    ACCESS_DENIED, APPROVAL_REQUIRED, AGENT_ALLOWLIST_DENIED, HANDLER_NOT_REGISTERED,
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
    private val suspendingHandlers: Map<String, SuspendingAgentTool> = emptyMap()
) {
    init {
        require(maxInputChars in 1..MAX_ALLOWED_INPUT_CHARS) {
            "Лимит входных данных должен быть от 1 до $MAX_ALLOWED_INPUT_CHARS символов"
        }
    }

    /** Tools advertised to agent designers must have a schema and a real executable handler. */
    fun availableTools(): List<AgentToolDescriptor> = registry.list().filter { descriptor ->
        descriptor.inputSchema != null &&
            (descriptor.id in handlers || descriptor.id in suspendingHandlers)
    }

    /**
     * Executes a tool only when its ID is explicitly present in this agent's approved run allowlist snapshot.
     * The allowlist is checked before registry lookup; capability grants and per-action approvals
     * are still independently enforced by [execute].
     */
    fun executeForAgent(
        agentAllowedToolIds: Set<String>,
        request: AgentToolRequest,
        context: AgentToolExecutionContext
    ): AgentToolExecutionResult {
        if (request.toolId !in agentAllowedToolIds) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.AGENT_ALLOWLIST_DENIED)
        }
        return execute(request, context)
    }

    /** Suspend counterpart with the same per-agent allowlist boundary. */
    suspend fun executeSuspendingForAgent(
        agentAllowedToolIds: Set<String>,
        request: AgentToolRequest,
        context: AgentToolExecutionContext
    ): AgentToolExecutionResult {
        if (request.toolId !in agentAllowedToolIds) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.AGENT_ALLOWLIST_DENIED)
        }
        return executeSuspending(request, context)
    }

    fun execute(
        request: AgentToolRequest,
        context: AgentToolExecutionContext
    ): AgentToolExecutionResult {
        val descriptor = registry.find(request.toolId)
            ?: return AgentToolExecutionResult(AgentToolExecutionStatus.TOOL_NOT_FOUND)

        if (request.capability !in descriptor.capabilities) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.CAPABILITY_NOT_DECLARED)
        }

        val persistentGrantMatches = request.resourceTarget?.let { target ->
            PersistentGrantPolicy.decide(
                request.capability, target, context.persistentGrants, context.currentTimeMillis()
            ) == PersistentGrantDecision.ALLOW
        } ?: false
        val hasGrant = request.capability in context.grantedCapabilities || persistentGrantMatches
        val approved = request.capability in context.explicitlyApprovedCapabilities || persistentGrantMatches
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

        if (request.capability !in descriptor.capabilities) {
            return AgentToolExecutionResult(AgentToolExecutionStatus.CAPABILITY_NOT_DECLARED)
        }

        val persistentGrantMatches = request.resourceTarget?.let { target ->
            PersistentGrantPolicy.decide(
                request.capability, target, context.persistentGrants, context.currentTimeMillis()
            ) == PersistentGrantDecision.ALLOW
        } ?: false
        val hasGrant = request.capability in context.grantedCapabilities || persistentGrantMatches
        val approved = request.capability in context.explicitlyApprovedCapabilities || persistentGrantMatches
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
            ?: return AgentToolExecutionResult(AgentToolExecutionStatus.HANDLER_NOT_REGISTERED)

        return try {
            AgentToolExecutionResult(
                AgentToolExecutionStatus.COMPLETED,
                handler.execute(request.input)
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
