package com.shev.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBuiltInSkillCatalogTest {
    @Test fun skillIdsAreUniqueAndValid() {
        val skills = AgentBuiltInSkillCatalog.skills
        assertTrue(skills.isNotEmpty())
        assertEquals(skills.size, skills.map { it.id }.toSet().size)
        skills.forEach {
            assertTrue(it.id.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        }
    }

    @Test fun everySkillHasBoundedContentKeywordsAndPublicSources() {
        AgentBuiltInSkillCatalog.skills.forEach { skill ->
            assertTrue(skill.title.isNotBlank())
            assertTrue(skill.title.length <= 120)
            assertTrue(skill.summary.isNotBlank())
            assertTrue(skill.instructions.isNotBlank())
            assertTrue(skill.instructions.length <= AgentSkillLibrary.MAX_INSTRUCTION_CHARS)
            assertTrue(skill.keywords.isNotEmpty())
            assertTrue(skill.keywords.all { it.isNotBlank() && it.length <= 64 })
            assertTrue(skill.sources.isNotEmpty())
            assertTrue(skill.sources.all { it.startsWith("https://github.com/") })
        }
    }

    @Test fun safetyPlaybookNeverRecommendsRuntimeCodeInjection() {
        val skill = AgentBuiltInSkillCatalog.skills.single { it.id == "builtin.safe-self-repair" }
        assertTrue(skill.instructions.contains("изолированной ветке"))
        assertTrue(skill.instructions.contains("явного одобрения пользователя"))
        assertTrue(skill.instructions.contains("Не загружай произвольный сгенерированный байткод"))
    }

    @Test fun diagnosticPlaybookRequiresReproductionAndRegressionTest() {
        val skill = AgentBuiltInSkillCatalog.skills.single { it.id == "builtin.systematic-debugging" }
        assertTrue(skill.instructions.contains("Воспроизведи проблему"))
        assertTrue(skill.instructions.contains("регрессионный тест"))
    }
}
