package com.shev.agent.model

import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SheVAgentSessionTest {
    @Test
    fun eachConversationTurnReceivesAnIndependentSessionId() {
        val firstTurn = SheVAgentSession.newConversationTurnSessionId()
        val secondTurn = SheVAgentSession.newConversationTurnSessionId()

        assertNotEquals(firstTurn, secondTurn)
        assert(firstTurn.startsWith("turn-"))
        assert(secondTurn.startsWith("turn-"))
    }

    @Test
    fun conversationTurnSessionCanBeCreatedAndDeleted() = runBlocking {
        val sessionService = InMemorySessionService()
        val sessionKey = SessionKey(
            SheVAgentSession.APP_NAME,
            SheVAgentSession.USER_ID,
            SheVAgentSession.newConversationTurnSessionId()
        )

        sessionService.createSession(sessionKey)
        val created = sessionService.getSession(sessionKey)
        assertNotNull(created)
        assertEquals(sessionKey.id, created?.key?.id)

        sessionService.deleteSession(sessionKey)
        assertNull(sessionService.getSession(sessionKey))
    }

    @Test
    fun creatorAttemptsAlwaysReceiveIndependentEmptySessions() {
        val firstAttempt = SheVAgentSession.newCreatorSessionId()
        val repairAttempt = SheVAgentSession.newCreatorSessionId()

        assertNotEquals(firstAttempt, repairAttempt)
        assert(firstAttempt.startsWith("creator-"))
        assert(repairAttempt.startsWith("creator-"))
    }
}
