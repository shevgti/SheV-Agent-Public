package com.shev.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedGitHubActionsClientTest {
    private class FakeStore(
        var access: String? = "old-access",
        var refresh: String? = "old-refresh"
    ) : GitHubCredentialStore {
        var saves = 0
        override fun save(accessToken: String, refreshToken: String?) {
            access = accessToken
            refresh = refreshToken
            saves++
        }
        override fun read() = access
        override fun readRefreshToken() = refresh
        override fun clear() { access = null; refresh = null }
    }

    private class FakeRefresher(
        private val result: GitHubTokenRefreshResult
    ) : GitHubOAuthRefresher {
        var calls = 0
        var suppliedRefresh: String? = null
        override fun refreshAccessToken(clientId: String, refreshToken: String): GitHubTokenRefreshResult {
            calls++
            suppliedRefresh = refreshToken
            return result
        }
    }

    private class FakeTransport : GitHubHttpTransport {
        val calls = mutableListOf<Pair<String, String>>()
        override fun get(url: String, token: String): GitHubHttpResponse {
            calls += url to token
            if (token == "old-access") return GitHubHttpResponse(401, "{}")
            if (token == "new-access" && url.endsWith("/actions/runs/42")) {
                return GitHubHttpResponse(200, """{
                    "id":42,
                    "path":".github/workflows/shev-agent-android.yml@refs/heads/main",
                    "head_sha":"${"a".repeat(40)}",
                    "status":"completed",
                    "conclusion":"success",
                    "repository":{"full_name":"shevgti/SheV-Memory"}
                }""")
            }
            if (token == "new-access" && url.contains("/actions/runs/42/jobs")) {
                return GitHubHttpResponse(200, """{"total_count":1,"jobs":[{"id":7,"name":"build","conclusion":"success"}]}""")
            }
            return GitHubHttpResponse(401, "{}")
        }
    }

    @Test fun refreshesRotatedTokensOn401AndRetriesWithNewAccessToken() {
        val store = FakeStore()
        val refresher = FakeRefresher(
            GitHubTokenRefreshResult.Refreshed(
                GitHubRefreshedTokens("new-access", "new-refresh", 3600, 86400)
            )
        )
        val transport = FakeTransport()
        val client = AuthenticatedGitHubActionsClient(
            store, "Iv1.validClientId123", refresher, transport
        )
        val result = client.verifyRun(
            "shevgti/SheV-Memory",
            ".github/workflows/shev-agent-android.yml",
            "a".repeat(40),
            42
        )
        assertTrue(result is GitHubActionsVerificationResult.Verified)
        assertEquals(1, refresher.calls)
        assertEquals("old-refresh", refresher.suppliedRefresh)
        assertEquals("new-access", store.access)
        assertEquals("new-refresh", store.refresh)
        assertEquals(1, store.saves)
        assertEquals(listOf("old-access", "new-access", "new-access"), transport.calls.map { it.second })
    }

    @Test fun doesNotRetryWhenRefreshFails() {
        val store = FakeStore()
        val refresher = FakeRefresher(GitHubTokenRefreshResult.Failed("expired"))
        val transport = FakeTransport()
        val client = AuthenticatedGitHubActionsClient(
            store, "Iv1.validClientId123", refresher, transport
        )
        val result = client.verifyRun(
            "shevgti/SheV-Memory",
            ".github/workflows/shev-agent-android.yml",
            "a".repeat(40),
            42
        )
        assertTrue(result is GitHubActionsVerificationResult.Unavailable)
        assertEquals(1, refresher.calls)
        assertEquals(1, transport.calls.size)
        assertEquals("old-access", store.access)
        assertEquals("old-refresh", store.refresh)
        assertEquals(0, store.saves)
    }

    @Test fun doesNotRefreshMoreThanOnceForTheSameRequest() {
        val store = FakeStore()
        val refresher = FakeRefresher(
            GitHubTokenRefreshResult.Refreshed(
                GitHubRefreshedTokens("new-access", "new-refresh", 3600, 86400)
            )
        )
        val transport = object : GitHubHttpTransport {
            var calls = 0
            override fun get(url: String, token: String): GitHubHttpResponse {
                calls++
                return GitHubHttpResponse(401, "{}")
            }
        }
        val client = AuthenticatedGitHubActionsClient(
            store, "Iv1.validClientId123", refresher, transport
        )
        val result = client.verifyRun(
            "shevgti/SheV-Memory",
            ".github/workflows/shev-agent-android.yml",
            "a".repeat(40),
            42
        )
        assertTrue(result is GitHubActionsVerificationResult.Unavailable)
        assertEquals(1, refresher.calls)
        assertEquals(2, transport.calls)
        assertEquals("new-refresh", store.refresh)
    }
}
