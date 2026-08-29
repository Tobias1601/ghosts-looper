package com.diy.loopstation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Lang { DE, EN }

/** App-wide, reactive UI settings. Reading these inside a Composable or a Canvas
 *  draw block makes that spot recompose/redraw automatically when they change. */
object UiSettings {
    var accentHue by mutableStateOf(14f) // 0..360, default = signal red-orange
    var lang by mutableStateOf(Lang.DE)
}

private val DE = mapOf(
    "app_title" to "GHOSTS LOOPER",
    "app_subtitle" to "04 TRACK",
    "setup" to "SETUP",
    "mic_permission_needed" to "MIKROFON-ZUGRIFF ERFORDERLICH",
    "allow" to "ERLAUBEN",
    "appearance" to "OPTIK",
    "accent_color" to "AKZENTFARBE",
    "language" to "SPRACHE",
    "latency_calibration" to "LATENZ-KALIBRIERUNG",
    "offset" to "OFFSET",
    "offset_hint" to "POSITIV = AUFNAHME WIRD FRÜHER GESCHNITTEN (SYSTEM-DELAY AUSGLEICHEN)",
    "low_latency_mode" to "LOW-LATENCY-MODUS",
    "low_latency_desc" to "VOICE_COMM INPUT + FAST-TRACK OUTPUT",
    "auto_latency_test" to "AUTO-LATENZ-TEST (CLICK-TRACK)",
    "metronome" to "METRONOM",
    "loop_length" to "LOOP-LÄNGE",
    "loop_length_pending" to "TRACK 1 AUFNEHMEN → LEGT LOOP-LÄNGE FEST",
    "track" to "TRACK",
    "rec" to "● REC",
    "stop" to "■ STOP",
    "overdub" to "● OVERDUB",
    "mute" to "MUTE",
    "undo" to "UNDO",
    "clear" to "CLEAR",
    "export" to "EXPORT",
    "edit" to "EDIT",
    "play_all" to "▶ ALLE",
    "stop_all" to "■ ALLE",
    "reset" to "RESET",
    "compact" to "KOMPAKT",
    "exported" to "EXPORTIERT",
    "trim_title" to "TRIM // LOOP-LÄNGE FESTLEGEN",
    "edit_title" to "EDIT // TRACK",
    "trim_length" to "LÄNGE",
    "discard" to "VERWERFEN",
    "apply" to "ÜBERNEHMEN",
    "zoom" to "ZOOM",
    "position" to "POSITION",
    "new_track_length" to "LÄNGE FÜR NEUEN TRACK",
    "latency_test_intro" to "SPIELT MEHRERE KURZE TÖNE ÜBER DEN LAUTSPRECHER UND ERKENNT SIE AUTOMATISCH IM MIKROFON. HANDY RUHIG HALTEN, NICHTS TUN.",
    "latency_test_start" to "TEST STARTEN",
    "latency_test_cancel" to "ABBRECHEN",
    "latency_test_recording" to "... AUFNAHME LÄUFT - JETZT MITKLATSCHEN ...",
    "latency_test_drag_hint" to "LINIE AUF DEINEN TRANSIENTEN ZIEHEN - NÄCHSTGELEGENER CLICK WIRD REFERENZ",
    "latency_measured" to "GEMESSENE LATENZ",
    "latency_legend" to "GESTRICHELT = ERWARTET · GRÜN = ERKANNTES ECHO",
    "pinch_hint" to "MIT 2 FINGERN ZOOMEN · 1 FINGER ZUM VERSCHIEBEN",
    "reset_zoom" to "ZOOM RESET",
    "sync" to "SYNC",
    "redo_test" to "TEST WIEDERHOLEN",
    "latency_hits" to "TREFFER",
    "latency_test_failed" to "KEIN CLICK ERKANNT - LAUTER STELLEN ODER RUHIGEREN RAUM VERSUCHEN",
    "export_empty" to "NOCH KEINE TRACKS ZUM EXPORTIEREN",
    "export_all" to "ALLE (MIX)"
)

private val EN = mapOf(
    "app_title" to "GHOSTS LOOPER",
    "app_subtitle" to "04 TRACK",
    "setup" to "SETUP",
    "mic_permission_needed" to "MICROPHONE ACCESS REQUIRED",
    "allow" to "ALLOW",
    "appearance" to "APPEARANCE",
    "accent_color" to "ACCENT COLOR",
    "language" to "LANGUAGE",
    "latency_calibration" to "LATENCY CALIBRATION",
    "offset" to "OFFSET",
    "offset_hint" to "POSITIVE = RECORDING GETS CUT EARLIER (COMPENSATE SYSTEM DELAY)",
    "low_latency_mode" to "LOW-LATENCY MODE",
    "low_latency_desc" to "VOICE_COMM INPUT + FAST-TRACK OUTPUT",
    "auto_latency_test" to "AUTO LATENCY TEST (CLICK TRACK)",
    "metronome" to "METRONOME",
    "loop_length" to "LOOP LENGTH",
    "loop_length_pending" to "RECORD TRACK 1 → SETS LOOP LENGTH",
    "track" to "TRACK",
    "rec" to "● REC",
    "stop" to "■ STOP",
    "overdub" to "● OVERDUB",
    "mute" to "MUTE",
    "undo" to "UNDO",
    "clear" to "CLEAR",
    "export" to "EXPORT",
    "edit" to "EDIT",
    "play_all" to "▶ ALL",
    "stop_all" to "■ ALL",
    "reset" to "RESET",
    "compact" to "COMPACT",
    "exported" to "EXPORTED",
    "trim_title" to "TRIM // SET LOOP LENGTH",
    "edit_title" to "EDIT // TRACK",
    "trim_length" to "LENGTH",
    "discard" to "DISCARD",
    "apply" to "APPLY",
    "zoom" to "ZOOM",
    "position" to "POSITION",
    "new_track_length" to "LENGTH FOR NEW TRACK",
    "latency_test_intro" to "PLAYS A FEW SHORT TONES THROUGH THE SPEAKER AND DETECTS THEM AUTOMATICALLY VIA THE MIC. KEEP THE PHONE STILL, DO NOTHING ELSE.",
    "latency_test_start" to "START TEST",
    "latency_test_cancel" to "CANCEL",
    "latency_test_recording" to "... RECORDING - CLAP ALONG NOW ...",
    "latency_test_drag_hint" to "DRAG THE LINE ONTO YOUR TRANSIENT - NEAREST CLICK BECOMES THE REFERENCE",
    "latency_measured" to "MEASURED LATENCY",
    "latency_legend" to "DASHED = EXPECTED · GREEN = DETECTED ECHO",
    "pinch_hint" to "PINCH TO ZOOM · DRAG WITH 1 FINGER TO PAN",
    "reset_zoom" to "RESET ZOOM",
    "sync" to "SYNC",
    "redo_test" to "REDO TEST",
    "latency_hits" to "HITS",
    "latency_test_failed" to "NO CLICKS DETECTED - TRY LOUDER VOLUME OR A QUIETER ROOM",
    "export_empty" to "NO TRACKS TO EXPORT YET",
    "export_all" to "ALL (MIX)"
)

object Strings {
    fun t(key: String): String {
        val map = if (UiSettings.lang == Lang.DE) DE else EN
        return map[key] ?: key
    }
}
