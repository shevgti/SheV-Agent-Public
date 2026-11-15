package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheVAgentSessionTest {
    @Test
    fun chatTurnSessionsAreUniqueAndSeparatedFromCreatorSessions() {
        val chatIds = List(100) { SheVAgentSession.newChatTurnSessionId() }
        val creatorIds = List(100) { SheVAgentSession.newCreatorSessionId() }

        assertEquals(100, chatIds.toSet().size)
        assertEquals(100, creatorIds.toSet().size)
        assertTrue(chatIds.all { it.startsWith("chat-") })
        assertTrue(creatorIds.all { it.startsWith("creator-") })
        assertTrue(chatIds.toSet().intersect(creatorIds.toSet()).isEmpty())
    }
}
