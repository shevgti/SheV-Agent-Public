package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexTaskDraftResponseParserTest {
    @Test
    fun detectsOnlyExplicitPositiveTaskDraftRequests() {
        assertTrue(CodexTaskDraftResponseParser.isExplicitTaskDraftRequest("Сохрани это как поручение"))
        assertTrue(CodexTaskDraftResponseParser.isExplicitTaskDraftRequest("Создай мне задачу на завтра"))
        assertFalse(CodexTaskDraftResponseParser.isExplicitTaskDraftRequest("Не надо создавать задачу"))
        assertFalse(CodexTaskDraftResponseParser.isExplicitTaskDraftRequest("Расскажи, как создать поручение"))
        assertFalse(CodexTaskDraftResponseParser.isExplicitTaskDraftRequest("Покажи, как создать задачу"))
        assertFalse(CodexTaskDraftResponseParser.isExplicitTaskDraftRequest("Просто обсудим задачу"))
    }

    @Test
    fun parsesStrictTitleAndCanonicalPlan() {
        val response = """{
            "title":"Найти актуальную информацию",
            "plan":{
                "summary":"Выполнить публичный поиск",
                "steps":[
                    {"id":"search-1","title":"Найти источники","toolId":"agent.web_search","capability":"READ_REMOTE_DATA","input":"{}"}
                ]
            }
        }"""
        val parsed = CodexTaskDraftResponseParser.parse(response)
        assertNotNull(parsed)
        assertEquals("Найти актуальную информацию", parsed?.title)
        assertTrue(parsed?.planJson?.contains("agent.web_search") == true)
    }

    @Test
    fun rejectsExtraFieldsAndInvalidPlans() {
        assertNull(CodexTaskDraftResponseParser.parse("""{"title":"x","plan":{"summary":"x","steps":[]},"extra":"no"}"""))
        assertNull(CodexTaskDraftResponseParser.parse("""{"title":"x","plan":{"summary":"x","steps":[]}}"""))
    }
}
