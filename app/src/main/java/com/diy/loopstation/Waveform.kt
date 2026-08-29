package com.diy.loopstation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/** A vertical reference/handle line drawn over the waveform, in buffer-fraction (0..1) space. */
data class WaveMarker(
    val id: String,
    val frac: Float,
    val color: Color,
    val draggable: Boolean = false,
    val dashed: Boolean = false
)

/** Precomputed (hi, lo) amplitude fraction pair per visible pixel column. */
private fun computeBars(buffer: ShortArray, viewStart: Float, viewEnd: Float, widthPx: Float): List<Pair<Float, Float>> {
    val n = buffer.size
    if (n == 0 || widthPx < 1f) return emptyList()
    val startFrame = (viewStart * n).toInt()
    val endFrame = (viewEnd * n).toInt().coerceAtLeast(startFrame + 1).coerceAtMost(n)
    val span = (endFrame - startFrame).coerceAtLeast(1)
    val pixels = widthPx.toInt().coerceAtLeast(1)
    val samplesPerPixel = (span / pixels).coerceAtLeast(1)
    val out = ArrayList<Pair<Float, Float>>(pixels)
    for (x in 0 until pixels) {
        val s = startFrame + x * samplesPerPixel
        val e = min(n, min(endFrame, s + samplesPerPixel))
        if (s >= e) {
            out.add(0f to 0f)
            continue
        }
        var lo = 0
        var hi = 0
        for (i in s until e) {
            val v = buffer[i].toInt()
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        out.add((hi / 32768f) to (lo / 32768f))
    }
    return out
}

/**
 * Waveform view with real two-finger pinch-to-zoom + pan (via Compose's own
 * detectTransformGestures - the same primitive photo viewers use) and dedicated,
 * appropriately-sized grab handles for each draggable marker, layered on top of the
 * canvas. A touch that starts on a handle always drags exactly that handle (delta-based,
 * so it never jumps); a touch anywhere else pans/zooms the view. The expensive per-pixel
 * waveform scan is cached and only recomputed when the buffer or the visible window
 * changes - moving a handle never triggers a rescan, so dragging stays smooth even on
 * long recordings.
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
    val density = LocalDensity.current
    val handleWidth = 28.dp
    val handleWidthPx = with(density) { handleWidth.toPx() }

    val visibleSpan = (1f / zoom).coerceIn(0.001f, 1f)
    val viewStart = (viewCenter - visibleSpan / 2f).coerceIn(0f, (1f - visibleSpan).coerceAtLeast(0f))
    val viewEnd = (viewStart + visibleSpan).coerceAtMost(1f)

    // Cache key deliberately excludes `markers` - dragging a handle must never
    // trigger a full waveform rescan.
    val bars = remember(buffer, viewStart, viewEnd, canvasWidthPx) {
        computeBars(buffer, viewStart, viewEnd, canvasWidthPx)
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(BgBlack)
                .border(BorderStroke(1.dp, OutlineGrey))
                .clipToBounds()
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(buffer) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            val span = (1f / zoom).coerceIn(0.001f, 1f)
                            val newZoom = (zoom * zoomChange).coerceIn(1f, 40f)
                            val newSpan = (1f / newZoom).coerceIn(0.001f, 1f)
                            val panFrac = -(pan.x / canvasWidthPx) * span
                            zoom = newZoom
                            viewCenter = (viewCenter + panFrac)
                                .coerceIn(newSpan / 2f, (1f - newSpan / 2f).coerceAtLeast(newSpan / 2f))
                        }
                    }
            ) {
                canvasWidthPx = size.width
                val w = size.width
                val h = size.height
                val mid = h / 2f

                bars.forEachIndexed { x, pair ->
                    val (hiN, loN) = pair
                    val yTop = mid - hiN * mid
                    val yBot = mid - loN * mid
                    drawLine(
                        color = TextSecondary,
                        start = Offset(x.toFloat(), yTop),
                        end = Offset(x.toFloat(), yBot),
                        strokeWidth = 1.5f
                    )
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

            // Dedicated grab handles for draggable markers, layered on top of the
            // canvas. Always kept alive (never removed from composition just because
            // the marker's position scrolls outside the visible window) - removing it
            // mid-drag would cancel the gesture and could make it look like the
            // handle "snapped back". Positioning it off-screen is enough; the parent
            // clips it visually.
            markers.filter { it.draggable }.forEach { m ->
                val xFrac = (m.frac - viewStart) / visibleSpan
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(handleWidth)
                        .offset {
                            val xPx = (xFrac * canvasWidthPx).roundToInt()
                            IntOffset(xPx - (handleWidthPx / 2f).roundToInt(), 0)
                        }
                        .pointerInput(m.id, buffer) {
                            var localFrac = m.frac
                            detectDragGestures(
                                onDragStart = { localFrac = m.frac },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val span = (1f / zoom).coerceIn(0.001f, 1f)
                                    localFrac = (localFrac + (dragAmount.x / canvasWidthPx) * span)
                                        .coerceIn(0f, 1f)
                                    onDrag(m.id, localFrac)
                                }
                            )
                        }
                )
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
