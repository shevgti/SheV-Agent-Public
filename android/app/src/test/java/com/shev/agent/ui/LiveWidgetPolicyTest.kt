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
        assertEquals(.44f, weather?.x ?: -1f, .0001f)
        assertEquals(.04f, weather?.y ?: -1f, .0001f)
        assertEquals("Амстердаме", weather?.city)
        val remove = LiveWidgetIntentRouter.parse("Убери часы")
        assertEquals(LiveWidgetAction.HIDE, remove?.action)
        assertEquals(LiveWidgetType.CLOCK, remove?.type)
        val move = LiveWidgetIntentRouter.parse("Перемести часы наверх")
        assertEquals(LiveWidgetAction.MOVE, move?.action)
        assertEquals(0.30f, move?.x ?: -1f, .0001f)
        assertEquals(0.04f, move?.y ?: -1f, .0001f)
        assertNull(LiveWidgetIntentRouter.parse("Какая погода сегодня?"))

        val battery = LiveWidgetIntentRouter.parse("Покажи батарею внизу")
        assertEquals(LiveWidgetType.BATTERY, battery?.type)
        assertEquals(0.30f, battery?.x ?: -1f, .0001f)
        assertEquals(0.58f, battery?.y ?: -1f, .0001f)

        val lowerLeft = LiveWidgetIntentRouter.parse("Добавь часы снизу слева")
        assertEquals(0.04f, lowerLeft?.x ?: -1f, .0001f)
        assertEquals(0.58f, lowerLeft?.y ?: -1f, .0001f)

        val center = LiveWidgetIntentRouter.parse("Покажи часы по центру")
        assertEquals(0.30f, center?.x ?: -1f, .0001f)
        assertEquals(0.35f, center?.y ?: -1f, .0001f)
    }
    @Test fun genericHideAllAndSingleBlockCommandsWork() {
        val widgets = listOf(
            LiveWidget(type = LiveWidgetType.CLOCK),
            LiveWidget(type = LiveWidgetType.WEATHER, city = "Berlin")
        )
        val all = LiveWidgetIntentRouter.parse("Убери все блоки с экрана", widgets)
        assertEquals(LiveWidgetAction.HIDE_ALL, all?.action)
        assertEquals(emptyList<LiveWidget>(), LiveWidgetPolicy.apply(widgets, all!!))

        val one = listOf(widgets.first())
        val hide = LiveWidgetIntentRouter.parse("Убери блок с экрана", one)
        assertEquals(LiveWidgetAction.HIDE, hide?.action)
        assertEquals(LiveWidgetType.CLOCK, hide?.type)

        val move = LiveWidgetIntentRouter.parse("Передвинь блок наверх справа", one)
        assertEquals(LiveWidgetAction.MOVE, move?.action)
        assertEquals(.44f, move?.x ?: -1f, .0001f)
        assertEquals(.04f, move?.y ?: -1f, .0001f)

        assertNull(LiveWidgetIntentRouter.parse("Убери блок", widgets))
    }

    @Test fun genericResizeChangesSelectedBlockScale() {
        val widgets = listOf(LiveWidget(type = LiveWidgetType.CLOCK))
        val command = LiveWidgetIntentRouter.parse("Увеличь блок", widgets)!!
        assertEquals(LiveWidgetAction.RESIZE, command.action)
        val resized = LiveWidgetPolicy.apply(widgets, command)!!
        assertEquals(1.15f, resized.single().scale, .0001f)
    }

    @Test fun asksWhichWidgetWhenGenericCommandIsAmbiguous() {
        val widgets = listOf(
            LiveWidget(type = LiveWidgetType.CLOCK),
            LiveWidget(type = LiveWidgetType.WEATHER, city = "Berlin")
        )
        assertTrue(LiveWidgetIntentRouter.needsTargetClarification("Передвинь блок вправо", widgets))
        assertTrue(LiveWidgetIntentRouter.needsTargetClarification("Убери блок с экрана", widgets))
        assertFalse(LiveWidgetIntentRouter.needsTargetClarification("Убери все блоки", widgets))
        assertFalse(LiveWidgetIntentRouter.needsTargetClarification("Убери часы", widgets))
    }

}
