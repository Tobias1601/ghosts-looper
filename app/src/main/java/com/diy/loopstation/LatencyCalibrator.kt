package com.diy.loopstation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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

enum class CalibPhase { IDLE, RECORDING, REVIEW }

/**
 * Automatic round-trip latency test - the same technique real audio tools use
 * (see Android's own loopback latency test): play a few short bursts through the
 * speaker while recording through the mic, detect exactly when each echo arrives
 * via a noise-floor threshold, and report the median round-trip time. No tapping,
 * no dragging, no human reaction time in the measurement at all.
 */
@Composable
fun LatencyCalibrator(
    engine: LooperEngine,
    phase: CalibPhase,
    onPhaseChange: (CalibPhase) -> Unit,
    buffer: ShortArray,
    onBufferChange: (ShortArray) -> Unit,
    expectedFrames: List<Int>,
    onExpectedFramesChange: (List<Int>) -> Unit,
    detectedFrames: List<Int>,
    onDetectedFramesChange: (List<Int>) -> Unit,
    measuredMs: Int,
    onMeasuredMsChange: (Int) -> Unit,
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
                            engine.runAutoLatencyTest { ms, buf, expected, detected ->
                                onMeasuredMsChange(ms)
                                onBufferChange(buf)
                                onExpectedFramesChange(expected)
                                onDetectedFramesChange(detected)
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
                val hitRate = if (expectedFrames.isNotEmpty())
                    (detectedFrames.size * 100 / expectedFrames.size) else 0

                if (detectedFrames.isEmpty()) {
                    Text(
                        Strings.t("latency_test_failed"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentSignal
                    )
                } else {
                    Text(
                        "${Strings.t("latency_measured")}: +$measuredMs MS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        "${Strings.t("latency_hits")}: $hitRate% (${detectedFrames.size}/${expectedFrames.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Spacer(Modifier.height(10.dp))

                val n = buffer.size.coerceAtLeast(1)
                val markers = buildList {
                    expectedFrames.forEach { f -> add(WaveMarker("exp_$f", f.toFloat() / n, TextPrimary, dashed = true)) }
                    detectedFrames.forEach { f -> add(WaveMarker("det_$f", f.toFloat() / n, ConfirmGreen)) }
                }
                ZoomableWaveform(buffer = buffer, markers = markers, onDrag = { _, _ -> })

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
                    OutlinedButton(
                        onClick = { onPhaseChange(CalibPhase.IDLE) },
                        border = BorderStroke(1.dp, AccentSignal),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSignal)
                    ) { Text(Strings.t("redo_test"), style = MaterialTheme.typography.labelLarge) }
                    if (detectedFrames.isNotEmpty()) {
                        Button(
                            onClick = { onApply(measuredMs) },
                            colors = ButtonDefaults.buttonColors(containerColor = ConfirmGreen, contentColor = Color.Black)
                        ) { Text(Strings.t("apply"), style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
    }
}
