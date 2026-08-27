package com.diy.loopstation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs

enum class CalibPhase { IDLE, RECORDING, REVIEW }

/**
 * Auto-latency test: 2 quiet lead-in clicks (not measured) + 4 measured clicks are
 * played while the mic records. The user claps/taps along; afterward a zoomable
 * waveform shows dashed reference lines at each measured click plus one draggable
 * marker for the user's transient. All state is hoisted (passed in) so it survives
 * this panel being closed and reopened - it only resets when a new test is run.
 */
@Composable
fun LatencyCalibrator(
    engine: LooperEngine,
    phase: CalibPhase,
    onPhaseChange: (CalibPhase) -> Unit,
    buffer: ShortArray,
    onBufferChange: (ShortArray) -> Unit,
    clickFrames: List<Int>,
    onClickFramesChange: (List<Int>) -> Unit,
    markerFrac: Float,
    onMarkerFracChange: (Float) -> Unit,
    onApply: (Int) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .border(BorderStroke(1.dp, AccentSignal))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostIcon(modifier = Modifier.width(13.dp).height(18.dp), color = AccentSignal)
            Spacer(Modifier.width(6.dp))
            Text(Strings.t("auto_latency_test"), style = MaterialTheme.typography.labelLarge, color = AccentSignal)
        }
        Spacer(Modifier.height(8.dp))

        when (phase) {
            CalibPhase.IDLE -> {
                Text(
                    Strings.t("latency_test_intro"),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        border = BorderStroke(1.dp, OutlineGrey)
                    ) { Text(Strings.t("latency_test_cancel"), style = MaterialTheme.typography.labelLarge) }
                    Button(
                        onClick = {
                            onPhaseChange(CalibPhase.RECORDING)
                            engine.runLatencyTest { buf, frames ->
                                onBufferChange(buf)
                                onClickFramesChange(frames)
                                val guess = frames.lastOrNull() ?: 0
                                val frac = if (buf.isNotEmpty())
                                    (guess.toFloat() / buf.size.toFloat()).coerceIn(0f, 1f) else 0.5f
                                onMarkerFracChange(frac)
                                onPhaseChange(CalibPhase.REVIEW)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentSignal, contentColor = Color.Black)
                    ) { Text(Strings.t("latency_test_start"), style = MaterialTheme.typography.labelLarge) }
                }
            }

            CalibPhase.RECORDING -> {
                Text(
                    Strings.t("latency_test_recording"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentSignal
                )
            }

            CalibPhase.REVIEW -> {
                val nearestClick = clickFrames.minByOrNull { abs(it - markerFrac * buffer.size) } ?: 0
                val diffFrames = (markerFrac * buffer.size).toInt() - nearestClick
                val diffMs = (diffFrames * 1000L / SAMPLE_RATE).toInt().coerceIn(-150, 150)
                val n = buffer.size.coerceAtLeast(1)

                Text(
                    Strings.t("latency_test_drag_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))

                val markers = buildList {
                    clickFrames.forEach { cf ->
                        add(WaveMarker("click_$cf", cf.toFloat() / n, TextPrimary, dashed = true))
                    }
                    add(WaveMarker("nearest", nearestClick.toFloat() / n, ConfirmGreen))
                    add(WaveMarker("marker", markerFrac, AccentSignal, draggable = true))
                }

                ZoomableWaveform(
                    buffer = buffer,
                    markers = markers,
                    onDrag = { id, frac -> if (id == "marker") onMarkerFracChange(frac) }
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    "${Strings.t("latency_measured")}: ${if (diffMs >= 0) "+" else ""}$diffMs MS",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    Strings.t("latency_legend"),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        border = BorderStroke(1.dp, OutlineGrey)
                    ) { Text(Strings.t("discard"), style = MaterialTheme.typography.labelLarge) }
                    Button(
                        onClick = { onApply(diffMs) },
                        colors = ButtonDefaults.buttonColors(containerColor = ConfirmGreen, contentColor = Color.Black)
                    ) { Text(Strings.t("apply"), style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}
