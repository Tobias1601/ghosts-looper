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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Start/end trim editor, reused for:
 *  - Track 1's very first recording (sets the master loop length). If [snapFracs] is
 *    non-empty (metronome was running during that recording, frame 0 = click 1), the
 *    start handle is locked to click 1 and the end handle snaps to the nearest click.
 *  - Re-editing an already-committed track later (length stays the same) - no snapping.
 */
@Composable
fun TrimEditor(
    title: String,
    buffer: ShortArray,
    initialStartFrac: Float = 0f,
    initialEndFrac: Float = 1f,
    snapFracs: List<Float> = emptyList(),
    onConfirm: (startFrame: Int, endFrame: Int) -> Unit,
    onCancel: () -> Unit
) {
    val hasGrid = snapFracs.isNotEmpty()
    var startFrac by remember(buffer) { mutableStateOf(if (hasGrid) 0f else initialStartFrac) }
    var endFrac by remember(buffer) { mutableStateOf(initialEndFrac) }

    fun snap(frac: Float): Float {
        if (!hasGrid) return frac
        val nearest = snapFracs.minByOrNull { abs(it - frac) } ?: return frac
        return if (abs(nearest - frac) < 0.02f) nearest else frac
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .border(BorderStroke(1.dp, OutlineGrey))
            .padding(14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = AccentSignal)
        Spacer(Modifier.height(4.dp))
        val durationS = (endFrac - startFrac) * buffer.size / SAMPLE_RATE.toFloat()
        Text(
            "${Strings.t("trim_length")}: %.2fS".format(durationS),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(10.dp))

        val markers = buildList {
            snapFracs.forEach { add(WaveMarker("grid_$it", it, TextPrimary, dashed = true)) }
            add(WaveMarker("start", startFrac, AccentSignal, draggable = !hasGrid))
            add(WaveMarker("end", endFrac, AccentSignal, draggable = true))
        }

        ZoomableWaveform(
            buffer = buffer,
            markers = markers,
            onDrag = { id, frac ->
                when (id) {
                    "start" -> if (!hasGrid) startFrac = min(frac, endFrac - 0.004f).coerceAtLeast(0f)
                    "end" -> endFrac = max(snap(frac), startFrac + 0.004f).coerceAtMost(1f)
                }
            }
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, OutlineGrey)
            ) { Text(Strings.t("discard"), style = MaterialTheme.typography.labelLarge) }

            Button(
                onClick = {
                    val s = (startFrac * buffer.size).toInt()
                    val e = (endFrac * buffer.size).toInt()
                    onConfirm(s, e)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ConfirmGreen, contentColor = Color.Black)
            ) { Text(Strings.t("apply"), style = MaterialTheme.typography.labelLarge) }
        }
    }
}
