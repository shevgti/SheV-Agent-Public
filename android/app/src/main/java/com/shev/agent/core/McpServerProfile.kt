package com.shev.agent.core

import java.net.URI

/**
 * User-approved remote MCP endpoint configuration.
 *
 * This is metadata only: it never connects, stores credentials, or grants tools.
 * Actual connections must be created by the kernel-owned MCP gateway and then
 * projected into AgentToolRegistry with per-tool capability checks.
 */
enum class McpTransportKind { STREAMABLE_HTTP }
enum class McpAuthenticationKind { NONE, OAUTH, BEARER_TOKEN }
enum class McpServerTrust { OFFICIAL, VERIFIED_VENDOR, COMMUNITY, USER_HOSTED }

data class McpServerProfile(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val transport: McpTransportKind = McpTransportKind.STREAMABLE_HTTP,
    val authentication: McpAuthenticationKind,
    val trust: McpServerTrust,
    val enabled: Boolean = false,
    val approvedToolNames: Set<String> = emptySet(),
    val notes: String = ""
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        require(displayName.isNotBlank() && displayName.length <= 100)
        require(notes.length <= 1_000)
        require(approvedToolNames.size <= 100)
        require(approvedToolNames.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")) })
        val uri = URI(endpoint)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Remote MCP endpoints must use HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "MCP endpoint must include a host" }
        require(uri.userInfo == null && uri.fragment == null) {
            "MCP endpoint must not embed credentials or fragments"
        }
        require(uri.port == -1 || uri.port in 1..65535)
        require(uri.path.isNullOrBlank() || uri.path.startsWith("/"))
    }
}

/** Curated catalogue entries; all integrations are disabled until explicitly paired. */
object CuratedMcpProfiles {
    val entries = listOf(
        McpServerProfile(
            id = "context7",
            displayName = "Context7",
            endpoint = "https://mcp.context7.com/mcp",
            authentication = McpAuthenticationKind.BEARER_TOKEN,
            trust = McpServerTrust.OFFICIAL,
            notes = "Актуальная документация библиотек и API. Каталог рекомендует API-ключ для более высоких лимитов."
        ),
        McpServerProfile(
            id = "github",
            displayName = "GitHub MCP",
            endpoint = "https://api.githubcopilot.com/mcp/",
            authentication = McpAuthenticationKind.OAUTH,
            trust = McpServerTrust.OFFICIAL,
            notes = "Репозитории, issues, pull requests и GitHub Actions. Доступ только в пределах выданных пользователем разрешений."
        )
    )
}

/**
 * A catalog capability gate. Discovery from an MCP server never automatically
 * makes a tool callable: only explicitly approved names are exposed to the kernel.
 */
object McpToolAllowlist {
    fun isAllowed(profile: McpServerProfile, discoveredToolName: String): Boolean =
        profile.enabled &&
            discoveredToolName.isNotBlank() &&
            discoveredToolName in profile.approvedToolNames
}
