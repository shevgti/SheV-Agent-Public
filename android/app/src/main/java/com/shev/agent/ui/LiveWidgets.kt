package com.shev.agent.ui

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

enum class LiveWidgetType { CLOCK, WEATHER, BATTERY }

data class LiveWidget(
    val id: String = UUID.randomUUID().toString(),
    val type: LiveWidgetType,
    val x: Float = 0.44f,
    val y: Float = 0.08f,
    val city: String = "",
    val scale: Float = 1f
)

enum class LiveWidgetAction { SHOW, HIDE, MOVE }

data class LiveWidgetCommand(
    val action: LiveWidgetAction,
    val type: LiveWidgetType,
    val city: String = "",
    val x: Float? = null,
    val y: Float? = null
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

    fun defaultPosition(type: LiveWidgetType, index: Int): Pair<Float, Float> = when (index % 4) {
        0 -> 0.44f to 0.04f
        1 -> 0.04f to 0.08f
        2 -> 0.44f to 0.55f
        else -> 0.04f to 0.58f
    }

    fun apply(items: List<LiveWidget>, command: LiveWidgetCommand): List<LiveWidget>? {
        if (validate(items) != null) return null
        val existing = items.firstOrNull { it.type == command.type }
        return when (command.action) {
            LiveWidgetAction.SHOW -> {
                if (existing != null) {
                    items.map { if (it.id == existing.id) it.copy(city = command.city.ifBlank { it.city }) else it }
                } else {
                    if (items.size >= MAX_WIDGETS) return null
                    val (x, y) = defaultPosition(command.type, items.size)
                    items + LiveWidget(type = command.type, x = command.x ?: x, y = command.y ?: y, city = command.city)
                }
            }
            LiveWidgetAction.HIDE -> if (existing == null) items else items.filterNot { it.id == existing.id }
            LiveWidgetAction.MOVE -> {
                if (existing == null) return null
                items.map { widget ->
                    if (widget.id == existing.id) widget.copy(
                        x = command.x ?: widget.x,
                        y = command.y ?: widget.y
                    ) else widget
                }
            }
        }.takeIf { validate(it) == null }
    }
}

object LiveWidgetIntentRouter {
    private val placement = listOf(
        Triple(Regex("(?:наверх|вверх|верх)\\\\b"), 0.30f, 0.04f),
        Triple(Regex("(?:вниз|ниж)\\\\b"), 0.30f, 0.58f),
        Triple(Regex("(?:влево|левее)\\\\b"), 0.04f, 0.35f),
        Triple(Regex("(?:вправо|правее)\\\\b"), 0.44f, 0.35f),
        Triple(Regex("(?:сверху|вверху|верхний).*(?:справа|правый)"), 0.68f, 0.04f),
        Triple(Regex("(?:сверху|вверху|верхний).*(?:слева|левый)"), 0.04f, 0.04f),
        Triple(Regex("(?:снизу|внизу|нижний).*(?:справа|правый)"), 0.68f, 0.58f),
        Triple(Regex("(?:снизу|внизу|нижний).*(?:слева|левый)"), 0.04f, 0.58f),
        Triple(Regex("(?:по центру|в центре|центральн)"), 0.36f, 0.35f)
    )

    fun parse(text: String): LiveWidgetCommand? {
        val normalized = text.lowercase().trim()
        val type = when {
            normalized.contains("погод") || normalized.contains("weather") -> LiveWidgetType.WEATHER
            normalized.contains("час") || normalized.contains("время") || normalized.contains("clock") -> LiveWidgetType.CLOCK
            normalized.contains("батаре") || normalized.contains("заряд") || normalized.contains("battery") -> LiveWidgetType.BATTERY
            else -> return null
        }
        val action = when {
            Regex("\\b(?:убери|удали|закрой|скрой|hide|remove)\\b").containsMatchIn(normalized) -> LiveWidgetAction.HIDE
            Regex("\\b(?:перемести|передвинь|move)\\b").containsMatchIn(normalized) -> LiveWidgetAction.MOVE
            else -> LiveWidgetAction.SHOW
        }
        val explicitAction = Regex("\\b(?:покажи|добавь|создай|выведи|сделай|show|add|display|убери|удали|закрой|скрой|hide|remove|перемести|передвинь|move)\\b")
        if (!explicitAction.containsMatchIn(normalized)) return null
        val position = placement.firstOrNull { it.first.containsMatchIn(normalized) }
        val city = if (type == LiveWidgetType.WEATHER) {
            Regex("""(?:погод[ау]?|weather)\s+(?:в|для)\s+([\p{L} .'-]{2,80})""")
                .find(normalized)?.groupValues?.getOrNull(1)?.trim()
                ?.replace(Regex("""\s+(?:сверху|вверху|снизу|внизу|справа|слева|по центру|в центре).*$"""), "")
                ?.trim()?.takeIf { it.isNotBlank() } ?: ""
        } else ""
        return LiveWidgetCommand(action, type, city, position?.second, position?.third)
    }
}

class LiveWidgetRepository(context: Context) {
    private val prefs = context.getSharedPreferences("shev_live_widgets", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<LiveWidget>>() {}.type

    fun load(): List<LiveWidget> = runCatching {
        gson.fromJson<List<LiveWidget>>(prefs.getString("items", "[]"), listType).orEmpty()
    }.getOrDefault(emptyList()).filter { LiveWidgetPolicy.validate(listOf(it)) == null }

    fun save(items: List<LiveWidget>) {
        require(LiveWidgetPolicy.validate(items) == null) { "Invalid live widget layout" }
        prefs.edit().putString("items", gson.toJson(items)).apply()
    }
}
