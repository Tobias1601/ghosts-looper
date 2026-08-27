package com.diy.loopstation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {

    private val engine = LooperEngine()
    private val metronome = Metronome()
    private var hasPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            LooperTheme {
                Surface(color = BgBlack, modifier = Modifier.fillMaxSize()) {
                    LooperScreen(
                        engine = engine,
                        metronome = metronome,
                        hasPermission = hasPermission,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        exportDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        engine.stopAll()
        metronome.stop()
        super.onDestroy()
    }
}

@Composable
fun LooperScreen(
    engine: LooperEngine,
    metronome: Metronome,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    exportDir: File
) {
    val context = LocalContext.current
    var recordingTrackIndex by remember { mutableStateOf(-1) }
    var tick by remember { mutableStateOf(0) }
    var bpm by remember { mutableStateOf(100f) }
    var metronomeOn by remember { mutableStateOf(false) }
    var latencyMs by remember { mutableStateOf(0f) }
    var lowLatency by remember { mutableStateOf(true) }
    var settingsOpen by remember { mutableStateOf(false) }
    var trimTrack by remember { mutableStateOf<Track?>(null) }
    var calibratorOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        // --- Header ---
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostIcon(modifier = Modifier.size(34.dp), color = AccentSignal)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("GHOSTS", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                    Text("LOOPER · DIY UNIT · 04 TRACK", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            OutlinedButton(
                onClick = { settingsOpen = !settingsOpen },
                border = BorderStroke(1.dp, if (settingsOpen) AccentSignal else OutlineGrey),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (settingsOpen) AccentSignal else TextSecondary)
            ) { Text("SETUP", style = MaterialTheme.typography.labelLarge) }
        }

        Spacer(Modifier.height(14.dp))

        if (!hasPermission) {
            SectionCard {
                Text("MIKROFON-ZUGRIFF ERFORDERLICH", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentSignal, contentColor = Color.Black)
                ) { Text("ERLAUBEN", style = MaterialTheme.typography.labelLarge) }
            }
            return@Column
        }

        // --- Settings panel: latency + low latency mode ---
        if (settingsOpen) {
            SectionCard(accent = true) {
                Text("LATENZ-KALIBRIERUNG", style = MaterialTheme.typography.titleMedium, color = AccentSignal)
                Spacer(Modifier.height(6.dp))
                Text(
                    "OFFSET: ${if (latencyMs >= 0) "+" else ""}${latencyMs.toInt()} MS",
                    style = MaterialTheme.typography.bodyMedium, color = TextPrimary
                )
                Text(
                    "POSITIV = AUFNAHME WIRD FRÜHER GESCHNITTEN (SYSTEM-DELAY AUSGLEICHEN)",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary
                )
                Slider(
                    value = latencyMs,
                    onValueChange = { latencyMs = it; AudioSettings.latencyMs = it.toInt() },
                    valueRange = -150f..150f,
                    colors = SliderDefaults.colors(thumbColor = AccentSignal, activeTrackColor = AccentSignal)
                )

                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { calibratorOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, AccentSignal),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSignal)
                ) { Text("AUTO-LATENZ-TEST (CLICK-TRACK)", style = MaterialTheme.typography.labelLarge) }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("LOW-LATENCY-MODUS", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("VOICE_COMM INPUT + FAST-TRACK OUTPUT", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Switch(
                        checked = lowLatency,
                        onCheckedChange = { lowLatency = it; AudioSettings.lowLatencyMode = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = AccentSignal)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // --- Auto latency calibrator overlay ---
        if (calibratorOpen) {
            LatencyCalibrator(
                engine = engine,
                onApply = { ms ->
                    latencyMs = ms.toFloat()
                    AudioSettings.latencyMs = ms
                    calibratorOpen = false
                },
                onCancel = { calibratorOpen = false }
            )
            Spacer(Modifier.height(10.dp))
        }

        // --- Trim editor overlay for the master-setting recording ---
        trimTrack?.let { t ->
            TrimEditor(
                buffer = t.buffer,
                onConfirm = { s, e ->
                    engine.finalizeMasterTrim(t, s, e)
                    trimTrack = null
                    tick++
                },
                onCancel = {
                    engine.cancelMasterTrim(t)
                    trimTrack = null
                    tick++
                }
            )
            Spacer(Modifier.height(10.dp))
        }

        // --- Metronome ---
        SectionCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("METRONOM · ${bpm.toInt()} BPM", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Switch(
                    checked = metronomeOn,
                    onCheckedChange = {
                        metronomeOn = it
                        metronome.bpm = bpm.toInt()
                        if (it) metronome.start() else metronome.stop()
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = AccentSignal)
                )
            }
            Slider(
                value = bpm,
                onValueChange = { bpm = it; metronome.bpm = it.toInt() },
                valueRange = 40f..220f,
                colors = SliderDefaults.colors(thumbColor = AccentSignal, activeTrackColor = AccentSignal)
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = if (engine.isMasterSet())
                "LOOP-LÄNGE: %.2fS".format(engine.masterLoopLengthFrames / SAMPLE_RATE.toFloat())
            else "TRACK 1 AUFNEHMEN → LEGT LOOP-LÄNGE FEST",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(Modifier.height(10.dp))

        key(tick) {
            engine.tracks.forEach { track ->
                TrackRow(
                    track = track,
                    isRecording = recordingTrackIndex == track.index,
                    canRecord = recordingTrackIndex == -1 && trimTrack == null && !calibratorOpen,
                    onRecordToggle = {
                        if (recordingTrackIndex == track.index) {
                            engine.stopRecording()
                        } else if (recordingTrackIndex == -1 && trimTrack == null && !calibratorOpen) {
                            recordingTrackIndex = track.index
                            engine.startRecording(track) { isMasterCandidate ->
                                recordingTrackIndex = -1
                                if (isMasterCandidate) {
                                    trimTrack = track
                                }
                                tick++
                            }
                        }
                    },
                    onClear = { engine.clear(track); tick++ },
                    onUndo = { engine.undo(track); tick++ },
                    onMuteToggle = { track.muted = !track.muted; track.updateVolume(); tick++ },
                    onVolumeChange = { v -> track.volume = v; track.updateVolume() },
                    onExport = {
                        exportDir.mkdirs()
                        val f = File(exportDir, "track${track.index + 1}.wav")
                        engine.exportWav(track, f)
                        Toast.makeText(context, "EXPORTIERT: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { engine.playAll() },
                border = BorderStroke(1.dp, OutlineGrey),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text("▶ ALLE", style = MaterialTheme.typography.labelLarge) }

            OutlinedButton(
                onClick = { engine.stopAll() },
                border = BorderStroke(1.dp, OutlineGrey),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text("■ ALLE", style = MaterialTheme.typography.labelLarge) }

            OutlinedButton(
                onClick = { engine.clearAll(); tick++ },
                border = BorderStroke(1.dp, AccentDim),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSignal)
            ) { Text("RESET", style = MaterialTheme.typography.labelLarge) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SectionCard(accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .border(BorderStroke(1.dp, if (accent) AccentSignal else OutlineGrey))
            .padding(14.dp)
    ) { content() }
}

@Composable
fun MuteToggle(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) AccentSignal else Color.Transparent)
            .border(BorderStroke(1.dp, if (active) AccentSignal else OutlineGrey))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            "MUTE",
            style = MaterialTheme.typography.bodySmall,
            color = if (active) Color.Black else TextSecondary
        )
    }
}

@Composable
fun TrackRow(
    track: Track,
    isRecording: Boolean,
    canRecord: Boolean,
    onRecordToggle: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onMuteToggle: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onExport: () -> Unit
) {
    var volumeSlider by remember(track.index) { mutableStateOf(track.volume) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .border(BorderStroke(1.dp, if (isRecording) AccentSignal else OutlineGrey))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TRACK ${track.index + 1}",
                style = MaterialTheme.typography.titleMedium,
                color = if (track.isEmpty) TextSecondary else TextPrimary
            )
            Button(
                onClick = onRecordToggle,
                enabled = canRecord || isRecording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) AccentSignal else SurfaceDark,
                    contentColor = if (isRecording) Color.Black else AccentSignal,
                    disabledContainerColor = SurfaceDark,
                    disabledContentColor = TextSecondary
                ),
                border = BorderStroke(1.dp, if (isRecording) AccentSignal else AccentDim)
            ) {
                Text(
                    if (isRecording) "■ STOP" else if (track.isEmpty) "● REC" else "● OVERDUB",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            MuteToggle(active = track.muted, onClick = onMuteToggle)
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onUndo, enabled = !track.isEmpty) {
                Text("UNDO", style = MaterialTheme.typography.bodySmall, color = if (!track.isEmpty) TextPrimary else TextSecondary)
            }
            TextButton(onClick = onClear, enabled = !track.isEmpty) {
                Text("CLEAR", style = MaterialTheme.typography.bodySmall, color = if (!track.isEmpty) TextPrimary else TextSecondary)
            }
            TextButton(onClick = onExport, enabled = !track.isEmpty) {
                Text("EXPORT", style = MaterialTheme.typography.bodySmall, color = if (!track.isEmpty) TextPrimary else TextSecondary)
            }
        }

        Slider(
            value = volumeSlider,
            onValueChange = { volumeSlider = it; onVolumeChange(it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = if (track.isEmpty) TextSecondary else AccentSignal,
                activeTrackColor = if (track.isEmpty) TextSecondary else AccentSignal,
                inactiveTrackColor = OutlineGrey
            )
        )
    }
}
