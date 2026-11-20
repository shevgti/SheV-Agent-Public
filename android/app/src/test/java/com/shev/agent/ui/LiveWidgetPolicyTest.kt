package com.shev.agent.ui

import org.junit.Assert.*
import org.junit.Test

class LiveWidgetPolicyTest {
    @Test fun addClockAndWeatherThenHideClock() {
        val clock = LiveWidgetPolicy.apply(emptyList(), LiveWidgetCommand(LiveWidgetAction.SHOW, LiveWidgetType.CLOCK))
        assertNotNull(clock)
        assertEquals(1, clock!!.size)
        val weather = LiveWidgetPolicy.apply(clock, LiveWidgetCommand(LiveWidgetAction.SHOW, LiveWidgetType.WEATHER, city = "Amsterdam"))
        assertEquals(2, weather!!.size)
        val hidden = LiveWidgetPolicy.apply(weather, LiveWidgetCommand(LiveWidgetAction.HIDE, LiveWidgetType.CLOCK))
        assertEquals(listOf(LiveWidgetType.WEATHER), hidden!!.map { it.type })
    }

    @Test fun placementAndMoveStayWithinNormalizedBounds() {
        val start = listOf(LiveWidget(type = LiveWidgetType.CLOCK, x = .2f, y = .2f))
        val moved = LiveWidgetPolicy.apply(start, LiveWidgetCommand(LiveWidgetAction.MOVE, LiveWidgetType.CLOCK, x = .8f, y = .7f))
        assertEquals(.8f, moved!!.first().x, .0001f)
        assertEquals(.7f, moved.first().y, .0001f)
        assertNull(LiveWidgetPolicy.apply(start, LiveWidgetCommand(LiveWidgetAction.MOVE, LiveWidgetType.CLOCK, x = 1.2f, y = .7f)))
    }

    @Test fun rejectsTooManyAndDuplicateWidgets() {
        val six = (0 until LiveWidgetPolicy.MAX_WIDGETS).map { LiveWidget(id = "id-$it", type = LiveWidgetType.BATTERY) }
        assertNull(LiveWidgetPolicy.apply(six, LiveWidgetCommand(LiveWidgetAction.SHOW, LiveWidgetType.CLOCK)))
        val duplicates = listOf(six[0], six[0])
        assertNotNull(LiveWidgetPolicy.validate(duplicates))
    }

    @Test fun routerUnderstandsRussianWidgetCommandsAndLocations() {
        val weather = LiveWidgetIntentRouter.parse("Покажи погоду в Амстердаме сверху справа")
        assertEquals(LiveWidgetType.WEATHER, weather?.type)
        assertEquals(LiveWidgetAction.SHOW, weather?.action)
        assertEquals(.68f, weather?.x ?: -1f, .0001f)
        assertEquals(.04f, weather?.y ?: -1f, .0001f)
        assertEquals("Амстердаме", weather?.city)
        val remove = LiveWidgetIntentRouter.parse("Убери часы")
        assertEquals(LiveWidgetAction.HIDE, remove?.action)
        assertEquals(LiveWidgetType.CLOCK, remove?.type)
    }
}
