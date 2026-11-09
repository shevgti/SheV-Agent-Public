package com.shev.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDeviceFlowClientTest {
    private class FakeTransport(var response: GitHubOAuthHttpResponse) : GitHubOAuthTransport {
        var url: String? = null
        var parameters: Map<String, String>? = null
        override fun postForm(url: String, parameters: Map<String, String>): GitHubOAuthHttpResponse {
            this.url = url
            this.parameters = parameters
            return response
        }
    }

    @Test fun requestsDeviceCodeFromOfficialEndpoint() {
        val transport = FakeTransport(
            GitHubOAuthHttpResponse(200, """{"device_code":"secret-device","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""")
        )
        val result = GitHubDeviceFlowClient(transport).requestDeviceCode("Iv1.validClientId123")
        assertTrue(result.isSuccess)
        assertEquals("ABCD-EFGH", result.getOrThrow().userCode)
        assertEquals("https://github.com/login/device/code", transport.url)
        assertEquals("Iv1.validClientId123", transport.parameters?.get("client_id"))
        assertEquals("offline_access", transport.parameters?.get("scope"))
    }

    @Test fun rejectsUntrustedVerificationUrl() {
        val transport = FakeTransport(
            GitHubOAuthHttpResponse(200, """{"device_code":"d","user_code":"ABCD","verification_uri":"https://evil.example/login/device","expires_in":900}""")
        )
        assertTrue(GitHubDeviceFlowClient(transport).requestDeviceCode("Iv1.validClientId123").isFailure)
    }

    @Test fun mapsPendingAndDeniedResponses() {
        val pending = FakeTransport(GitHubOAuthHttpResponse(200, """{"error":"authorization_pending"}"""))
        assertEquals(GitHubDevicePollResult.Pending,
            GitHubDeviceFlowClient(pending).pollForAccessToken("Iv1.validClientId123", "device"))
        val denied = FakeTransport(GitHubOAuthHttpResponse(200, """{"error":"access_denied"}"""))
        assertEquals(GitHubDevicePollResult.Denied,
            GitHubDeviceFlowClient(denied).pollForAccessToken("Iv1.validClientId123", "device"))
    }

    @Test fun refreshesAccessTokenAndRequiresRotatedRefreshToken() {
        val transport = FakeTransport(
            GitHubOAuthHttpResponse(200, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":28800,"refresh_token_expires_in":15897600,"token_type":"bearer"}""")
        )
        val result = GitHubDeviceFlowClient(transport).refreshAccessToken("Iv1.validClientId123", "old-refresh")
        assertTrue(result is GitHubTokenRefreshResult.Refreshed)
        val tokens = (result as GitHubTokenRefreshResult.Refreshed).tokens
        assertEquals("new-access", tokens.accessToken)
        assertEquals("new-refresh", tokens.refreshToken)
        assertEquals("refresh_token", transport.parameters?.get("grant_type"))
        assertEquals("old-refresh", transport.parameters?.get("refresh_token"))
        assertEquals("Iv1.validClientId123", transport.parameters?.get("client_id"))
    }

    @Test fun doesNotAcceptRefreshResponseWithoutRotatedToken() {
        val transport = FakeTransport(
            GitHubOAuthHttpResponse(200, """{"access_token":"new-access","expires_in":28800}""")
        )
        val result = GitHubDeviceFlowClient(transport).refreshAccessToken("Iv1.validClientId123", "old-refresh")
        assertTrue(result is GitHubTokenRefreshResult.Failed)
    }

    @Test fun returnsAccessTokenOnlyToCaller() {
        val transport = FakeTransport(
            GitHubOAuthHttpResponse(200, """{"access_token":"token-value","token_type":"bearer","scope":""}""")
        )
        val result = GitHubDeviceFlowClient(transport).pollForAccessToken("Iv1.validClientId123", "device")
        assertTrue(result is GitHubDevicePollResult.Authorized)
        assertEquals("token-value", (result as GitHubDevicePollResult.Authorized).accessToken)
    }
}
