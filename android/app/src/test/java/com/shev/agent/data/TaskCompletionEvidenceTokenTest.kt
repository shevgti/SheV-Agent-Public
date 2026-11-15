package com.shev.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCompletionEvidenceTokenTest {
    @Test
    fun tokenIsOpaqueAndOnlyDigestIsPersistable() {
        val token = TaskCompletionEvidenceToken.create()
        val digest = TaskCompletionEvidenceToken.digest(token)

        assertEquals(43, token.length)
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(token, digest)
        assertEquals(digest, TaskCompletionEvidenceToken.digest(token))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedToken() {
        TaskCompletionEvidenceToken.digest("not-a-valid-token")
    }
}
