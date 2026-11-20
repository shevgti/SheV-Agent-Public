package com.shev.agent.ui

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

// Chat-local live cards for the 0.3.2 release baseline.
enum class LiveWidgetType { CLOCK, WEATHER, BATTERY }
data class LiveWidget(
    val id: String = UUID.randomUUID().toString(),
    val type: LiveWidgetType,
    val x: Float = 0.44f,
    val y: Float = 0.08f,
    val city: String = "",
    val scale: Float = 1f
)
enum class LiveWidgetAction { SHOW, HIDE, HIDE_ALL, MOVE, RESIZE }
data class LiveWidgetCommand(
    val action: LiveWidgetAction,
    val type: LiveWidgetType,
    val city: String = "",
    val x: Float? = null,
    val y: Float? = null,
    val scale: Float? = null
)

object LiveWidgetPolicy {
    const val MAX_WIDGETS = 6
    fun validate(items: List<LiveWidget>): String? {
        if (items.size > MAX_WIDGETS) return "Превышен лимит живых блоков"
        if (items.map { it.id }.toSet().size != items.size) return "Повторяется ID блока"
        if (items.any { it.x !in 0f..1f || it.y !in 0f..1f }) return "Положение блока за пределами экрана"
        if (items.any { it.city.length > 80 }) return "Слишком длинное название города"
        if (items.any { it.scale !in 0.75f..1.5f }) return "Неверный размер блока"
        return null
    }
    fun defaultPosition(type: LiveWidgetType, index: Int): Pair<Float, Float> {
        val right = if (type == LiveWidgetType.WEATHER) 0.44f else 0.58f
        return when (index % 4) {
            0 -> right to 0.04f
            1 -> 0.04f to 0.08f
            2 -> right to 0.55f
            else -> 0.04f to 0.58f
        }
    }
    fun apply(items: List<LiveWidget>, command: LiveWidgetCommand): List<LiveWidget>? {
        if (validate(items) != null) return null
        if (command.action == LiveWidgetAction.HIDE_ALL) return emptyList()
        val existing = items.firstOrNull { it.type == command.type }
        return when (command.action) {
            LiveWidgetAction.SHOW -> {
                if (existing != null) items.map { if (it.id == existing.id) it.copy(
                    city = command.city.ifBlank { it.city }, x = command.x ?: it.x, y = command.y ?: it.y,
                    scale = command.scale ?: it.scale
                ) else it }
                else {
                    if (items.size >= MAX_WIDGETS) return null
                    val (x, y) = defaultPosition(command.type, items.size)
                    items + LiveWidget(type = command.type, x = command.x ?: x, y = command.y ?: y,
                        city = command.city, scale = command.scale ?: 1f)
                }
            }
            LiveWidgetAction.HIDE -> if (existing == null) items else items.filterNot { it.id == existing.id }
            LiveWidgetAction.MOVE -> {
                if (existing == null || (command.x == null && command.y == null)) return null
                items.map { if (it.id == existing.id) it.copy(x = command.x ?: it.x, y = command.y ?: it.y) else it }
            }
            LiveWidgetAction.RESIZE -> {
                if (existing == null || command.scale == null) return null
                items.map { if (it.id == existing.id) it.copy(scale = command.scale) else it }
            }
            LiveWidgetAction.HIDE_ALL -> emptyList()
        }.takeIf { validate(it) == null }
    }
}

object LiveWidgetIntentRouter {
    private val placement = listOf(
        Triple(Regex("(?:сверху|вверху|верхний).*(?:справа|правый)"), .44f, .04f),
        Triple(Regex("(?:сверху|вверху|верхний).*(?:слева|левый)"), .04f, .04f),
        Triple(Regex("(?:снизу|внизу|нижний).*(?:справа|правый)"), .44f, .58f),
        Triple(Regex("(?:снизу|внизу|нижний).*(?:слева|левый)"), .04f, .58f),
        Triple(Regex("(?:наверх|вверх|верх|сверху|вверху)(?=\\s|$)"), .30f, .04f),
        Triple(Regex("(?:вниз|ниж|снизу|внизу)(?=\\s|$)"), .30f, .58f),
        Triple(Regex("(?:влево|левее|слева)(?=\\s|$)"), .04f, .35f),
        Triple(Regex("(?:вправо|правее|справа)(?=\\s|$)"), .44f, .35f),
        Triple(Regex("(?:по центру|в центре|центральн)"), .30f, .35f)
    )
    fun parse(text: String, activeWidgets: List<LiveWidget> = emptyList()): LiveWidgetCommand? {
        val n = text.lowercase().trim()
        val allTarget = Regex("""(?:все|всё|все блоки|все виджеты|блоки|виджеты)""").containsMatchIn(n)
        val explicitType = when {
            Regex("""погод|weather""").containsMatchIn(n) -> LiveWidgetType.WEATHER
            Regex("""час|время|clock""").containsMatchIn(n) -> LiveWidgetType.CLOCK
            Regex("""батаре|заряд|battery""").containsMatchIn(n) -> LiveWidgetType.BATTERY
            else -> null
        }
        val hide = Regex("""(?:убери|удали|закрой|скрой|сотри|убрать|удалить|скрыть|выключи|отключи|remove|hide)""").containsMatchIn(n)
        val move = Regex("""(?:перемести|передвинь|сдвинь|перетащи|поставь|перенеси|переместить|передвинуть|move)""").containsMatchIn(n)
        val resizeUp = Regex("""(?:увеличь|увеличить|сделай крупнее|побольше)""").containsMatchIn(n)
        val resizeDown = Regex("""(?:уменьши|уменьшить|сделай меньше|поменьше)""").containsMatchIn(n)
        val show = Regex("""(?:покажи|добавь|создай|выведи|сделай|включи|show|add|display)""").containsMatchIn(n)
        if (!hide && !move && !resizeUp && !resizeDown && !show) return null
        val action = when {
            hide && (explicitType == null && (allTarget || activeWidgets.isEmpty() && (n.contains("блок") || n.contains("виджет")))) -> LiveWidgetAction.HIDE_ALL
            hide -> LiveWidgetAction.HIDE
            move -> LiveWidgetAction.MOVE
            resizeUp -> LiveWidgetAction.RESIZE
            resizeDown -> LiveWidgetAction.RESIZE
            else -> LiveWidgetAction.SHOW
        }
        val type = explicitType ?: activeWidgets.singleOrNull()?.type ?: if (action == LiveWidgetAction.HIDE_ALL) LiveWidgetType.CLOCK else return null
        val p = placement.firstOrNull { it.first.containsMatchIn(n) }
        val city = if (type == LiveWidgetType.WEATHER) {
            Regex("""(?i)(?:погод\\p{L}*|weather)\\s+(?:в|для)\\s+([\\p{L} .'-]{2,80})""")
                .find(text)?.groupValues?.getOrNull(1)?.trim()
                ?.replace(Regex("""(?i)\\s+(?:сверху|вверху|снизу|внизу|справа|слева|по центру|в центре).*$"""), "")
                ?.trim()?.takeIf { it.isNotBlank() } ?: ""
        } else ""
        val scale = when {
            resizeUp -> (activeWidgets.firstOrNull { it.type == type }?.scale ?: 1f) + .15f
            resizeDown -> (activeWidgets.firstOrNull { it.type == type }?.scale ?: 1f) - .15f
            else -> null
        }?.coerceIn(.75f, 1.5f)
        return LiveWidgetCommand(action, type, city, p?.second, p?.third, scale)
    }
}

class LiveWidgetRepository(context: Context) {
    private val prefs = context.getSharedPreferences("shev_live_widgets", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<LiveWidget>>() {}.type
    fun load(): List<LiveWidget> = runCatching {
        gson.fromJson<List<LiveWidget>>(prefs.getString("items", "[]"), listType).orEmpty()
    }.getOrDefault(emptyList()).let { if (LiveWidgetPolicy.validate(it) == null) it else emptyList() }
    fun save(items: List<LiveWidget>) {
        require(LiveWidgetPolicy.validate(items) == null) { "Invalid live widget layout" }
        prefs.edit().putString("items", gson.toJson(items)).apply()
    }
}
