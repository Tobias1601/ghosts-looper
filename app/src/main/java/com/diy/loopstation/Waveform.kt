package com.diy.loopstation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A vertical reference/handle line drawn over the waveform, in buffer-fraction (0..1) space. */
data class WaveMarker(
    val id: String,
    val frac: Float,
    val color: Color,
    val draggable: Boolean = false,
    val dashed: Boolean = false,
    val label: String = ""
)

/** Finds the sample positions of note/hit attacks (onsets). Used to draw transient
 *  ticks and to let a marker jump from attack to attack. */
fun findTransients(buf: ShortArray): IntArray {
    val hop = SAMPLE_RATE / 500 // 2 ms
    val n = buf.size / hop
    if (n < 8) return IntArray(0)
    val env = FloatArray(n)
    for (i in 0 until n) {
        var m = 0
        val s = i * hop
        for (j in s until s + hop) { val a = abs(buf[j].toInt()); if (a > m) m = a }
        env[i] = m.toFloat()
    }
    val out = ArrayList<Int>()
    var last = -100
    for (i in 3 until n) {
        val from = max(0, i - 12)
        var sum = 0f
        for (k in from until i - 1) sum += env[k]
        val avg = sum / max(1, i - 1 - from)
        if (env[i] > avg * 3f + 600f && i - last > 25) {
            val thr = max(avg * 2f, env[i] * 0.2f)
            var pos = i * hop
            val lo = max(0, (i - 2) * hop)
            val hi = min(buf.size, (i + 1) * hop)
            for (p in lo until hi) { if (abs(buf[p].toInt()) > thr) { pos = p; break } }
            // slide back to the nearest zero crossing so a cut here does not click
            var z = pos
            val zl = max(1, pos - 96)
            while (z > zl && (buf[z].toInt() xor buf[z - 1].toInt()) >= 0) z--
            out.add(z)
            last = i
        }
    }
    return out.toIntArray()
}

/** (hi, lo) per pixel column, only for zoom levels where >= 1.5 samples fall on a pixel. */
private fun computeBars(buffer: ShortArray, viewStart: Float, viewEnd: Float, widthPx: Float): List<Pair<Float, Float>> {
    val n = buffer.size
    if (n == 0 || widthPx < 1f) return emptyList()
    val startF = viewStart * n
    val spanF = ((viewEnd - viewStart) * n).coerceAtLeast(1f)
    val pixels = widthPx.toInt().coerceAtLeast(1)
    val spp = spanF / pixels
    if (spp < 1.5f) return emptyList()
    val out = ArrayList<Pair<Float, Float>>(pixels)
    for (x in 0 until pixels) {
        val s = (startF + x * spp).toInt().coerceIn(0, n)
        val e = (startF + (x + 1) * spp).toInt().coerceIn(s + 1, n.coerceAtLeast(s + 1)).coerceAtMost(n)
        if (s >= e) { out.add(0f to 0f); continue }
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

@Composable
private fun WaveBtn(label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) AccentSignal else Color.Transparent)
            .border(BorderStroke(1.dp, if (active) AccentSignal else OutlineGrey))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (!enabled) OutlineGrey else if (active) Color.Black else TextPrimary
        )
    }
}

/**
 * Sampler-style waveform:
 *  - pinch to zoom (anchored under your fingers) up to individual samples, one finger pans
 *  - at sample level the real waveform line + dots are drawn, so you see exactly where a
 *    transient starts; detected attacks are shown as small ticks at the bottom
 *  - big drag handles for the markers, a minimap that shows where you are zoomed in
 *  - for precise work: select a marker (IN/OUT), nudge it sample-wise, or jump it from
 *    transient to transient; +/- zoom around the selected marker
 */
@Composable
fun ZoomableWaveform(
    buffer: ShortArray,
    markers: List<WaveMarker>,
    onDrag: (id: String, newFrac: Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    onNudge: ((id: String, newFrac: Float) -> Unit)? = null
) {
    val n = buffer.size.coerceAtLeast(1)
    val minSpan = (64f / n).coerceIn(0.00001f, 1f)
    val maxZoom = 1f / minSpan

    var zoom by remember(buffer) { mutableStateOf(1f) }
    var viewCenter by remember(buffer) { mutableStateOf(0.5f) }
    var selectedId by remember(buffer) { mutableStateOf<String?>(null) }
    var canvasWidthPx by remember { mutableStateOf(1f) }
    var miniWidthPx by remember { mutableStateOf(1f) }
    val density = LocalDensity.current
    val handleWidth = 32.dp
    val handleWidthPx = with(density) { handleWidth.toPx() }

    val visibleSpan = (1f / zoom).coerceIn(minSpan, 1f)
    val viewStart = (viewCenter - visibleSpan / 2f).coerceIn(0f, (1f - visibleSpan).coerceAtLeast(0f))
    val viewEnd = (viewStart + visibleSpan).coerceAtMost(1f)

    val bars = remember(buffer, viewStart, viewEnd, canvasWidthPx) {
        computeBars(buffer, viewStart, viewEnd, canvasWidthPx)
    }
    val draggables = markers.filter { it.draggable }
    val transients = remember(buffer) { if (draggables.isNotEmpty()) findTransients(buffer) else IntArray(0) }
    val selected = draggables.firstOrNull { it.id == selectedId } ?: draggables.firstOrNull()
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestFracs = remember { mutableMapOf<String, Float>() }
    draggables.forEach { latestFracs[it.id] = it.frac }

    fun setView(newZoom: Float, newStart: Float) {
        val z = newZoom.coerceIn(1f, maxZoom)
        val sp = (1f / z).coerceIn(minSpan, 1f)
        zoom = z
        viewCenter = (newStart + sp / 2f).coerceIn(sp / 2f, (1f - sp / 2f).coerceAtLeast(sp / 2f))
    }

    fun zoomBy(factor: Float) {
        val anchor = selected?.frac ?: (viewStart + visibleSpan / 2f)
        val inView = anchor in viewStart..viewEnd
        val a = if (inView) anchor else viewStart + visibleSpan / 2f
        val cx = if (inView) (a - viewStart) / visibleSpan else 0.5f
        val newZoom = (zoom * factor).coerceIn(1f, maxZoom)
        val newSpan = (1f / newZoom).coerceIn(minSpan, 1f)
        // zooming in on an off-screen selected marker jumps to it
        val target = if (!inView && factor > 1f && selected != null) selected.frac else a
        setView(newZoom, target - (if (!inView && factor > 1f && selected != null) 0.5f else cx) * newSpan)
    }

    fun moveSelected(newFrac: Float, fine: Boolean) {
        val s = selected ?: return
        val f = newFrac.coerceIn(0f, 1f)
        if (fine && onNudge != null) onNudge(s.id, f) else onDrag(s.id, f)
        // keep the marker on screen
        if (f < viewStart || f > viewEnd) setView(zoom, f - visibleSpan / 2f)
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
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val oldSpan = (1f / zoom).coerceIn(minSpan, 1f)
                            val oldStart = (viewCenter - oldSpan / 2f).coerceIn(0f, (1f - oldSpan).coerceAtLeast(0f))
                            val cx = (centroid.x / canvasWidthPx).coerceIn(0f, 1f)
                            val under = oldStart + cx * oldSpan
                            val newZoom = (zoom * zoomChange).coerceIn(1f, maxZoom)
                            val newSpan = (1f / newZoom).coerceIn(minSpan, 1f)
                            val newStart = under - cx * newSpan - (pan.x / canvasWidthPx) * newSpan
                            setView(newZoom, newStart)
                        }
                    }
            ) {
                canvasWidthPx = size.width
                val w = size.width
                val h = size.height
                val mid = h / 2f
                drawLine(OutlineGrey, Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)

                val startF = viewStart * n
                val spanF = (viewEnd - viewStart) * n
                val spp = spanF / w
                if (bars.isNotEmpty()) {
                    bars.forEachIndexed { x, pair ->
                        val (hiN, loN) = pair
                        drawLine(
                            color = TextSecondary,
                            start = Offset(x.toFloat(), mid - hiN * mid),
                            end = Offset(x.toFloat(), mid - loN * mid),
                            strokeWidth = 1.5f
                        )
                    }
                } else {
                    // sample level: draw the actual waveform as a line, dots on each sample
                    val i0 = startF.toInt().coerceIn(0, buffer.size - 1)
                    val i1 = (startF + spanF).toInt().coerceIn(i0, buffer.size - 1)
                    var prev: Offset? = null
                    for (i in max(0, i0 - 1)..min(buffer.size - 1, i1 + 1)) {
                        val x = (i - startF) / spp
                        val y = mid - (buffer[i] / 32768f) * mid
                        val p = Offset(x, y)
                        prev?.let { drawLine(TextSecondary, it, p, strokeWidth = 2f) }
                        if (spp < 0.35f) drawCircle(TextPrimary, 3f, p)
                        prev = p
                    }
                }

                // transient ticks along the bottom edge
                if (transients.isNotEmpty()) {
                    val lo = (viewStart * n).toInt()
                    val hi = (viewEnd * n).toInt()
                    var drawn = 0
                    for (t in transients) {
                        if (t < lo || t > hi) continue
                        if (++drawn > 300) break
                        val x = (t - startF) / spp
                        drawLine(AccentDim, Offset(x, h - 12f), Offset(x, h), strokeWidth = 2f)
                    }
                }

                markers.forEach { m ->
                    if (m.frac in viewStart..viewEnd) {
                        val x = (((m.frac - viewStart) / visibleSpan) * w).coerceIn(1.5f, w - 1.5f)
                        if (m.dashed) {
                            drawLine(
                                color = m.color, start = Offset(x, 0f), end = Offset(x, h),
                                strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                            )
                        } else {
                            val isSel = m.draggable && m.id == selected?.id
                            drawLine(
                                color = m.color, start = Offset(x, 0f), end = Offset(x, h),
                                strokeWidth = if (m.draggable) (if (isSel) 5f else 3f) else 2f
                            )
                        }
                    }
                }
            }

            // time at the edges of the visible window
            Text(
                "%.3fS".format(viewStart * n / SAMPLE_RATE),
                style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = 2.dp)
            )
            Text(
                "%.3fS".format(viewEnd * n / SAMPLE_RATE),
                style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 36.dp, top = 2.dp)
            )

            // Grab handles. Always kept in composition (never removed when the marker
            // scrolls out of view) so a drag can never be cancelled mid-gesture.
            draggables.forEach { m ->
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
                            // NOTE: this block only restarts when its keys change, so it must
                            // never read `m` / `onDrag` directly - they'd be stale captures
                            // (the old marker position -> handle "jumps back"). Always go
                            // through the up-to-date state holders below.
                            var localFrac = 0f
                            detectDragGestures(
                                onDragStart = { localFrac = latestFracs[m.id] ?: 0f; selectedId = m.id },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val span = (1f / zoom).coerceIn(minSpan, 1f)
                                    localFrac = (localFrac + (dragAmount.x / canvasWidthPx) * span).coerceIn(0f, 1f)
                                    latestOnDrag(m.id, localFrac)
                                }
                            )
                        },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (m.label.isNotEmpty()) {
                        Text(
                            m.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black,
                            modifier = Modifier
                                .background(m.color)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }

        // minimap: where in the whole recording are we looking
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(SurfaceDark)
                .border(BorderStroke(1.dp, OutlineGrey))
                .pointerInput(buffer) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val sp = (1f / zoom).coerceIn(minSpan, 1f)
                        setView(zoom, viewCenter - sp / 2f + drag.x / miniWidthPx)
                    }
                }
        ) {
            miniWidthPx = size.width
            val x0 = viewStart * size.width
            val ww = max(4f, (viewEnd - viewStart) * size.width)
            drawRect(AccentDim, Offset(x0, 0f), Size(ww, size.height))
            markers.filter { it.draggable }.forEach {
                val x = it.frac * size.width
                drawLine(it.color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            }
        }

        if (draggables.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val stepFrac = max(1f, visibleSpan * n / canvasWidthPx) / n
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                draggables.forEach { m ->
                    WaveBtn(m.label.ifEmpty { m.id.uppercase() }, active = m.id == selected?.id) { selectedId = m.id }
                }
                Spacer(Modifier.weight(1f))
                WaveBtn("◀") { selected?.let { moveSelected(it.frac - stepFrac, true) } }
                WaveBtn("▶") { selected?.let { moveSelected(it.frac + stepFrac, true) } }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                WaveBtn("|◀ ${Strings.t("transient")}", enabled = transients.isNotEmpty()) {
                    selected?.let { s ->
                        val cur = (s.frac * n).toInt()
                        val prev = transients.lastOrNull { it < cur - 2 }
                        if (prev != null) moveSelected(prev.toFloat() / n, true)
                    }
                }
                WaveBtn("${Strings.t("transient")} ▶|", enabled = transients.isNotEmpty()) {
                    selected?.let { s ->
                        val cur = (s.frac * n).toInt()
                        val next = transients.firstOrNull { it > cur + 2 }
                        if (next != null) moveSelected(next.toFloat() / n, true)
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            WaveBtn("−") { zoomBy(0.5f) }
            WaveBtn("+") { zoomBy(2f) }
            WaveBtn("FIT", enabled = zoom > 1.01f) { setView(1f, 0f) }
            Spacer(Modifier.weight(1f))
            Text(
                buildString {
                    append("%.0f×".format(zoom))
                    selected?.let { append("  ${it.label.ifEmpty { it.id.uppercase() }} %.3fS".format(it.frac * n / SAMPLE_RATE)) }
                },
                style = MaterialTheme.typography.bodySmall, color = TextSecondary
            )
        }
        Text(Strings.t("pinch_hint"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
