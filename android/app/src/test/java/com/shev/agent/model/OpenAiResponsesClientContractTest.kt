package com.shev.agent.model

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesClientContractTest {
    @Test fun functionDefinitionUsesStrictResponsesApiShape() {
        val parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("query", JsonObject().apply {
                    addProperty("type", "string")
                    add("enum", com.google.gson.JsonArray().apply { add("now") })
                })
            })
            add("required", com.google.gson.JsonArray().apply { add("query") })
            addProperty("additionalProperties", false)
        }
        val json = OpenAiFunctionTool(
            name = "get_current_local_time",
            description = "Read current device time",
            parameters = parameters
        ).toJson()

        assertEquals("function", json.get("type").asString)
        assertEquals("get_current_local_time", json.get("name").asString)
        assertTrue(json.get("strict").asBoolean)
        assertFalse(json.getAsJsonObject("parameters").get("additionalProperties").asBoolean)
    }

    @Test fun functionNameRejectsUnsupportedCharacters() {
        val parameters = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject())
            add("required", com.google.gson.JsonArray())
            addProperty("additionalProperties", false)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            OpenAiFunctionTool("bad tool name", "Invalid name", parameters)
        }
    }
}
