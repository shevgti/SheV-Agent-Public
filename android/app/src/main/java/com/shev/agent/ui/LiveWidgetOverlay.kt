package com.shev.agent.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveWidgetsOverlay(
    widgets: List<LiveWidget>,
    onMove: (id: String, x: Float, y: Float) -> Unit,
    onRemove: (id: String) -> Unit,
    onResize: (id: String, scale: Float) -> Unit
) {
    BoxWithConstraints(Modifier) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        widgets.forEach { widget ->
            val baseWidth = if (widget.type == LiveWidgetType.WEATHER) 200.dp else 150.dp
            val cardWidth = baseWidth * widget.scale
            val estimatedHeight = (if (widget.type == LiveWidgetType.WEATHER) 170.dp else 120.dp) * widget.scale
            val maxX = ((maxWidth - cardWidth) / maxWidth).coerceIn(0f, 0.84f)
            val maxY = ((maxHeight - estimatedHeight) / maxHeight).coerceIn(0f, 0.82f)
            LiveWidgetCard(
                widget = widget,
                onRemove = { onRemove(widget.id) },
                onResize = { delta -> onResize(widget.id, (widget.scale + delta).coerceIn(0.75f, 1.5f)) },
                modifier = Modifier
                    .offset(x = (widget.x.coerceIn(0f, maxX) * maxWidth.value).dp, y = (widget.y.coerceIn(0f, maxY) * maxHeight.value).dp)
                    .width(cardWidth)
                    .pointerInput(widget.id, widthPx, heightPx, cardWidth) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val nextX = (widget.x + drag.x / widthPx).coerceIn(0f, maxX)
                            val nextY = (widget.y + drag.y / heightPx).coerceIn(0f, maxY)
                            onMove(widget.id, nextX, nextY)
                        }
                    }
            )
        }
    }
}

@Composable
private fun LiveWidgetCard(widget: LiveWidget, onRemove: () -> Unit, onResize: (Float) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var weather by remember { mutableStateOf("Загрузка погоды…") }
    var weatherError by remember { mutableStateOf<String?>(null) }
    var batteryPercent by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(widget.type) {
        while (true) {
            currentTime = System.currentTimeMillis()
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null
            delay(if (widget.type == LiveWidgetType.CLOCK) 1000L else 60_000L)
        }
    }

    LaunchedEffect(widget.city, widget.type) {
        if (widget.type == LiveWidgetType.WEATHER) {
            if (widget.city.isBlank()) {
                weather = "Скажи: «погода в Амстердаме» или укажи свой город"
                weatherError = null
            } else {
                while (true) {
                    weather = "Обновляю…"
                    weatherError = null
                    try {
                        weather = LiveWeatherClient.current(widget.city)
                    } catch (error: Exception) {
                        weatherError = error.message ?: "Погодные данные недоступны"
                        weather = "Не удалось обновить"
                    }
                    delay(15 * 60 * 1000L)
                }
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xE81A2330),
        contentColor = Color(0xFFF0F4FA),
        shadowElevation = 10.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x665C86A6))
    ) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 10.dp)) {
            Row {
                Text(
                    when (widget.type) {
                        LiveWidgetType.CLOCK -> "ЧАСЫ"
                        LiveWidgetType.WEATHER -> "ПОГОДА"
                        LiveWidgetType.BATTERY -> "БАТАРЕЯ"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB7C7D9),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onResize(-0.1f) }) { Text("−", color = Color(0xFFCAD6E4)) }
                IconButton(onClick = { onResize(0.1f) }) { Text("+", color = Color(0xFFCAD6E4)) }
                IconButton(onClick = onRemove) { Text("×", color = Color(0xFFCAD6E4)) }
            }
            when (widget.type) {
                LiveWidgetType.CLOCK -> {
                    Text(
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTime)),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        SimpleDateFormat("EEE, d MMM", Locale("ru")).format(Date(currentTime)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB7C7D9)
                    )
                }
                LiveWidgetType.WEATHER -> {
                    Text(weather, style = MaterialTheme.typography.bodyMedium)
                    weatherError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB8A8)) }
                }
                LiveWidgetType.BATTERY -> {
                    Text(batteryPercent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineMedium)
                    Text("Заряд устройства", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB7C7D9))
                }
            }
        }
    }
}
