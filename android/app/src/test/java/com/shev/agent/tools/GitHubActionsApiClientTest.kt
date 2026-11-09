package com.shev.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubActionsApiClientTest {
    private val runUrl = "https://api.github.com/repos/shevgti/SheV-Memory/actions/runs/42"
    private val jobsUrl = "$runUrl/jobs?per_page=100&page=1"
    private val runJson = """{
      "id": 42,
      "path": ".github/workflows/shev-agent-android.yml@refs/heads/main",
      "head_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "status": "completed",
      "conclusion": "success",
      "repository": {"full_name": "shevgti/SheV-Memory"}
    }"""
    private val jobsJson = """{
      "total_count": 1,
      "jobs": [{"id": 101, "name": "build", "conclusion": "success"}]
    }"""

    private class FakeTransport(
        private val responses: Map<String, GitHubHttpResponse>
    ) : GitHubHttpTransport {
        val requested = mutableListOf<String>()
        override fun get(url: String, token: String): GitHubHttpResponse {
            requested += url
            assertEquals("test-token", token)
            return responses[url] ?: error("Unexpected URL: $url")
        }
    }

    private fun client(transport: GitHubHttpTransport) =
        GitHubActionsApiClient(transport, "https://api.github.com")

    private fun verify(transport: GitHubHttpTransport, token: String = "test-token") =
        client(transport).verifyRun(
            "shevgti/SheV-Memory",
            ".github/workflows/shev-agent-android.yml",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            42,
            token
        )

    @Test fun verifiesExactRunAndAllJobs() {
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(200, jobsJson)
            )
        )
        val result = verify(transport)
        assertTrue(result is GitHubActionsVerificationResult.Verified)
        assertEquals(2, transport.requested.size)
    }

    @Test fun rejectsRunWithDifferentSha() {
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson.replace("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "dddddddddddddddddddddddddddddddddddddddd")),
                jobsUrl to GitHubHttpResponse(200, jobsJson)
            )
        )
        assertTrue(verify(transport) is GitHubActionsVerificationResult.Rejected)
    }

    @Test fun rejectsIncompleteJobsWhenNoNextPageExists() {
        val incomplete = jobsJson.replace("\"total_count\": 1", "\"total_count\": 2")
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(200, incomplete)
            )
        )
        assertTrue(verify(transport) is GitHubActionsVerificationResult.Unavailable)
    }

    @Test fun followsNextPageAndCollectsAllJobs() {
        val pageTwo = "$runUrl/jobs?per_page=100&page=2"
        val firstPage = """{"total_count":2,"jobs":[{"id":101,"name":"build","conclusion":"success"}]}"""
        val secondPage = """{"total_count":2,"jobs":[{"id":102,"name":"tests","conclusion":"success"}]}"""
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(
                    200, firstPage,
                    mapOf("Link" to "<$pageTwo>; rel=\"next\", <$jobsUrl>; rel=\"first\"")
                ),
                pageTwo to GitHubHttpResponse(200, secondPage)
            )
        )
        val result = verify(transport)
        assertTrue(result is GitHubActionsVerificationResult.Verified)
        assertEquals(3, transport.requested.size)
    }

    @Test fun reportsRateLimitAsUnavailableNotTestFailure() {
        val transport = FakeTransport(
            mapOf(runUrl to GitHubHttpResponse(403, "{}", mapOf("X-RateLimit-Remaining" to "0")))
        )
        val result = verify(transport)
        assertTrue(result is GitHubActionsVerificationResult.Unavailable)
        assertTrue((result as GitHubActionsVerificationResult.Unavailable).reason.contains("лимит"))
    }

    @Test fun rejectsMalformedExpectedShaBeforeNetworkAccess() {
        val transport = FakeTransport(emptyMap())
        val result = client(transport).verifyRun(
            "shevgti/SheV-Memory", ".github/workflows/shev-agent-android.yml",
            "abc123", 42, "test-token"
        )
        assertTrue(result is GitHubActionsVerificationResult.Rejected)
        assertEquals(0, transport.requested.size)
    }

    @Test fun refusesBlankTokenBeforeNetworkAccess() {
        val transport = FakeTransport(emptyMap())
        assertTrue(verify(transport, " ") is GitHubActionsVerificationResult.Unavailable)
        assertEquals(0, transport.requested.size)
    }

    @Test fun rejectsPaginationLinkToUntrustedHost() {
        val firstPage = """{"total_count":2,"jobs":[{"id":101,"name":"build","conclusion":"success"}]}"""
        val link = "<https://evil.example/repos/shevgti/SheV-Memory/actions/runs/42/jobs?per_page=100&page=2>; rel=\"next\""
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(200, firstPage, mapOf("Link" to link))
            )
        )
        val result = verify(transport)
        assertTrue(result is GitHubActionsVerificationResult.Unavailable)
        assertEquals(2, transport.requested.size)
    }

    @Test fun rejectsDuplicateJobIdsAcrossPages() {
        val pageTwo = "$runUrl/jobs?per_page=100&page=2"
        val firstPage = """{"total_count":2,"jobs":[{"id":101,"name":"build","conclusion":"success"}]}"""
        val secondPage = """{"total_count":2,"jobs":[{"id":101,"name":"tests","conclusion":"success"}]}"""
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(200, firstPage, mapOf("Link" to "<$pageTwo>; rel=\"next\"")),
                pageTwo to GitHubHttpResponse(200, secondPage)
            )
        )
        assertTrue(verify(transport) is GitHubActionsVerificationResult.Unavailable)
    }


    @Test fun rejectsRedirectResponseInsteadOfFollowingIt() {
        val transport = FakeTransport(
            mapOf(runUrl to GitHubHttpResponse(302, "", mapOf("Location" to "https://evil.example/steal")))
        )
        val result = verify(transport)
        assertTrue(result is GitHubActionsVerificationResult.Unavailable)
        assertEquals(1, transport.requested.size)
    }

    @Test fun rejectsSameHostPaginationLinkWithUnexpectedPath() {
        val firstPage = """{"total_count":2,"jobs":[{"id":101,"name":"build","conclusion":"success"}]}"""
        val badPath = "https://api.github.com/repos/other/repo/actions/runs/42/jobs?per_page=100&page=2"
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(200, firstPage, mapOf("Link" to "<$badPath>; rel=\"next\""))
            )
        )
        assertTrue(verify(transport) is GitHubActionsVerificationResult.Unavailable)
        assertEquals(2, transport.requested.size)
    }

    @Test fun rejectsJobWithoutStableId() {
        val malformed = """{"total_count":1,"jobs":[{"name":"build","conclusion":"success"}]}"""
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(200, malformed)
            )
        )
        assertTrue(verify(transport) is GitHubActionsVerificationResult.Unavailable)
    }


    @Test fun rejectsInsecureOrUntrustedApiOriginBeforeSendingToken() {
        val transport = FakeTransport(emptyMap())
        val httpClient = GitHubActionsApiClient(transport, "http://api.github.com")
        val result = httpClient.verifyRun(
            "shevgti/SheV-Memory", ".github/workflows/shev-agent-android.yml",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 42, "test-token"
        )
        assertTrue(result is GitHubActionsVerificationResult.Rejected)
        assertEquals(0, transport.requested.size)
    }

    @Test fun rejectsUntrustedApiHostBeforeSendingToken() {
        val transport = FakeTransport(emptyMap())
        val httpClient = GitHubActionsApiClient(transport, "https://evil.example")
        val result = httpClient.verifyRun(
            "shevgti/SheV-Memory", ".github/workflows/shev-agent-android.yml",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 42, "test-token"
        )
        assertTrue(result is GitHubActionsVerificationResult.Rejected)
        assertEquals(0, transport.requested.size)
    }

    @Test fun rejectsMalformedRepositoryAndNonPositiveRunIdBeforeNetworkAccess() {
        val transport = FakeTransport(emptyMap())
        val api = client(transport)
        val validSha = "a".repeat(40)
        assertTrue(api.verifyRun("../owner/repo", ".github/workflows/shev-agent-android.yml", validSha, 42, "test-token")
            is GitHubActionsVerificationResult.Rejected)
        assertTrue(api.verifyRun("shevgti/SheV-Memory", ".github/workflows/shev-agent-android.yml", validSha, 0, "test-token")
            is GitHubActionsVerificationResult.Rejected)
        assertEquals(0, transport.requested.size)
    }

    @Test fun rejectsChangingTotalCountAcrossPages() {
        val pageTwo = "$runUrl/jobs?per_page=100&page=2"
        val firstPage = """{"total_count":2,"jobs":[{"id":101,"name":"build","conclusion":"success"}]}"""
        val secondPage = """{"total_count":3,"jobs":[{"id":102,"name":"tests","conclusion":"success"}]}"""
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(200, firstPage, mapOf("Link" to "<$pageTwo>; rel=\"next\"")),
                pageTwo to GitHubHttpResponse(200, secondPage)
            )
        )
        assertTrue(verify(transport) is GitHubActionsVerificationResult.Unavailable)
    }


    @Test fun rejectsPaginationThatRepeatsThePreviousPage() {
        val firstPage = """{"total_count":2,"jobs":[{"id":101,"name":"build","conclusion":"success"}]}"""
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(200, firstPage, mapOf("Link" to "<$jobsUrl>; rel=\"next\""))
            )
        )
        assertTrue(verify(transport) is GitHubActionsVerificationResult.Unavailable)
        assertEquals(2, transport.requested.size)
    }

    @Test fun rejectsPaginationUrlWithEmbeddedUserInfo() {
        val firstPage = """{"total_count":2,"jobs":[{"id":101,"name":"build","conclusion":"success"}]}"""
        val unsafe = "https://attacker@api.github.com/repos/shevgti/SheV-Memory/actions/runs/42/jobs?per_page=100&page=2"
        val transport = FakeTransport(
            mapOf(
                runUrl to GitHubHttpResponse(200, runJson),
                jobsUrl to GitHubHttpResponse(200, firstPage, mapOf("Link" to "<$unsafe>; rel=\"next\""))
            )
        )
        assertTrue(verify(transport) is GitHubActionsVerificationResult.Unavailable)
        assertEquals(2, transport.requested.size)
    }

}
