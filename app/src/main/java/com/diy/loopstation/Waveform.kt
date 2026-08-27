package com.diy.loopstation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Waveform view with real two-finger pinch-to-zoom + pan, like a phone photo viewer.
 * One finger near a draggable marker grabs and moves that marker; one finger elsewhere
 * pans the view; two fingers pinch-zoom and pan together. A marker is only grabbed if
 * the touch-down is reasonably close to it, so dragging elsewhere never yanks a distant
 * handle around.
 */
@Composable
fun ZoomableWaveform(
    buffer: ShortArray,
    markers: List<WaveMarker>,
    onDrag: (id: String, newFrac: Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 130.dp
) {
    var zoom by remember(buffer) { mutableStateOf(1f) }
    var viewCenter by remember(buffer) { mutableStateOf(0.5f) }
    var canvasWidthPx by remember { mutableStateOf(1f) }

    val visibleSpan = (1f / zoom).coerceIn(0.001f, 1f)
    val viewStart = (viewCenter - visibleSpan / 2f).coerceIn(0f, (1f - visibleSpan).coerceAtLeast(0f))
    val viewEnd = (viewStart + visibleSpan).coerceAtMost(1f)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(BgBlack)
                .border(BorderStroke(1.dp, OutlineGrey))
                .pointerInput(buffer, markers) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val spanAtDown = (1f / zoom).coerceIn(0.001f, 1f)
                        val startAtDown = (viewCenter - spanAtDown / 2f)
                            .coerceIn(0f, (1f - spanAtDown).coerceAtLeast(0f))
                        val touchFrac = startAtDown + (down.position.x / canvasWidthPx) * spanAtDown

                        var draggingMarkerId: String? = markers.filter { it.draggable }
                            .map { it to abs(it.frac - touchFrac) }
                            .minByOrNull { it.second }
                            ?.takeIf { it.second < spanAtDown * 0.06f }
                            ?.first?.id

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            val span = (1f / zoom).coerceIn(0.001f, 1f)

                            if (pressed.size >= 2) {
                                draggingMarkerId = null
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val newZoom = (zoom * zoomChange).coerceIn(1f, 40f)
                                val newSpan = (1f / newZoom).coerceIn(0.001f, 1f)
                                val panFrac = -(panChange.x / canvasWidthPx) * span
                                zoom = newZoom
                                viewCenter = (viewCenter + panFrac)
                                    .coerceIn(newSpan / 2f, (1f - newSpan / 2f).coerceAtLeast(newSpan / 2f))
                                event.changes.forEach { it.consume() }
                            } else {
                                val change = pressed.first()
                                if (draggingMarkerId != null) {
                                    val vs = (viewCenter - span / 2f).coerceIn(0f, (1f - span).coerceAtLeast(0f))
                                    val frac = (vs + (change.position.x / canvasWidthPx) * span).coerceIn(0f, 1f)
                                    onDrag(draggingMarkerId!!, frac)
                                    change.consume()
                                } else {
                                    val panChange = event.calculatePan()
                                    val panFrac = -(panChange.x / canvasWidthPx) * span
                                    viewCenter = (viewCenter + panFrac)
                                        .coerceIn(span / 2f, (1f - span / 2f).coerceAtLeast(span / 2f))
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
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

        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(Strings.t("pinch_hint"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            if (zoom > 1.01f) {
                TextButton(onClick = { zoom = 1f; viewCenter = 0.5f }) {
                    Text(Strings.t("reset_zoom"), style = MaterialTheme.typography.bodySmall, color = AccentSignal)
                }
            }
        }
    }
}
