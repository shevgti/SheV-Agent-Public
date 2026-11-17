package com.shev.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentSkillManifestTest {
    private fun skill(
        enabled: Boolean = true,
        tools: Set<String> = setOf("docs.lookup")
    ) = AgentSkillManifest(
        id = "android-docs",
        name = "Android documentation research",
        version = "1.0.0",
        description = "Find version-correct Android API documentation",
        triggerPhrases = listOf("Android API", "Gradle dependency"),
        requiredToolIds = tools,
        instructionText = "Find the exact library version, then retrieve its official documentation.",
        sourceUri = "https://example.org/skills/android-docs",
        sha256 = "a".repeat(64),
        trust = AgentSkillTrust.REVIEWED_COMMUNITY,
        enabled = enabled
    )

    @Test
    fun enabledSkillActivatesOnlyWhenRequiredToolsExist() {
        assertTrue(AgentSkillPolicy.canActivate(skill(), setOf("docs.lookup")))
        assertFalse(AgentSkillPolicy.canActivate(skill(), emptySet()))
    }

    @Test
    fun disabledSkillNeverActivates() {
        assertFalse(AgentSkillPolicy.canActivate(skill(enabled = false), setOf("docs.lookup")))
    }

    @Test
    fun rejectsNonHttpsSkillSource() {
        assertThrows(IllegalArgumentException::class.java) {
            skill().copy(sourceUri = "http://example.org/skill")
        }
    }

    @Test
    fun rejectsInvalidVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            skill().copy(version = "latest")
        }
    }

    @Test
    fun skillCannotRequestMalformedToolIdentifiers() {
        assertThrows(IllegalArgumentException::class.java) {
            skill(tools = setOf("../shell"))
        }
    }
}
