package com.shev.agent.tools

interface GitHubCredentialStore {
    fun save(accessToken: String, refreshToken: String? = null)
    fun read(): String?
    fun readRefreshToken(): String?
    fun clear()
}

fun interface GitHubOAuthRefresher {
    fun refreshAccessToken(clientId: String, refreshToken: String): GitHubTokenRefreshResult
}

/**
 * Authenticated read-only facade. A 401 triggers one refresh-token rotation and
 * exactly one retry. Refresh credentials never leave the encrypted TokenStore.
 */
class AuthenticatedGitHubActionsClient(
    private val tokenStore: GitHubCredentialStore,
    private val clientId: String,
    private val oauthClient: GitHubOAuthRefresher = GitHubDeviceFlowClient(),
    private val baseTransport: GitHubHttpTransport = UrlConnectionGitHubHttpTransport(),
    private val apiBaseUrl: String = "https://api.github.com"
) {
    private val authenticatedTransport = object : GitHubHttpTransport {
        @Synchronized
        override fun get(url: String, token: String): GitHubHttpResponse {
            val access = tokenStore.read()
                ?: return GitHubHttpResponse(401, """{"message":"GitHub is not connected"}""")
            val first = baseTransport.get(url, access)
            if (first.statusCode != 401) return first

            val refresh = tokenStore.readRefreshToken()
                ?: return first
            val refreshed = oauthClient.refreshAccessToken(clientId, refresh)
            if (refreshed !is GitHubTokenRefreshResult.Refreshed) return first

            val newTokens = refreshed.tokens
            // Persist the rotated pair before retrying; never reuse the old refresh token.
            tokenStore.save(newTokens.accessToken, newTokens.refreshToken)
            return baseTransport.get(url, newTokens.accessToken)
        }
    }

    private val apiClient = GitHubActionsApiClient(authenticatedTransport, apiBaseUrl)

    fun verifyRun(
        expectedRepository: String,
        expectedWorkflowPath: String,
        expectedHeadSha: String,
        expectedRunId: Long
    ): GitHubActionsVerificationResult {
        if (clientId.isBlank()) {
            return GitHubActionsVerificationResult.Unavailable("GitHub App Client ID не настроен.")
        }
        if (tokenStore.read().isNullOrBlank()) {
            return GitHubActionsVerificationResult.Unavailable("Сначала подключите GitHub в разделе авторизации.")
        }
        // The transport obtains the current token from TokenStore on every request.
        return apiClient.verifyRun(
            expectedRepository, expectedWorkflowPath, expectedHeadSha,
            expectedRunId, token = "authenticated"
        )
    }
}
