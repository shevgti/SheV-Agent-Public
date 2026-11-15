package com.shev.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanJsonCodecTest {
    private val valid = """{"summary":"Найти информацию","steps":[{"id":"search-1","title":"Поиск","toolId":"agent.web_search","capability":"READ_REMOTE_DATA","input":"{\\"query\\":\\"погода\\"}"}]}"""

    @Test
    fun decodesAndCanonicalizesAValidPlan() {
        val plan = AgentPlanJsonCodec.decode(valid)
        assertNotNull(plan)
        assertEquals("Найти информацию", plan?.summary)
        assertEquals("agent.web_search", plan?.steps?.single()?.toolId)
        val canonical = AgentPlanJsonCodec.canonicalJson(plan!!)
        assertEquals(canonical, AgentPlanJsonCodec.canonicalize(canonical))
        assertTrue(AgentPlanJsonCodec.sha256(canonical).matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun rejectsUnknownRootAndStepFields() {
        assertNull(AgentPlanJsonCodec.decode("""{"summary":"x","steps":[],"extra":true}"""))
        assertNull(AgentPlanJsonCodec.decode("""{"summary":"x","steps":[{"id":"s","title":"x","toolId":"agent.web_search","capability":"READ_REMOTE_DATA","input":"{}","extra":true}]}"""))
    }

    @Test
    fun rejectsDuplicateStepIdsAndUnknownCapabilities() {
        val duplicate = """{"summary":"x","steps":[{"id":"s","title":"one","toolId":"agent.web_search","capability":"READ_REMOTE_DATA","input":"{}"},{"id":"s","title":"two","toolId":"agent.web_search","capability":"READ_REMOTE_DATA","input":"{}"}]}"""
        val unknownCapability = """{"summary":"x","steps":[{"id":"s","title":"one","toolId":"agent.web_search","capability":"ADMIN","input":"{}"}]}"""
        assertNull(AgentPlanJsonCodec.decode(duplicate))
        assertNull(AgentPlanJsonCodec.decode(unknownCapability))
    }

    @Test
    fun planHashChangesWhenCanonicalPlanChanges() {
        val first = AgentPlanJsonCodec.decode(valid)!!
        val second = first.copy(summary = "Другой план")
        assertNotEquals(
            AgentPlanJsonCodec.sha256(AgentPlanJsonCodec.canonicalJson(first)),
            AgentPlanJsonCodec.sha256(AgentPlanJsonCodec.canonicalJson(second))
        )
    }

    @Test
    fun rejectsOversizedPlan() {
        assertNull(AgentPlanJsonCodec.decode(" ".repeat(AgentPlanJsonCodec.MAX_PLAN_JSON_CHARS + 1)))
    }
}
