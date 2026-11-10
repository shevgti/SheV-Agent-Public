package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAIProviderConfigTest {
    @Test fun acceptsOfficialOpenAiApiEndpoint() {
        val config = OpenAiCompatibleConfig(
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-4.1-mini",
            apiToken = "test-token"
        )
        assertEquals("https://api.openai.com/v1", config.validatedBaseUrl())
    }

    @Test fun rejectsInsecureHttpEndpoint() {
        val config = OpenAiCompatibleConfig(
            baseUrl = "http://api.openai.com/v1",
            modelId = "gpt-4.1-mini",
            apiToken = "test-token"
        )
        assertThrows(IllegalArgumentException::class.java) { config.validatedBaseUrl() }
    }

    @Test fun rejectsQueryParametersAndUnexpectedPaths() {
        val withQuery = OpenAiCompatibleConfig(
            baseUrl = "https://api.openai.com/v1?token=secret",
            modelId = "gpt-4.1-mini",
            apiToken = "test-token"
        )
        assertThrows(IllegalArgumentException::class.java) { withQuery.validatedBaseUrl() }

        val unexpectedPath = OpenAiCompatibleConfig(
            baseUrl = "https://api.openai.com/proxy/v1",
            modelId = "gpt-4.1-mini",
            apiToken = "test-token"
        )
        assertThrows(IllegalArgumentException::class.java) { unexpectedPath.validatedBaseUrl() }
    }

    @Test fun rejectsEmptyCredentialsAndModelIds() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiCompatibleConfig("https://api.openai.com/v1", "gpt-4.1-mini", "").validatedBaseUrl()
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiCompatibleConfig("https://api.openai.com/v1", "", "test-token").validatedBaseUrl()
        }
    }
}
