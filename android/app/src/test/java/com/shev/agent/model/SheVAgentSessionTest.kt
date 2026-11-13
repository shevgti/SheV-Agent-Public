package com.shev.agent.model

import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SheVAgentSessionTest {
    @Test
    fun initializesSessionBeforeFirstAgentTurn() = runBlocking {
        val sessionService = InMemorySessionService()

        SheVAgentSession.initialize(sessionService)

        val session = sessionService.getSession(
            SessionKey(
                SheVAgentSession.APP_NAME,
                SheVAgentSession.USER_ID,
                SheVAgentSession.SESSION_ID
            )
        )

        assertNotNull(session)
        assertEquals(SheVAgentSession.SESSION_ID, session?.key?.id)
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
