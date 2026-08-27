package com.diy.loopstation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-width waveform preview of the just-recorded master candidate, with two
 * draggable vertical handles for start/end trim. Dragging is picked up by
 * whichever handle is closer to the touch-down point.
 */
@Composable
fun TrimEditor(
    buffer: ShortArray,
    onConfirm: (startFrame: Int, endFrame: Int) -> Unit,
    onCancel: () -> Unit
) {
    var startFrac by remember(buffer) { mutableStateOf(0f) }
    var endFrac by remember(buffer) { mutableStateOf(1f) }
    var canvasWidthPx by remember { mutableStateOf(1f) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .border(BorderStroke(1.dp, OutlineGrey))
            .padding(14.dp)
    ) {
        Text(
            "TRIM // TRACK 1 SETZT LOOP-LÄNGE",
            style = MaterialTheme.typography.labelLarge,
            color = AccentSignal
        )
        Spacer(Modifier.height(4.dp))
        val durationS = (endFrac - startFrac) * buffer.size / SAMPLE_RATE.toFloat()
        Text(
            "LÄNGE: %.2fS".format(durationS),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(BgBlack)
                .border(BorderStroke(1.dp, OutlineGrey))
                .pointerInput(buffer) {
                    var draggingStart = false
                    detectDragGestures(
                        onDragStart = { offset ->
                            val sx = startFrac * canvasWidthPx
                            val ex = endFrac * canvasWidthPx
                            draggingStart = abs(offset.x - sx) <= abs(offset.x - ex)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val frac = (change.position.x / canvasWidthPx).coerceIn(0f, 1f)
                            if (draggingStart) {
                                startFrac = min(frac, endFrac - 0.01f).coerceAtLeast(0f)
                            } else {
                                endFrac = max(frac, startFrac + 0.01f).coerceAtMost(1f)
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                canvasWidthPx = size.width
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val n = buffer.size
                if (n > 0) {
                    val pixels = w.toInt().coerceAtLeast(1)
                    val samplesPerPixel = max(1, n / pixels)
                    for (x in 0 until pixels) {
                        val start = x * samplesPerPixel
                        val end = min(n, start + samplesPerPixel)
                        if (start >= end) continue
                        var lo = 0
                        var hi = 0
                        for (i in start until end) {
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
                // dim out the trimmed-away regions
                drawRect(
                    color = Color.Black.copy(alpha = 0.65f),
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(startFrac * w, h)
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.65f),
                    topLeft = Offset(endFrac * w, 0f),
                    size = androidx.compose.ui.geometry.Size(w - endFrac * w, h)
                )
                // handles
                drawLine(
                    color = AccentSignal,
                    start = Offset(startFrac * w, 0f),
                    end = Offset(startFrac * w, h),
                    strokeWidth = 5f,
                    cap = StrokeCap.Butt
                )
                drawLine(
                    color = AccentSignal,
                    start = Offset(endFrac * w, 0f),
                    end = Offset(endFrac * w, h),
                    strokeWidth = 5f,
                    cap = StrokeCap.Butt
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, OutlineGrey)
            ) { Text("VERWERFEN", style = MaterialTheme.typography.labelLarge) }

            Button(
                onClick = {
                    val s = (startFrac * buffer.size).toInt()
                    val e = (endFrac * buffer.size).toInt()
                    onConfirm(s, e)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ConfirmGreen,
                    contentColor = Color.Black
                )
            ) { Text("ÜBERNEHMEN", style = MaterialTheme.typography.labelLarge) }
        }
    }
}
