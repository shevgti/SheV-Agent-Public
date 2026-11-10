package com.shev.agent.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFallbackRouterTest {
    @Test fun parsesOrderedUniqueCandidates() {
        assertEquals(listOf("model-a", "model-b", "model-c"),
            ModelFallbackRouter.candidates(" model-a,model-b; model-a\nmodel-c "))
    }

    @Test fun triesNextModelAfterFailure() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = ModelFallbackRouter.firstSuccessful(listOf("bad", "good")) { model ->
            attempts += model
            if (model == "bad") error("quota")
            "answer"
        }
        assertEquals(listOf("bad", "good"), attempts)
        assertEquals("good", result.first)
        assertEquals("answer", result.second)
    }

    @Test fun capsCandidatesAtEight() {
        assertEquals(8, ModelFallbackRouter.candidates((1..12).joinToString(",") { "m$it" }).size)
    }
}
