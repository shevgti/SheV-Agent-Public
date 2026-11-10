package com.shev.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTimeIntentRouterTest {
    @Test
    fun recognizesRussianCurrentTimeAndDateRequests() {
        listOf(
            "Который час?",
            "Сколько сейчас времени",
            "Скажи текущее время",
            "Какая сегодня дата?",
            "Какое сегодня число",
            "Какой сегодня день недели?"
        ).forEach { assertTrue("$it should be routed", LocalTimeIntentRouter.isCurrentTimeRequest(it)) }
    }

    @Test
    fun doesNotCaptureUnrelatedConversation() {
        listOf(
            "Расскажи про часовые пояса",
            "Мне нравится эта дата",
            "Поставь напоминание на завтра"
        ).forEach { assertFalse("$it should stay with the model", LocalTimeIntentRouter.isCurrentTimeRequest(it)) }
    }
}
