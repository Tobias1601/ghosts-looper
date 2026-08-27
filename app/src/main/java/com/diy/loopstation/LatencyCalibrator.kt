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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

/**
 * Auto-latency test: plays a 4-click metronome track while recording the mic.
 * The user taps/claps along, trying to land exactly on the clicks. Afterward the
 * recording is shown as a waveform with dashed reference lines at every click
 * frame; the user drags one line over their tapped transient, and the offset to
 * the nearest click is converted straight into a latency-ms value.
 */
@Composable
fun LatencyCalibrator(
    engine: LooperEngine,
    onApply: (Int) -> Unit,
    onCancel: () -> Unit
) {
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var buffer by remember { mutableStateOf(ShortArray(0)) }
    var clickFrames by remember { mutableStateOf(listOf<Int>()) }
    var markerFrac by remember { mutableStateOf(0.5f) }
    var canvasWidthPx by remember { mutableStateOf(1f) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .border(BorderStroke(1.dp, AccentSignal))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            GhostIcon(modifier = Modifier.size(18.dp), color = AccentSignal)
            Spacer(Modifier.width(6.dp))
            Text("AUTO-LATENZ-TEST", style = MaterialTheme.typography.labelLarge, color = AccentSignal)
        }
        Spacer(Modifier.height(8.dp))

        when (phase) {
            Phase.IDLE -> {
                Text(
                    "2 LEISE VORZÄHL-KLICKS, DANN 4 KLICKS ZUM MITKLATSCHEN - " +
                        "GENAU AUF JEDEN LAUTEN CLICK.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        border = BorderStroke(1.dp, OutlineGrey)
                    ) { Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge) }
                    Button(
                        onClick = {
                            phase = Phase.RECORDING
                            engine.runLatencyTest { buf, frames ->
                                buffer = buf
                                clickFrames = frames
                                val guess = frames.lastOrNull() ?: 0
                                markerFrac = if (buf.isNotEmpty())
                                    (guess.toFloat() / buf.size.toFloat()).coerceIn(0f, 1f) else 0.5f
                                phase = Phase.REVIEW
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentSignal, contentColor = Color.Black)
                    ) { Text("TEST STARTEN", style = MaterialTheme.typography.labelLarge) }
                }
            }

            Phase.RECORDING -> {
                Text(
                    "... AUFNAHME LÄUFT - JETZT MITKLATSCHEN ...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentSignal
                )
            }

            Phase.REVIEW -> {
                val nearestClick = clickFrames.minByOrNull { abs(it - markerFrac * buffer.size) } ?: 0
                val diffFrames = (markerFrac * buffer.size).toInt() - nearestClick
                val diffMs = (diffFrames * 1000L / SAMPLE_RATE).toInt().coerceIn(-150, 150)

                Text(
                    "LINIE AUF DEINEN GEKLATSCHTEN TRANSIENTEN ZIEHEN (NÄCHSTGELEGENER CLICK WIRD REFERENZ)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(BgBlack)
                        .border(BorderStroke(1.dp, OutlineGrey))
                        .pointerInput(buffer) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                markerFrac = (change.position.x / canvasWidthPx).coerceIn(0f, 1f)
                            }
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
                            val samplesPerPixel = (n / pixels).coerceAtLeast(1)
                            for (x in 0 until pixels) {
                                val start = x * samplesPerPixel
                                val end = min(n, start + samplesPerPixel)
                                if (start >= end) continue
                                var lo = 0; var hi = 0
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
                        // reference click lines (dashed, white)
                        clickFrames.forEach { cf ->
                            val x = (cf.toFloat() / n.toFloat()) * w
                            drawLine(
                                color = TextPrimary,
                                start = Offset(x, 0f),
                                end = Offset(x, h),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                            )
                        }
                        // draggable marker (accent, solid)
                        val mx = markerFrac * w
                        drawLine(
                            color = AccentSignal,
                            start = Offset(mx, 0f),
                            end = Offset(mx, h),
                            strokeWidth = 5f,
                            cap = StrokeCap.Butt
                        )
                        // nearest-click highlight
                        val nx = (nearestClick.toFloat() / n.toFloat()) * w
                        drawLine(
                            color = ConfirmGreen,
                            start = Offset(nx, 0f),
                            end = Offset(nx, h),
                            strokeWidth = 2f
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "GEMESSENE LATENZ: ${if (diffMs >= 0) "+" else ""}$diffMs MS",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    "WEISS GESTRICHELT = CLICKS · GRÜN = REFERENZ-CLICK · ROT = DEIN MARKER",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        border = BorderStroke(1.dp, OutlineGrey)
                    ) { Text("VERWERFEN", style = MaterialTheme.typography.labelLarge) }
                    Button(
                        onClick = { onApply(diffMs) },
                        colors = ButtonDefaults.buttonColors(containerColor = ConfirmGreen, contentColor = Color.Black)
                    ) { Text("ÜBERNEHMEN", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

private enum class Phase { IDLE, RECORDING, REVIEW }
