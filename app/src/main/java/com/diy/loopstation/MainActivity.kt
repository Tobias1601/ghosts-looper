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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {

    private val engine = LooperEngine()
    private var hasPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) engine.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("ghosts_settings", MODE_PRIVATE)
        AudioSettings.latencyMs = prefs.getInt("latencyMs", 0)
        AudioSettings.lowLatencyMode = prefs.getBoolean("lowLatency", true)
        AudioSettings.autoGain = prefs.getBoolean("autoGain", true)
        AudioSettings.inputGainDb = prefs.getFloat("inputGainDb", 0f)
        AudioSettings.calibrated = prefs.getBoolean("calibrated", false)
        UiSettings.accentHue = prefs.getFloat("accentHue", 14f)
        UiSettings.lang = if (prefs.getString("lang", "EN") == "DE") Lang.DE else Lang.EN
        UiSettings.randomHue = prefs.getBoolean("randomHue", true)
        UiSettings.compact = prefs.getBoolean("compact", true)
        if (UiSettings.randomHue) UiSettings.accentHue = (Math.random() * 360.0).toFloat()

        hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            engine.start()
        }

        setContent {
            LooperTheme {
                Surface(color = BgBlack, modifier = Modifier.fillMaxSize()) {
                    LooperScreen(
                        engine = engine,
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

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onStop() {
        super.onStop()
        saveSettings()
    }

    private fun saveSettings() {
        getSharedPreferences("ghosts_settings", MODE_PRIVATE).edit()
            .putInt("latencyMs", AudioSettings.latencyMs)
            .putBoolean("lowLatency", AudioSettings.lowLatencyMode)
            .putBoolean("autoGain", AudioSettings.autoGain)
            .putFloat("inputGainDb", AudioSettings.inputGainDb)
            .putBoolean("calibrated", AudioSettings.calibrated)
            .putFloat("accentHue", UiSettings.accentHue)
            .putString("lang", UiSettings.lang.name)
            .putBoolean("randomHue", UiSettings.randomHue)
            .putBoolean("compact", UiSettings.compact)
            .apply()
    }

    override fun onDestroy() {
        engine.shutdown()
        super.onDestroy()
    }
}

@Composable
fun LooperScreen(
    engine: LooperEngine,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    exportDir: File
) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var bpm by remember { mutableStateOf(engine.bpm.toFloat()) }
    var metronomeOn by remember { mutableStateOf(false) }
    var metronomeExpanded by remember { mutableStateOf(false) }
    var latencyMs by remember { mutableStateOf(AudioSettings.latencyMs.toFloat()) }
    var lowLatency by remember { mutableStateOf(AudioSettings.lowLatencyMode) }
    var settingsOpen by remember { mutableStateOf(false) }
    var calibrated by remember { mutableStateOf(AudioSettings.calibrated) }
    var autoGain by remember { mutableStateOf(AudioSettings.autoGain) }
    var gainDb by remember { mutableStateOf(AudioSettings.inputGainDb) }
    var meter by remember { mutableStateOf(0f) }
    var trimTrack by remember { mutableStateOf<Track?>(null) }
    var editTrack by remember { mutableStateOf<Track?>(null) }
    var compactMode by remember { mutableStateOf(UiSettings.compact) }
    val multipliers = remember { mutableStateMapOf<Int, Float>() }

    // Auto-latency-test state, hoisted here so it survives closing/reopening SETUP -
    // it only resets when the user runs a new test.
    var calibratorOpen by remember { mutableStateOf(false) }
    var calibPhase by remember { mutableStateOf(CalibPhase.IDLE) }
    var calibBuffer by remember { mutableStateOf(ShortArray(0)) }
    var calibExpected by remember { mutableStateOf(listOf<Int>()) }
    var calibDetected by remember { mutableStateOf(listOf<Int>()) }
    var calibMeasuredMs by remember { mutableStateOf(0) }

    var enginePhase by remember { mutableStateOf(RecordPhase.NONE) }
    var recordingTrackIndex by remember { mutableStateOf(-1) }
    val busy = recordingTrackIndex != -1 || trimTrack != null || editTrack != null || calibratorOpen

    // The engine runs on its own audio thread; the UI just mirrors its state.
    var lastRevision by remember { mutableStateOf(engine.revision) }
    LaunchedEffect(Unit) {
        while (true) {
            enginePhase = engine.recordingPhase
            meter = engine.inputPeak
            recordingTrackIndex = engine.activeRecordingTrackIndex
            if (metronomeOn != engine.metronomeOn) metronomeOn = engine.metronomeOn
            if (engine.revision != lastRevision) { lastRevision = engine.revision; tick++ }
            val pt = engine.pendingTrim
            if (pt >= 0 && trimTrack == null) trimTrack = engine.tracks[pt]
            delay(30)
        }
    }

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
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                GhostIcon(modifier = Modifier.width(22.dp).height(32.dp), color = AccentSignal)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Strings.t("app_title"),
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        Strings.t("app_subtitle"),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { settingsOpen = !settingsOpen },
                border = BorderStroke(1.dp, if (settingsOpen) AccentSignal else OutlineGrey),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (settingsOpen) AccentSignal else TextSecondary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    Strings.t("setup"),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LangButton(Strings.t("compact"), compactMode) { compactMode = !compactMode; UiSettings.compact = compactMode }
        }

        Spacer(Modifier.height(14.dp))

        if (!hasPermission) {
            SectionCard {
                Text(Strings.t("mic_permission_needed"), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentSignal, contentColor = Color.Black)
                ) { Text(Strings.t("allow"), style = MaterialTheme.typography.labelLarge) }
            }
            return@Column
        }

        if (!calibrated && !settingsOpen && !calibratorOpen) {
            SectionCard(accent = true) {
                Text(Strings.t("latency_first_hint"), style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { settingsOpen = true; calibratorOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentSignal, contentColor = Color.Black)
                ) { Text(Strings.t("measure_now"), style = MaterialTheme.typography.labelLarge) }
            }
            Spacer(Modifier.height(10.dp))
        }

        // --- Auto latency calibrator overlay ---
        if (calibratorOpen) {
            LatencyCalibrator(
                engine = engine,
                phase = calibPhase,
                onPhaseChange = { calibPhase = it },
                buffer = calibBuffer,
                onBufferChange = { calibBuffer = it },
                expectedFrames = calibExpected,
                onExpectedFramesChange = { calibExpected = it },
                detectedFrames = calibDetected,
                onDetectedFramesChange = { calibDetected = it },
                measuredMs = calibMeasuredMs,
                onMeasuredMsChange = { calibMeasuredMs = it },
                onApply = { ms ->
                    latencyMs = ms.toFloat()
                    AudioSettings.latencyMs = ms
                    AudioSettings.calibrated = true
                    calibrated = true
                    calibratorOpen = false
                },
                onCancel = { calibratorOpen = false }
            )
            Spacer(Modifier.height(10.dp))
        }

        // --- Settings panel ---
        if (settingsOpen) {
            SectionCard(accent = true) {
                Text(Strings.t("latency_calibration"), style = MaterialTheme.typography.titleMedium, color = AccentSignal)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${Strings.t("offset")}: ${if (latencyMs >= 0) "+" else ""}${latencyMs.toInt()} MS",
                    style = MaterialTheme.typography.bodyMedium, color = TextPrimary
                )
                Text(Strings.t("offset_hint"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Slider(
                    value = latencyMs,
                    onValueChange = { latencyMs = it; AudioSettings.latencyMs = it.toInt(); AudioSettings.calibrated = true; calibrated = true },
                    valueRange = -300f..300f,
                    colors = SliderDefaults.colors(thumbColor = AccentSignal, activeTrackColor = AccentSignal)
                )

                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { calibratorOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, AccentSignal),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSignal)
                ) { Text(Strings.t("auto_latency_test"), style = MaterialTheme.typography.labelLarge) }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(Strings.t("low_latency_mode"), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text(Strings.t("low_latency_desc"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Switch(
                        checked = lowLatency,
                        onCheckedChange = { lowLatency = it; AudioSettings.lowLatencyMode = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = AccentSignal)
                    )
                }

                Spacer(Modifier.height(14.dp))
                HairlineDivider()
                Spacer(Modifier.height(14.dp))

                Text(Strings.t("input_gain"), style = MaterialTheme.typography.titleMedium, color = AccentSignal)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LangButton(Strings.t("gain_auto"), autoGain) { autoGain = true; AudioSettings.autoGain = true }
                    LangButton(Strings.t("gain_manual"), !autoGain) { autoGain = false; AudioSettings.autoGain = false }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    Strings.t(if (autoGain) "gain_auto_desc" else "gain_manual_desc"),
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary
                )
                if (!autoGain) {
                    Text(
                        "${if (gainDb >= 0) "+" else ""}${gainDb.toInt()} DB",
                        style = MaterialTheme.typography.bodyMedium, color = TextPrimary
                    )
                    Slider(
                        value = gainDb,
                        onValueChange = { gainDb = it; AudioSettings.inputGainDb = it },
                        valueRange = -12f..36f,
                        colors = SliderDefaults.colors(thumbColor = AccentSignal, activeTrackColor = AccentSignal)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().height(8.dp).background(OutlineGrey)) {
                    val m = (meter * 1.0f).coerceIn(0.001f, 1f)
                    Box(Modifier.weight(m).fillMaxHeight().background(if (meter > 0.92f) ComplementarySignal else AccentSignal))
                    if (m < 1f) Spacer(Modifier.weight(1f - m))
                }

                Spacer(Modifier.height(14.dp))
                HairlineDivider()
                Spacer(Modifier.height(14.dp))

                Text(Strings.t("appearance"), style = MaterialTheme.typography.titleMedium, color = AccentSignal)
                Spacer(Modifier.height(6.dp))
                Text(Strings.t("accent_color"), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Slider(
                    value = UiSettings.accentHue,
                    onValueChange = { UiSettings.accentHue = it; UiSettings.randomHue = false },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(thumbColor = AccentSignal, activeTrackColor = AccentSignal)
                )
                Row(
                    Modifier.fillMaxWidth().clickable { UiSettings.randomHue = !UiSettings.randomHue },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = UiSettings.randomHue,
                        onCheckedChange = { UiSettings.randomHue = it },
                        colors = CheckboxDefaults.colors(checkedColor = AccentSignal, checkmarkColor = Color.Black, uncheckedColor = OutlineGrey)
                    )
                    Text(Strings.t("random_color"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }

                Spacer(Modifier.height(10.dp))
                Text(Strings.t("language"), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LangButton("DE", UiSettings.lang == Lang.DE) { UiSettings.lang = Lang.DE }
                    LangButton("EN", UiSettings.lang == Lang.EN) { UiSettings.lang = Lang.EN }
                }

                Spacer(Modifier.height(14.dp))
                HairlineDivider()
                Spacer(Modifier.height(14.dp))

                Text(Strings.t("export"), style = MaterialTheme.typography.titleMedium, color = AccentSignal)
                Spacer(Modifier.height(6.dp))
                if (engine.tracks.none { !it.isEmpty }) {
                    Text(Strings.t("export_empty"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        engine.tracks.forEach { t ->
                            if (!t.isEmpty) {
                                OutlinedButton(
                                    onClick = {
                                        exportDir.mkdirs()
                                        val f = File(exportDir, "track${t.index + 1}.wav")
                                        engine.exportWav(t, f)
                                        Toast.makeText(context, "${Strings.t("exported")}: ${f.name}", Toast.LENGTH_LONG).show()
                                    },
                                    border = BorderStroke(1.dp, OutlineGrey),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                                ) { Text("T${t.index + 1}", style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                        Button(
                            onClick = {
                                val nonEmpty = engine.tracks.filter { !it.isEmpty }
                                if (nonEmpty.isNotEmpty()) {
                                    val len = nonEmpty.maxOf { it.buffer.size }
                                    val mix = ShortArray(len)
                                    for (t in nonEmpty) {
                                        val tl = t.buffer.size
                                        if (tl == 0) continue
                                        for (i in 0 until len) {
                                            val v = t.buffer[i % tl].toInt()
                                            mix[i] = (mix[i] + v).coerceIn(-32768, 32767).toShort()
                                        }
                                    }
                                    exportDir.mkdirs()
                                    val f = File(exportDir, "mixdown.wav")
                                    writeWav(f, mix, SAMPLE_RATE)
                                    Toast.makeText(context, "${Strings.t("exported")}: ${f.name}", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentSignal, contentColor = Color.Black)
                        ) { Text(Strings.t("export_all"), style = MaterialTheme.typography.labelLarge) }
                    }
                }

                Spacer(Modifier.height(18.dp))
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Text(
                    "by ghostsmakemusic · instagram",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://www.instagram.com/ghostsmakemusic/")
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // --- Trim editor overlay for the master-setting recording ---
        trimTrack?.let { t ->
            TrimEditor(
                title = Strings.t("trim_title"),
                buffer = t.buffer,
                initialEndFrac = (engine.pendingTrimInitialEnd.toFloat() / t.buffer.size.coerceAtLeast(1)).coerceIn(0f, 1f),
                snapFracs = t.clickGridFrames.map { it.toFloat() / t.buffer.size.toFloat() },
                onConfirm = { s, e, _ ->
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

        // --- Re-edit overlay for an already-committed track (length preserved,
        // always reads from the track's persistent raw capture so widening back
        // out after narrowing never loses audio) ---
        editTrack?.let { t ->
            // snapshot for undo/cancel + make sure an untrimmed source exists
            androidx.compose.runtime.LaunchedEffect(t) { engine.beginEdit(t) }
            val editSource = if (t.rawCapture.isNotEmpty()) t.rawCapture else t.buffer
            val srcLen = editSource.size.coerceAtLeast(1)
            val outLen = t.buffer.size.coerceAtLeast(1)
            TrimEditor(
                title = "${Strings.t("edit_title")} ${t.index + 1}",
                buffer = editSource,
                initialStartFrac = (t.activeWindowStart.toFloat() / srcLen).coerceIn(0f, 1f),
                initialEndFrac = (t.activeWindowEnd.toFloat() / srcLen).coerceIn(0f, 1f),
                changesLoopLength = engine.isSoloTrack(t),
                onPreview = { s, e, rep -> engine.previewEdit(t, s, e, rep) },
                playheadFor = { s, e, rep ->
                    val len = t.buffer.size
                    if (len <= 0 || !engine.isPlaying) null
                    else {
                        val p = (engine.globalPos % len).toInt()
                        val seg = (e - s).coerceAtLeast(1)
                        val local = if (engine.isSoloTrack(t)) p else if (rep) p % seg else kotlin.math.min(p, seg)
                        ((s + local).toFloat() / srcLen).coerceIn(0f, 1f)
                    }
                },
                isPlaying = { engine.isPlaying },
                onTogglePlay = { if (engine.isPlaying) engine.stopAll() else engine.playAll() },
                onConfirm = { s, e, rep ->
                    engine.commitEdit(t, s, e, rep)
                    editTrack = null
                    tick++
                },
                onCancel = {
                    engine.cancelEdit(t)
                    editTrack = null
                    tick++
                }
            )
            Spacer(Modifier.height(10.dp))
        }

        // --- Metronome: compact, tap BPM to expand ---
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { metronomeExpanded = !metronomeExpanded }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${Strings.t("metronome")} · ${bpm.toInt()} BPM",
                style = MaterialTheme.typography.bodyMedium,
                color = if (metronomeOn) TextPrimary else TextSecondary
            )
            Switch(
                checked = metronomeOn,
                onCheckedChange = {
                    metronomeOn = it
                    engine.bpm = bpm.toInt()
                    engine.setMetronome(it)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = AccentSignal)
            )
        }
        if (metronomeExpanded) {
            Slider(
                value = bpm,
                onValueChange = { bpm = it; engine.bpm = it.toInt() },
                valueRange = 40f..220f,
                colors = SliderDefaults.colors(thumbColor = AccentSignal, activeTrackColor = AccentSignal)
            )
        }
        HairlineDivider()
        Spacer(Modifier.height(10.dp))

        Text(
            text = if (engine.isMasterSet())
                "${Strings.t("loop_length")}: %.2fS".format(engine.masterLoopLengthFrames / SAMPLE_RATE.toFloat())
            else Strings.t("loop_length_pending"),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(4.dp))
        SyncBar(engine = engine, phase = enginePhase)

        Spacer(Modifier.height(if (compactMode) 4.dp else 10.dp))

        key(tick) {
            engine.tracks.forEach { track ->
                TrackRow(
                    track = track,
                    isRecording = recordingTrackIndex == track.index,
                    canAct = (!busy || recordingTrackIndex == track.index) && (recordingTrackIndex == -1 || recordingTrackIndex == track.index),
                    isMasterSet = engine.isMasterSet(),
                    selectedMultiplier = multipliers[track.index] ?: 1f,
                    onMultiplierChange = { multipliers[track.index] = it },
                    compact = compactMode,
                    onRecordToggle = {
                        if (!busy || recordingTrackIndex == track.index || enginePhase != RecordPhase.NONE) {
                            engine.pressRecord(track, multipliers[track.index] ?: 1f)
                        }
                    },
                    onClear = { engine.clear(track); tick++ },
                    onUndo = { engine.undo(track); tick++ },
                    onMuteToggle = { track.muted = !track.muted; track.updateVolume(); tick++ },
                    onVolumeChange = { v -> track.volume = v; track.updateVolume() },
                    onEdit = { editTrack = track },
                    recordColor = when {
                        recordingTrackIndex != track.index -> null
                        enginePhase == RecordPhase.OVERDUBBING -> OverdubSignal
                        enginePhase == RecordPhase.RECORDING_FRESH -> ComplementarySignal
                        enginePhase == RecordPhase.WAITING -> AccentDim
                        else -> null
                    }
                )
                Spacer(Modifier.height(if (compactMode) 4.dp else 8.dp))
            }
        }

        Spacer(Modifier.height(if (compactMode) 2.dp else 6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { engine.playAll(); tick++ },
                border = BorderStroke(1.dp, OutlineGrey),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text(Strings.t("play_all"), style = MaterialTheme.typography.labelLarge) }

            OutlinedButton(
                onClick = { engine.stopAll(); tick++ },
                border = BorderStroke(1.dp, OutlineGrey),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text(Strings.t("stop_all"), style = MaterialTheme.typography.labelLarge) }

            OutlinedButton(
                onClick = { engine.clearAll(); tick++ },
                border = BorderStroke(1.dp, AccentDim),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSignal)
            ) { Text(Strings.t("reset"), style = MaterialTheme.typography.labelLarge) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SyncBar(engine: LooperEngine, phase: RecordPhase = RecordPhase.NONE) {
    var progress by remember { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }

    // Reads the engine's single frame counter - the same one that drives the audio.
    LaunchedEffect(Unit) {
        while (true) {
            val m = engine.masterLoopLengthFrames
            playing = engine.isPlaying && m > 0
            progress = if (playing) (engine.globalPos % m).toFloat() / m.toFloat() else 0f
            delay(25)
        }
    }

    val barColor = when (phase) {
        RecordPhase.RECORDING_FRESH -> ComplementarySignal
        RecordPhase.OVERDUBBING -> OverdubSignal
        else -> AccentSignal
    }

    if (engine.isMasterSet()) {
        Row(
            Modifier.fillMaxWidth().height(10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            repeat(16) { i ->
                val filled = playing && progress >= i / 16f
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (filled) barColor else OutlineGrey)
                )
            }
        }
    }
}

@Composable
fun LangButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) AccentSignal else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) AccentSignal else OutlineGrey))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.Black else TextSecondary
        )
    }
}

@Composable
fun HairlineDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(OutlineGrey)
    )
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
            Strings.t("mute"),
            style = MaterialTheme.typography.bodySmall,
            color = if (active) Color.Black else TextSecondary
        )
    }
}

@Composable
fun MultiplierPicker(selected: Float, onSelect: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.5f to "½×", 1f to "1×", 2f to "2×").forEach { (value, label) ->
            val isSel = selected == value
            Box(
                Modifier
                    .background(if (isSel) AccentSignal else Color.Transparent)
                    .border(BorderStroke(1.dp, if (isSel) AccentSignal else OutlineGrey))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = if (isSel) Color.Black else TextSecondary)
            }
        }
    }
}

@Composable
fun CompactIconButton(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) AccentSignal else Color.Transparent)
            .border(BorderStroke(1.dp, if (active) AccentSignal else OutlineGrey))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (!enabled) OutlineGrey else if (active) Color.Black else TextSecondary
        )
    }
}

@Composable
fun TrackRow(
    track: Track,
    isRecording: Boolean,
    canAct: Boolean,
    isMasterSet: Boolean,
    selectedMultiplier: Float,
    onMultiplierChange: (Float) -> Unit,
    onRecordToggle: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onMuteToggle: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onEdit: () -> Unit,
    recordColor: Color? = null,
    compact: Boolean = false
) {
    var volumeSlider by remember(track.index) { mutableStateOf(track.volume) }
    val activeColor = recordColor ?: AccentSignal

    if (compact) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .border(BorderStroke(1.dp, if (isRecording) activeColor else OutlineGrey))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${track.index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (track.isEmpty) TextSecondary else TextPrimary,
                    modifier = Modifier.width(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = onRecordToggle,
                    enabled = canAct,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) activeColor else SurfaceDark,
                        contentColor = if (isRecording) Color.Black else AccentSignal,
                        disabledContainerColor = SurfaceDark,
                        disabledContentColor = TextSecondary
                    ),
                    border = BorderStroke(1.dp, if (isRecording) activeColor else AccentDim)
                ) {
                    Text(
                        if (isRecording) "■" else if (track.isEmpty) "● REC" else "● OD",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.width(6.dp))
                CompactIconButton("M", track.muted, canAct, onMuteToggle)
                Spacer(Modifier.width(3.dp))
                CompactIconButton("U", false, canAct && !track.isEmpty, onUndo)
                Spacer(Modifier.width(3.dp))
                CompactIconButton("E", false, canAct && !track.isEmpty, onEdit)
                Spacer(Modifier.width(3.dp))
                CompactIconButton("C", false, canAct && !track.isEmpty, onClear)
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = volumeSlider,
                    onValueChange = { volumeSlider = it; onVolumeChange(it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = if (track.isEmpty) TextSecondary else AccentSignal,
                        activeTrackColor = if (track.isEmpty) TextSecondary else AccentSignal,
                        inactiveTrackColor = OutlineGrey
                    )
                )
            }
            if (track.isEmpty && isMasterSet && !isRecording) {
                Spacer(Modifier.height(2.dp))
                Row(Modifier.padding(start = 22.dp)) {
                    MultiplierPicker(selected = selectedMultiplier, onSelect = onMultiplierChange)
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .border(BorderStroke(1.dp, if (isRecording) activeColor else OutlineGrey))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${Strings.t("track")} ${track.index + 1}",
                style = MaterialTheme.typography.titleMedium,
                color = if (track.isEmpty) TextSecondary else TextPrimary
            )
            Button(
                onClick = onRecordToggle,
                enabled = canAct,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) activeColor else SurfaceDark,
                    contentColor = if (isRecording) Color.Black else AccentSignal,
                    disabledContainerColor = SurfaceDark,
                    disabledContentColor = TextSecondary
                ),
                border = BorderStroke(1.dp, if (isRecording) activeColor else AccentDim)
            ) {
                Text(
                    if (isRecording) Strings.t("stop")
                    else if (track.isEmpty) Strings.t("rec")
                    else Strings.t("overdub"),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        if (track.isEmpty && isMasterSet && !isRecording) {
            Spacer(Modifier.height(8.dp))
            Text(Strings.t("new_track_length"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(4.dp))
            MultiplierPicker(selected = selectedMultiplier, onSelect = onMultiplierChange)
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            MuteToggle(active = track.muted, onClick = onMuteToggle)
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onUndo, enabled = !track.isEmpty && canAct) {
                Text(Strings.t("undo"), style = MaterialTheme.typography.bodySmall, color = if (!track.isEmpty) TextPrimary else TextSecondary)
            }
            TextButton(onClick = onEdit, enabled = !track.isEmpty && canAct) {
                Text(Strings.t("edit"), style = MaterialTheme.typography.bodySmall, color = if (!track.isEmpty) TextPrimary else TextSecondary)
            }
            TextButton(onClick = onClear, enabled = !track.isEmpty && canAct) {
                Text(Strings.t("clear"), style = MaterialTheme.typography.bodySmall, color = if (!track.isEmpty) TextPrimary else TextSecondary)
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
