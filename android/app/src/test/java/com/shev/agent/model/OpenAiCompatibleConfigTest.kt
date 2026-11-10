package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleConfigTest {
    private fun config(url: String) = OpenAiCompatibleConfig(url, "qwen-local", "secret-token")

    @Test fun acceptsHttpsBaseUrlAndNormalizesV1Path() {
        assertEquals("https://agent.example:8443/v1", config("https://agent.example:8443").validatedBaseUrl())
        assertEquals("https://agent.example/v1", config("https://agent.example/v1/").validatedBaseUrl())
    }

    @Test fun acceptsOpenRouterApiV1WithoutRewritingItsPath() {
        assertEquals(
            "https://openrouter.ai/api/v1",
            OpenAiCompatibleConfig("https://openrouter.ai/api/v1", "openrouter/free", "secret-token", "OpenRouter").validatedBaseUrl()
        )
        assertEquals(
            "https://agent.example/api/v1",
            config("https://agent.example/api/v1/").validatedBaseUrl()
        )
    }

    @Test fun acceptsGeminiOpenAiCompatibilityPath() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            config("https://generativelanguage.googleapis.com/v1beta/openai/").validatedBaseUrl()
        )
    }

    @Test fun rejectsPlainHttpForAllEndpoints() {
        assertFalse(runCatching { config("http://127.0.0.1:20128/v1").validatedBaseUrl() }.isSuccess)
        assertFalse(runCatching { config("http://192.168.1.20:1234/v1").validatedBaseUrl() }.isSuccess)
    }

    @Test fun rejectsUserInfoQueryAndFragment() {
        listOf(
            "https://user:pass@agent.example/v1",
            "https://agent.example/v1?token=secret",
            "https://agent.example/v1#fragment"
        ).forEach { url ->
            assertTrue(runCatching { config(url).validatedBaseUrl() }.isFailure)
        }
    }

    @Test fun rejectsUnexpectedPathAndMissingCredentials() {
        assertTrue(runCatching { config("https://agent.example/api").validatedBaseUrl() }.isFailure)
        assertTrue(runCatching { config("https://agent.example/api/v1/extra").validatedBaseUrl() }.isFailure)
        assertTrue(runCatching { OpenAiCompatibleConfig("https://agent.example/v1", " ", "secret").validatedBaseUrl() }.isFailure)
        assertTrue(runCatching { OpenAiCompatibleConfig("https://agent.example/v1", "model", "").validatedBaseUrl() }.isFailure)
    }
}
