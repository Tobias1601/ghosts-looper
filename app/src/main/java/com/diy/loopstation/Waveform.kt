package com.diy.loopstation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

/** A vertical reference/handle line drawn over the waveform, in buffer-fraction (0..1) space. */
data class WaveMarker(
    val id: String,
    val frac: Float,
    val color: Color,
    val draggable: Boolean = false,
    val dashed: Boolean = false
)

/**
 * Waveform view with pinch-free zoom (slider-controlled) + pan, and any number of
 * vertical marker lines. Markers flagged [WaveMarker.draggable] can be dragged;
 * whichever draggable marker is closest to the touch-down point moves with the drag,
 * reported back through [onDrag] as a fraction (0..1) of the FULL buffer.
 */
@Composable
fun ZoomableWaveform(
    buffer: ShortArray,
    markers: List<WaveMarker>,
    onDrag: (id: String, newFrac: Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp
) {
    var zoom by remember(buffer) { mutableStateOf(1f) }
    var viewCenter by remember(buffer) { mutableStateOf(0.5f) }
    var canvasWidthPx by remember { mutableStateOf(1f) }

    val visibleSpan = (1f / zoom).coerceIn(0.001f, 1f)
    val viewStart = (viewCenter - visibleSpan / 2f).coerceIn(0f, (1f - visibleSpan).coerceAtLeast(0f))
    val viewEnd = (viewStart + visibleSpan).coerceAtMost(1f)

    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(BgBlack)
                .border(BorderStroke(1.dp, OutlineGrey))
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .pointerInput(buffer, zoom, viewStart, markers) {
                        var draggingId: String? = null
                        detectDragGestures(
                            onDragStart = { offset ->
                                val touchFrac = viewStart + (offset.x / canvasWidthPx) * visibleSpan
                                draggingId = markers.filter { it.draggable }
                                    .minByOrNull { abs(it.frac - touchFrac) }?.id
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val frac = (viewStart + (change.position.x / canvasWidthPx) * visibleSpan)
                                    .coerceIn(0f, 1f)
                                draggingId?.let { onDrag(it, frac) }
                            }
                        )
                    }
            ) {
                canvasWidthPx = size.width
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val n = buffer.size
                if (n > 0) {
                    val startFrame = (viewStart * n).toInt()
                    val endFrame = (viewEnd * n).toInt().coerceAtLeast(startFrame + 1).coerceAtMost(n)
                    val span = (endFrame - startFrame).coerceAtLeast(1)
                    val pixels = w.toInt().coerceAtLeast(1)
                    val samplesPerPixel = (span / pixels).coerceAtLeast(1)
                    for (x in 0 until pixels) {
                        val s = startFrame + x * samplesPerPixel
                        val e = min(n, min(endFrame, s + samplesPerPixel))
                        if (s >= e) continue
                        var lo = 0
                        var hi = 0
                        for (i in s until e) {
                            val v = buffer[i].toInt()
                            if (v < lo) lo = v
                            if (v > hi) hi = v
                        }
                        val yTop = mid - (hi / 32768f) * mid
                        val yBot = mid - (lo / 32768f) * mid
                        drawLine(
                            color = TextSecondary,
                            start = Offset(x.toFloat(), yTop),
                            end = Offset(x.toFloat(), yBot),
                            strokeWidth = 1.5f
                        )
                    }
                }
                markers.forEach { m ->
                    if (m.frac in viewStart..viewEnd) {
                        val x = ((m.frac - viewStart) / visibleSpan) * w
                        if (m.dashed) {
                            drawLine(
                                color = m.color,
                                start = Offset(x, 0f),
                                end = Offset(x, h),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                            )
                        } else {
                            drawLine(
                                color = m.color,
                                start = Offset(x, 0f),
                                end = Offset(x, h),
                                strokeWidth = if (m.draggable) 5f else 2f
                            )
                        }
                    }
                }
            }
        }

        Spacer6()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Strings.t("zoom"), style = MaterialTheme.typography.bodySmall,
                color = TextSecondary, modifier = Modifier.width(56.dp)
            )
            Slider(
                value = zoom,
                onValueChange = { zoom = it },
                valueRange = 1f..20f,
                colors = SliderDefaults.colors(thumbColor = AccentSignal, activeTrackColor = AccentSignal)
            )
        }
        if (zoom > 1.01f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Strings.t("position"), style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary, modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = viewCenter,
                    onValueChange = { viewCenter = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = AccentSignal, activeTrackColor = AccentSignal)
                )
            }
        }
    }
}

@Composable
private fun Spacer6() {
    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
}
