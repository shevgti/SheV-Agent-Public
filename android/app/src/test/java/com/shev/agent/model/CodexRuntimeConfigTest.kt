package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexRuntimeConfigTest {
    @Test fun codexInstructionsRequireRealSearchAndSourceLinks() {
        assertTrue(CodexRuntimeConfig.DEVELOPER_INSTRUCTIONS.contains("используй встроенный web search"))
        assertTrue(CodexRuntimeConfig.DEVELOPER_INSTRUCTIONS.contains("добавляй прямые ссылки"))
        assertTrue(CodexRuntimeConfig.DEVELOPER_INSTRUCTIONS.contains("не заявляй о поиске", ignoreCase = true))
    }

    @Test fun newInstallEnablesLiveWebSearchAndKeepsReadOnlySandbox() {
        val config = CodexRuntimeConfig.withLiveWebSearchDefault(null)
        assertTrue(config.contains("approval_policy = \"on-request\""))
        assertTrue(config.contains("sandbox_mode = \"read-only\""))
        assertTrue(config.contains("web_search = \"live\""))
    }

    @Test fun existingConfigGetsLiveSearchWithoutLosingOtherSettings() {
        val existing = "approval_policy = \"on-request\"\nmodel_reasoning_effort = \"high\"\n"
        val config = CodexRuntimeConfig.withLiveWebSearchDefault(existing)
        assertTrue(config.contains("model_reasoning_effort = \"high\""))
        assertTrue(config.contains("web_search = \"live\""))
    }

    @Test fun explicitSearchModeIsPreserved() {
        val existing = "web_search = \"disabled\"\n"
        assertEquals(existing, CodexRuntimeConfig.withLiveWebSearchDefault(existing))
    }

    @Test fun doesNotDuplicateExistingSearchSetting() {
        val existing = "web_search = \"cached\"\n"
        assertEquals(existing, CodexRuntimeConfig.withLiveWebSearchDefault(existing))
    }
}
