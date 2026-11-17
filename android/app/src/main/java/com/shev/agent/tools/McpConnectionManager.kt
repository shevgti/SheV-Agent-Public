package com.shev.agent.tools

import com.shev.agent.core.McpInvocationAuthorizationProvider
import com.shev.agent.core.McpInvocationAuthorizationVerifier
import com.shev.agent.core.McpServerProfile
import com.shev.agent.core.RemoteMcpClient
import com.shev.agent.security.KeystoreMcpCredentialProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class McpConnectionStatus {
    CONNECTED, PROFILE_DISABLED, ALREADY_CONNECTED, NO_APPROVED_TOOLS, CONNECTION_FAILED
}

data class McpConnectionResult(
    val status: McpConnectionStatus,
    val registeredToolIds: List<String> = emptyList(),
    val rejectedToolNames: List<String> = emptyList(),
    val errorCode: String? = null
)

/**
 * Kernel-owned ephemeral MCP connection lifecycle.
 * Pairing/profile approval happens outside this class; this manager never enables
 * a disabled profile or expands its exact tool allowlist.
 */
class McpConnectionManager(
    private val registry: AgentToolRegistry,
    private val executor: AgentToolExecutor,
    private val credentials: KeystoreMcpCredentialProvider,
    private val authorizationProvider: McpInvocationAuthorizationProvider,
    private val authorizationVerifier: McpInvocationAuthorizationVerifier
) {
    private data class ActiveConnection(
        val client: RemoteMcpClient,
        val toolIds: List<String>,
        val rejectedToolNames: List<String>
    )

    private val mutex = Mutex()
    private val active = linkedMapOf<String, ActiveConnection>()

    suspend fun connect(profile: McpServerProfile): McpConnectionResult = mutex.withLock {
        if (!profile.enabled) return@withLock McpConnectionResult(McpConnectionStatus.PROFILE_DISABLED)
        if (profile.id in active) return@withLock McpConnectionResult(McpConnectionStatus.ALREADY_CONNECTED)

        val beforeIds = registry.list().map { it.id }.toSet()
        val installedHandlers = mutableListOf<String>()
        val client = RemoteMcpClient(profile) { requestedProfile ->
            credentials.get(requestedProfile)
        }
        try {
            val discovered = client.connectAndDiscover()
            val registration = McpToolRegistryBridge(
                profile = profile,
                remoteClient = client,
                authorizationProvider = authorizationProvider,
                authorizationVerifier = authorizationVerifier
            ).register(discovered, registry)

            if (registration.registeredToolIds.isEmpty()) {
                client.disconnect()
                return@withLock McpConnectionResult(
                    McpConnectionStatus.NO_APPROVED_TOOLS,
                    rejectedToolNames = registration.rejectedToolNames
                )
            }

            for (toolId in registration.registeredToolIds) {
                val handler = registration.contextualSuspendingHandlers[toolId]
                    ?: error("MCP_HANDLER_MISSING")
                check(executor.registerContextualSuspendingHandler(toolId, handler)) {
                    "MCP_HANDLER_REGISTRATION_CONFLICT"
                }
                installedHandlers += toolId
            }

            active[profile.id] = ActiveConnection(
                client = client,
                toolIds = registration.registeredToolIds,
                rejectedToolNames = registration.rejectedToolNames
            )
            McpConnectionResult(
                McpConnectionStatus.CONNECTED,
                registration.registeredToolIds,
                registration.rejectedToolNames
            )
        } catch (cancelled: CancellationException) {
            installedHandlers.forEach(executor::unregisterContextualSuspendingHandler)
            registry.list().filter { it.id !in beforeIds }.forEach { registry.unregister(it.id) }
            runCatching { client.disconnect() }
            throw cancelled
        } catch (failure: Exception) {
            installedHandlers.forEach(executor::unregisterContextualSuspendingHandler)
            registry.list().filter { it.id !in beforeIds }.forEach { registry.unregister(it.id) }
            runCatching { client.disconnect() }
            McpConnectionResult(
                McpConnectionStatus.CONNECTION_FAILED,
                errorCode = when (failure.message) {
                    "MCP_HANDLER_MISSING" -> "HANDLER_MISSING"
                    "MCP_HANDLER_REGISTRATION_CONFLICT" -> "HANDLER_CONFLICT"
                    else -> "MCP_CONNECTION_OR_REGISTRATION_FAILED"
                }
            )
        }
    }

    suspend fun disconnect(profileId: String): Boolean = mutex.withLock {
        val connection = active.remove(profileId) ?: return@withLock false
        connection.toolIds.forEach { toolId ->
            executor.unregisterContextualSuspendingHandler(toolId)
            registry.unregister(toolId)
        }
        connection.client.disconnect()
        true
    }

    suspend fun disconnectAll() = mutex.withLock {
        val connections = active.values.toList()
        active.clear()
        connections.forEach { connection ->
            connection.toolIds.forEach { toolId ->
                executor.unregisterContextualSuspendingHandler(toolId)
                registry.unregister(toolId)
            }
            runCatching { connection.client.disconnect() }
        }
    }

    suspend fun connectedProfileIds(): Set<String> = mutex.withLock { active.keys.toSet() }
}
