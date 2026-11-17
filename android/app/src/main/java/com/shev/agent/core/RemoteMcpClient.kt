package com.shev.agent.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Remote MCP connection owner. Only this adapter speaks the MCP protocol;
 * callers still need to route every discovered tool through AgentToolExecutor.
 *
 * Credentials are supplied by the caller for a single connection attempt and
 * are never persisted or included in tool descriptions/results.
 */
class RemoteMcpClient(
    private val profile: McpServerProfile,
    private val credentialProvider: suspend (McpServerProfile) -> String?
) {
    data class DiscoveredTool(
        val name: String,
        val description: String,
        val inputSchema: ToolSchema
    )

    private val mutex = Mutex()
    private var httpClient: HttpClient? = null
    private var mcpClient: Client? = null

    suspend fun connectAndDiscover(): List<DiscoveredTool> = mutex.withLock {
        check(profile.enabled) { "MCP server profile is disabled" }
        closeLocked()
        McpEndpointPolicy.validate(profile)

        val credential = credentialProvider(profile)
        when (profile.authentication) {
            McpAuthenticationKind.NONE -> require(credential.isNullOrBlank()) {
                "This MCP profile does not accept credentials"
            }
            McpAuthenticationKind.OAUTH,
            McpAuthenticationKind.BEARER_TOKEN -> require(!credential.isNullOrBlank()) {
                "Authentication is required for this MCP server"
            }
        }

        val engine = HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
        }
        val client = Client(
            clientInfo = Implementation(
                name = "shev-agent",
                version = "0.3.1"
            )
        )
        try {
            val transport = StreamableHttpClientTransport(
                client = engine,
                url = profile.endpoint,
                requestBuilder = {
                    if (!credential.isNullOrBlank()) {
                        header("Authorization", "Bearer $credential")
                    }
                }
            )
            client.connect(transport)
            val discovered = client.listTools().tools.map { tool ->
                DiscoveredTool(
                    name = tool.name,
                    description = tool.description.orEmpty().take(1_000),
                    inputSchema = tool.inputSchema
                )
            }.take(MAX_DISCOVERED_TOOLS)
            httpClient = engine
            mcpClient = client
            discovered
        } catch (error: Exception) {
            runCatching { client.close() }
            engine.close()
            throw error
        }
    }

    /**
     * Call only a tool that the user explicitly approved in this profile.
     * Arguments must already have passed the native JSON-schema validator.
     */
    suspend fun callApprovedTool(
        nativeToolId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        authorization: McpInvocationAuthorization,
        authorizationVerifier: McpInvocationAuthorizationVerifier,
        executionContext: McpInvocationExecutionContext
    ): String = mutex.withLock {
        require(McpToolAllowlist.isAllowed(profile, toolName)) {
            "MCP tool is not explicitly approved"
        }
        require(arguments.size <= MAX_ARGUMENTS) { "Too many MCP tool arguments" }
        val client = checkNotNull(mcpClient) { "MCP server is not connected" }
        val expectedBinding = McpInvocationBinding.create(
            profile = profile,
            nativeToolId = nativeToolId,
            toolName = toolName,
            arguments = arguments
        )
        require(authorization.binding == expectedBinding) {
            "MCP invocation approval does not match this server, tool or argument set"
        }
        require(
            authorization.invocationId == executionContext.invocationId &&
                authorization.taskId == executionContext.taskId &&
                authorization.taskRevision == executionContext.taskRevision &&
                authorization.planRevision == executionContext.planRevision &&
                authorization.stepId == executionContext.stepId &&
                authorization.stepRevision == executionContext.stepRevision
        ) {
            "MCP invocation approval does not match the current persisted task and step"
        }
        val nowMillis = System.currentTimeMillis()
        require(
            authorization.issuedAtEpochMillis <= nowMillis &&
                authorization.expiresAtEpochMillis > nowMillis
        ) { "MCP invocation approval has expired or is not yet valid" }
        require(authorizationVerifier.consume(authorization, expectedBinding, nowMillis)) {
            "Agent Kernel rejected or already consumed this MCP invocation approval"
        }
        // Consume the one-use authorization immediately before the network side effect.
        val result = client.callTool(
            name = toolName,
            arguments = arguments
        )
        result.content.toString().take(MAX_RESULT_CHARS)
    }

    suspend fun disconnect() = mutex.withLock { closeLocked() }

    private suspend fun closeLocked() {
        val client = mcpClient
        mcpClient = null
        httpClient?.close()
        httpClient = null
        if (client != null) runCatching { client.close() }
    }

    companion object {
        const val MAX_DISCOVERED_TOOLS = 200
        const val MAX_ARGUMENTS = 64
        const val MAX_RESULT_CHARS = 24_000
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val SOCKET_TIMEOUT_MILLIS = 30_000L
    }
}
