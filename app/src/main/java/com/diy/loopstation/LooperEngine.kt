package com.diy.loopstation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

const val SAMPLE_RATE = 44100
const val BLOCK_FRAMES = 256

/** What the engine is doing recording-wise right now (drives UI colors). */
enum class RecordPhase { NONE, WAITING, RECORDING_FRESH, OVERDUBBING }

/** Global audio settings. */
object AudioSettings {
    /** Round-trip latency in ms (output + input). Recorded audio is moved this much
     *  earlier in time so it lands exactly where it was played. */
    var latencyMs: Int = 0
    /** Requests the low-latency (fast mixer) path for input and output streams. */
    var lowLatencyMode: Boolean = true
    /** AUTO: every take is normalised to a good level after recording. MANUAL: fixed input gain. */
    @Volatile var autoGain: Boolean = true
    @Volatile var inputGainDb: Float = 0f
    /** Has the user measured/applied a latency value at least once? */
    var calibrated: Boolean = false

    fun offsetFrames(): Int = (latencyMs * SAMPLE_RATE) / 1000
}

class Track(val index: Int) {
    @Volatile var buffer: ShortArray = ShortArray(0)
    @Volatile var lastBuffer: ShortArray = ShortArray(0)
    @Volatile var volume: Float = 1f
    @Volatile var muted: Boolean = false
    @Volatile var isEmpty: Boolean = true
    /** Full, untrimmed audio of the last take. Edits read from here so the in/out
     *  window can be widened again after narrowing it. */
    @Volatile var rawCapture: ShortArray = ShortArray(0)
    @Volatile var lastRawCapture: ShortArray = ShortArray(0)
    /** Metronome click positions (frames) inside [buffer], frame 0 = click 1. */
    @Volatile var clickGridFrames: List<Int> = emptyList()
    @Volatile var activeWindowStart: Int = 0
    @Volatile var activeWindowEnd: Int = 0

    /** Volume is read by the audio loop every sample - nothing to push anywhere. */
    fun updateVolume() {}
}

/**
 * Single-clock looper engine - the way hardware loopers and apps like Loopy or
 * Mobius work:
 *
 *  - ONE audio thread runs a full-duplex loop: read a block from the mic, mix all
 *    tracks, write the block to the speaker.
 *  - ONE frame counter [globalPos] is the playhead. Every track plays sample
 *    (globalPos mod trackLength). Nothing else keeps time: not nanoTime, not threads.
 *    So tracks can never drift against each other.
 *  - "Wait until the loop restarts", auto-stop, overdub position, the metronome and
 *    the transport bar in the UI all derive from that same counter.
 *  - Round-trip latency L is applied as a position offset: a mic sample read while
 *    the playhead is at g belongs to musical position g - L.
 */
class LooperEngine {
    val tracks = List(4) { Track(it) }

    // ---- state read by the UI (written only by the audio thread / commands) ----
    @Volatile var masterLoopLengthFrames = 0
    @Volatile var globalPos = 0L
    @Volatile var isPlaying = false
    @Volatile var recordingPhase = RecordPhase.NONE
    @Volatile var activeRecordingTrackIndex = -1
    @Volatile var revision = 0
    @Volatile var pendingTrim = -1
    @Volatile var pendingTrimInitialEnd = 0
    @Volatile var metronomeOn = false
    @Volatile var bpm = 100
    /** Input level 0..1 (peak with decay) for the meter. */
    @Volatile var inputPeak = 0f

    fun isMasterSet() = masterLoopLengthFrames > 0

    // ---- audio-thread-only state ----
    private enum class Mode {
        IDLE, FIRST_WAIT, FIRST_REC, FIRST_STOPPING, FRESH_WAIT, FRESH_REC, OVERDUB, LATENCY_TEST
    }
    private var mode = Mode.IDLE
    private var modeTrack: Track? = null
    private var clickPos = 0L
    private var firstBuf = ShortArray(SAMPLE_RATE * 30)
    private var firstLen = 0
    private var firstCaptured = 0
    private var firstQuantized = false
    private var firstExtra = 0
    private var freshT = 0
    private var freshLo = 0L
    private var freshHi = 0L
    private var freshMult = 1f

    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var running = false
    private var audioThread: Thread? = null

    private fun post(c: () -> Unit) { commands.add(c) }
    private fun bump() { revision++ }

    // ---- metronome click tables ----
    private val clickLen = 1100
    private fun makeClick(freq: Double, amp: Double) = ShortArray(clickLen) { i ->
        val t = i.toDouble() / SAMPLE_RATE
        (sin(2 * PI * freq * t) * amp * exp(-t * 70.0)).toInt().toShort()
    }
    private val clickNormal = makeClick(1000.0, 7000.0)
    private val clickAccent = makeClick(1600.0, 11000.0)

    private fun clickAt(ref: Long): Int {
        val beat = (SAMPLE_RATE * 60L / bpm.coerceAtLeast(1))
        val p = (ref % beat).toInt()
        if (p >= clickLen) return 0
        val accent = (ref / beat) % 4L == 0L
        return (if (accent) clickAccent[p] else clickNormal[p]).toInt()
    }

    // ---- latency test state ----
    private var testBurst = ShortArray(0)
    private var testIn = ShortArray(0)
    private var testStarts = IntArray(0)
    private var testPos = 0
    private var testGap = 0
    private var testCallback: ((Int, ShortArray, List<Int>, List<Int>) -> Unit)? = null

    // ---- gain helpers ----
    private fun normGain(data: ShortArray): Float {
        var peak = 0
        for (v in data) { val a = abs(v.toInt()); if (a > peak) peak = a }
        if (peak < 600) return 1f // silence / pure noise - don't pump it up
        return (0.7f * 32767f / peak).coerceIn(0.3f, 20f)
    }

    private fun applyGain(data: ShortArray, gain: Float) {
        if (abs(gain - 1f) < 0.02f) return
        for (i in data.indices) data[i] = (data[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
    }

    private fun fadeEdges(d: ShortArray, len: Int = 48) {
        val l = min(len, d.size / 2)
        for (i in 0 until l) {
            val k = i.toFloat() / l
            d[i] = (d[i] * k).toInt().toShort()
            d[d.size - 1 - i] = (d[d.size - 1 - i] * k).toInt().toShort()
        }
    }

    private var ovLayer = ShortArray(0)

    // ================= lifecycle =================

    fun start() {
        if (running) return
        running = true
        audioThread = thread(start = true, name = "looper-audio") { audioLoop() }
    }

    fun shutdown() {
        running = false
        try { audioThread?.join(800) } catch (e: InterruptedException) {}
        audioThread = null
    }

    // ================= UI commands =================

    fun setMetronome(on: Boolean) = post {
        if (on && !metronomeOn) clickPos = 0
        metronomeOn = on
    }

    fun playAll() = post {
        if (!isMasterSet() || mode == Mode.LATENCY_TEST) return@post
        globalPos = 0
        isPlaying = true
    }

    fun stopAll() = post {
        when (mode) {
            Mode.OVERDUB -> finishOverdub()
            Mode.FRESH_WAIT, Mode.FRESH_REC -> abortFresh()
            Mode.FIRST_WAIT -> { mode = Mode.IDLE; modeTrack = null; setPhase(RecordPhase.NONE, -1) }
            else -> {}
        }
        isPlaying = false
        globalPos = 0
    }

    /** Press on a track's record button. Behaviour depends on state - see processBlock. */
    fun pressRecord(track: Track, multiplier: Float) = post {
        if (mode == Mode.LATENCY_TEST || pendingTrim >= 0) return@post
        val me = modeTrack === track
        when {
            // --- toggling the track that is currently active ---
            me && mode == Mode.FIRST_WAIT -> { mode = Mode.IDLE; modeTrack = null; setPhase(RecordPhase.NONE, -1) }
            me && mode == Mode.FIRST_REC -> stopFirstTake()
            me && mode == Mode.FRESH_WAIT -> abortFresh()
            me && mode == Mode.FRESH_REC -> abortFresh()
            me && mode == Mode.OVERDUB -> finishOverdub()
            mode != Mode.IDLE -> {} // busy with another track
            // --- starting something new ---
            !isMasterSet() -> beginFirstTake(track)
            track.isEmpty -> beginFresh(track, multiplier)
            else -> beginOverdub(track)
        }
    }

    fun undo(track: Track) {
        track.buffer = track.lastBuffer
        track.rawCapture = track.lastRawCapture
        track.isEmpty = track.buffer.isEmpty()
        track.activeWindowStart = 0
        track.activeWindowEnd = track.rawCapture.size
        post { if (tracks.all { it.isEmpty }) resetTransport() }
        bump()
    }

    fun clear(track: Track) {
        track.lastBuffer = track.buffer
        track.lastRawCapture = track.rawCapture
        track.isEmpty = true
        track.buffer = ShortArray(0)
        track.rawCapture = ShortArray(0)
        track.activeWindowStart = 0
        track.activeWindowEnd = 0
        track.clickGridFrames = emptyList()
        post { if (tracks.all { it.isEmpty }) resetTransport() }
        bump()
    }

    fun clearAll() {
        post {
            if (mode != Mode.LATENCY_TEST) { mode = Mode.IDLE; modeTrack = null; setPhase(RecordPhase.NONE, -1) }
        }
        tracks.forEach { clear(it) }
    }

    private fun resetTransport() {
        masterLoopLengthFrames = 0
        isPlaying = false
        globalPos = 0
        bump()
    }

    fun finalizeMasterTrim(track: Track, startFrame: Int, endFrame: Int) {
        val source = if (track.rawCapture.isNotEmpty()) track.rawCapture else track.buffer
        val s = startFrame.coerceIn(0, source.size)
        val e = endFrame.coerceIn(s + 1, source.size)
        val buf = source.copyOfRange(s, e)
        fadeEdges(buf)
        track.buffer = buf
        track.activeWindowStart = s
        track.activeWindowEnd = e
        pendingTrim = -1
        post {
            track.isEmpty = false
            masterLoopLengthFrames = buf.size
            globalPos = 0
            isPlaying = true
            bump()
        }
    }

    fun cancelMasterTrim(track: Track) {
        track.buffer = track.lastBuffer
        track.rawCapture = track.lastRawCapture
        track.isEmpty = true
        track.clickGridFrames = emptyList()
        pendingTrim = -1
        bump()
    }

    /** Builds the playable buffer for an edit window: the chosen part of the untrimmed
     *  capture goes to the loop start. Total length stays the same (sync stays intact).
     *  [repeat] = the window loops (Simpler loop mode) to fill the track, otherwise
     *  the rest is silence (one-shot). */
    private fun buildEdited(source: ShortArray, s0: Int, e0: Int, outLen: Int, repeat: Boolean): ShortArray {
        val s = s0.coerceIn(0, source.size)
        val e = e0.coerceIn(s + 1, source.size)
        val segment = source.copyOfRange(s, e)
        fadeEdges(segment)
        val out = ShortArray(outLen.coerceAtLeast(1))
        if (!repeat) {
            segment.copyInto(out, 0, 0, min(segment.size, out.size))
        } else {
            var pos = 0
            while (pos < out.size) {
                val n = min(segment.size, out.size - pos)
                segment.copyInto(out, pos, 0, n)
                pos += n
            }
        }
        return out
    }

    private fun editSource(track: Track) = if (track.rawCapture.isNotEmpty()) track.rawCapture else track.buffer

    /** Call once when the edit view opens: remembers the state for undo / cancel. */
    fun beginEdit(track: Track) {
        if (track.rawCapture.isEmpty()) track.rawCapture = track.buffer.copyOf()
        track.lastBuffer = track.buffer
        track.lastRawCapture = track.rawCapture
    }

    /** Live audition while IN/OUT are being moved - the running loop changes immediately. */
    fun previewEdit(track: Track, startFrame: Int, endFrame: Int, repeat: Boolean) {
        val src = editSource(track)
        if (src.isEmpty()) return
        track.buffer = buildEdited(src, startFrame, endFrame, track.lastBuffer.size.coerceAtLeast(track.buffer.size), repeat)
    }

    fun commitEdit(track: Track, startFrame: Int, endFrame: Int, repeat: Boolean) {
        val src = editSource(track)
        if (src.isEmpty()) return
        val s = startFrame.coerceIn(0, src.size)
        val e = endFrame.coerceIn(s + 1, src.size)
        track.buffer = buildEdited(src, s, e, track.lastBuffer.size.coerceAtLeast(1), repeat)
        track.activeWindowStart = s
        track.activeWindowEnd = e
        bump()
    }

    fun cancelEdit(track: Track) {
        track.buffer = track.lastBuffer
        track.rawCapture = track.lastRawCapture
        bump()
    }

    fun exportWav(track: Track, outFile: File) = writeWav(outFile, track.buffer, SAMPLE_RATE)

    // ================= mode transitions (audio thread) =================

    private fun setPhase(p: RecordPhase, trackIndex: Int) {
        recordingPhase = p
        activeRecordingTrackIndex = trackIndex
    }

    private fun beginFirstTake(track: Track) {
        track.lastBuffer = track.buffer
        track.lastRawCapture = track.rawCapture
        modeTrack = track
        firstLen = 0
        firstQuantized = metronomeOn
        if (firstQuantized) {
            mode = Mode.FIRST_WAIT
            setPhase(RecordPhase.WAITING, track.index)
        } else {
            mode = Mode.FIRST_REC
            setPhase(RecordPhase.RECORDING_FRESH, track.index)
        }
    }

    private fun stopFirstTake() {
        firstCaptured = firstLen
        val lat = AudioSettings.offsetFrames()
        if (lat > 0) { firstExtra = lat; mode = Mode.FIRST_STOPPING } else finishFirstTake()
    }

    private fun finishFirstTake() {
        val track = modeTrack ?: return
        val lat = AudioSettings.offsetFrames()
        val raw = ShortArray(firstCaptured)
        if (lat >= 0) {
            // sample at recorded index i belongs to musical position i - lat
            val avail = min(firstCaptured, firstLen - lat)
            if (avail > 0) System.arraycopy(firstBuf, lat, raw, 0, avail)
        } else {
            val shift = -lat
            val n = min(firstCaptured - shift, firstLen)
            if (n > 0) System.arraycopy(firstBuf, 0, raw, shift, n)
        }
        if (AudioSettings.autoGain) applyGain(raw, normGain(raw))
        var grid: List<Int> = emptyList()
        var initialEnd = raw.size
        if (firstQuantized) {
            val beat = (SAMPLE_RATE * 60L / bpm.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val g = ArrayList<Int>()
            var f = 0
            while (f <= raw.size) { g.add(f); f += beat }
            grid = g
            val beats = max(1, (raw.size.toFloat() / beat).roundToInt())
            initialEnd = min(raw.size, beats * beat)
            // the metronome was only a recording aid
            metronomeOn = false
        }
        track.buffer = raw
        track.rawCapture = raw.copyOf()
        track.clickGridFrames = grid
        track.activeWindowStart = 0
        track.activeWindowEnd = raw.size
        pendingTrimInitialEnd = initialEnd
        pendingTrim = track.index
        mode = Mode.IDLE
        modeTrack = null
        setPhase(RecordPhase.NONE, -1)
        isPlaying = false
        bump()
    }

    private fun beginFresh(track: Track, multiplier: Float) {
        track.lastBuffer = track.buffer
        track.lastRawCapture = track.rawCapture
        modeTrack = track
        freshMult = multiplier
        freshT = (masterLoopLengthFrames * multiplier).roundToInt().coerceAtLeast(1)
        if (!isPlaying) { globalPos = 0; isPlaying = true }
        mode = Mode.FRESH_WAIT
        setPhase(RecordPhase.WAITING, track.index)
    }

    private fun abortFresh() {
        val t = modeTrack
        if (t != null) {
            t.buffer = t.lastBuffer
            t.rawCapture = t.lastRawCapture
            t.isEmpty = t.buffer.isEmpty()
        }
        mode = Mode.IDLE; modeTrack = null
        setPhase(RecordPhase.NONE, -1)
        bump()
    }

    private fun beginOverdub(track: Track) {
        track.lastBuffer = track.buffer.copyOf()
        track.lastRawCapture = track.rawCapture
        modeTrack = track
        ovLayer = ShortArray(track.buffer.size)
        if (!isPlaying) { globalPos = 0; isPlaying = true }
        mode = Mode.OVERDUB
        setPhase(RecordPhase.OVERDUBBING, track.index)
    }

    private fun finishOverdub() {
        val t = modeTrack
        val layer = ovLayer
        mode = Mode.IDLE; modeTrack = null
        setPhase(RecordPhase.NONE, -1)
        if (t != null) {
            val auto = AudioSettings.autoGain
            thread {
                val gain = if (auto) normGain(layer) else 1f
                val buf = t.buffer
                val n = min(buf.size, layer.size)
                for (i in 0 until n) buf[i] = (buf[i] + (layer[i] * gain).toInt()).coerceIn(-32768, 32767).toShort()
                t.rawCapture = buf.copyOf()
                t.activeWindowStart = 0
                t.activeWindowEnd = buf.size
                bump()
            }
        }
        bump()
    }

    private fun finishFresh() {
        val t = modeTrack
        mode = Mode.IDLE; modeTrack = null
        setPhase(RecordPhase.NONE, -1)
        if (t != null) {
            val auto = AudioSettings.autoGain
            thread {
                if (auto) applyGain(t.buffer, normGain(t.buffer))
                t.rawCapture = t.buffer.copyOf()
                t.activeWindowStart = 0
                t.activeWindowEnd = t.buffer.size
                t.isEmpty = false
                bump()
            }
        }
        bump()
    }

    // ================= audio loop =================

    private fun audioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        var rec: AudioRecord? = null
        var at: AudioTrack? = null
        try {
            val minIn = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val minOut = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minIn, BLOCK_FRAMES * 2 * 4)
            )
            val b = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setBufferSizeInBytes(max(minOut, BLOCK_FRAMES * 2 * 3))
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (AudioSettings.lowLatencyMode) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            at = b.build()

            val inBuf = ShortArray(BLOCK_FRAMES)
            val outBuf = ShortArray(BLOCK_FRAMES)
            at.write(outBuf, 0, BLOCK_FRAMES) // prime
            rec.startRecording()
            at.play()

            while (running) {
                while (true) { val c = commands.poll() ?: break; c() }

                var n = 0
                while (n < BLOCK_FRAMES && running) {
                    val r = rec.read(inBuf, n, BLOCK_FRAMES - n)
                    if (r < 0) { java.util.Arrays.fill(inBuf, n, BLOCK_FRAMES, 0); Thread.sleep(3); break }
                    n += r
                }
                processBlock(inBuf, outBuf)
                at.write(outBuf, 0, BLOCK_FRAMES)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { rec?.stop() } catch (e: Exception) {}
            try { rec?.release() } catch (e: Exception) {}
            try { at?.stop() } catch (e: Exception) {}
            try { at?.release() } catch (e: Exception) {}
        }
    }

    private fun processBlock(inp: ShortArray, out: ShortArray) {
        val lat = AudioSettings.offsetFrames()
        val master = masterLoopLengthFrames
        val playing = isPlaying && master > 0
        var g = globalPos
        val metro = metronomeOn
        val manual = !AudioSettings.autoGain
        val gainLin = Math.pow(10.0, AudioSettings.inputGainDb / 20.0).toFloat()
        var blockPeak = 0

        for (f in 0 until BLOCK_FRAMES) {
            val xr = inp[f].toInt()
            val x = if (manual) (xr * gainLin).toInt().coerceIn(-32768, 32767) else xr
            val ax = abs(x)
            if (ax > blockPeak) blockPeak = ax
            var acc = 0

            // ---------- mode logic (uses the mic sample x at playhead g) ----------
            when (mode) {
                Mode.FIRST_WAIT -> {
                    val bar = (SAMPLE_RATE * 60L / bpm.coerceAtLeast(1)) * 4
                    if (clickPos % bar == 0L) {
                        mode = Mode.FIRST_REC
                        setPhase(RecordPhase.RECORDING_FRESH, modeTrack?.index ?: -1)
                        firstLen = 0
                    }
                }
                else -> {}
            }
            when (mode) {
                Mode.FIRST_REC -> storeFirst(x)
                Mode.FIRST_STOPPING -> {
                    storeFirst(x)
                    if (--firstExtra <= 0) finishFirstTake()
                }
                Mode.FRESH_WAIT -> if (playing && g % master == 0L) {
                    val t = modeTrack!!
                    t.buffer = ShortArray(freshT)
                    freshLo = g + max(lat, 0)
                    freshHi = freshLo + freshT
                    mode = Mode.FRESH_REC
                    setPhase(RecordPhase.RECORDING_FRESH, t.index)
                }
                else -> {}
            }
            if (mode == Mode.FRESH_REC && playing) {
                if (g >= freshHi) finishFresh()
                else if (g >= freshLo) {
                    val buf = modeTrack!!.buffer
                    val idx = (((g - lat) % freshT) + freshT) % freshT
                    buf[idx.toInt()] = x.toShort()
                }
            }
            if (mode == Mode.OVERDUB && playing) {
                // recorded into a separate layer (heard live), merged + levelled on stop
                val layer = ovLayer
                val len = layer.size
                if (len > 0) {
                    val idx = ((((g - lat) % len) + len) % len).toInt()
                    layer[idx] = (layer[idx] + x).coerceIn(-32768, 32767).toShort()
                }
            }

            // ---------- output ----------
            if (mode == Mode.LATENCY_TEST) {
                acc += latencyTestSample(xr)
            } else {
                if (playing) {
                    for (t in tracks) {
                        if (t.isEmpty || t.muted) continue
                        val buf = t.buffer
                        val len = buf.size
                        if (len == 0) continue
                        acc += (buf[(g % len).toInt()] * t.volume).toInt()
                    }
                    if (mode == Mode.OVERDUB && ovLayer.isNotEmpty()) acc += ovLayer[(g % ovLayer.size).toInt()].toInt()
                    acc = (acc * 0.9f).toInt()
                }
                if (metro) acc += clickAt(if (master > 0) g else clickPos)
            }
            out[f] = acc.coerceIn(-32768, 32767).toShort()

            // ---------- advance clocks ----------
            if (playing) g++
            if (metro) clickPos++
        }
        globalPos = g
        inputPeak = max(blockPeak / 32768f, inputPeak * 0.9f)
    }

    private fun storeFirst(x: Int) {
        if (firstLen >= firstBuf.size) firstBuf = firstBuf.copyOf(firstBuf.size * 2)
        firstBuf[firstLen++] = x.toShort()
    }

    // ================= latency test =================

    private var testClap = false
    private var testSkip = 0

    /** Speaker bursts, detected acoustically through the mic (no human involved). */
    fun runAutoLatencyTest(
        bursts: Int = 5,
        gapMs: Int = 450,
        onResult: (measuredMs: Int, buffer: ShortArray, expectedFrames: List<Int>, detectedFrames: List<Int>) -> Unit
    ) = startTest(false, bursts, gapMs, onResult)

    /** Click track in the headphones, the user claps along; transients are detected. */
    fun runClapLatencyTest(
        onResult: (measuredMs: Int, buffer: ShortArray, expectedFrames: List<Int>, detectedFrames: List<Int>) -> Unit
    ) = startTest(true, 12, 667, onResult)

    private fun startTest(
        clap: Boolean, bursts: Int, gapMs: Int,
        onResult: (Int, ShortArray, List<Int>, List<Int>) -> Unit
    ) = post {
        if (mode != Mode.IDLE) return@post
        val burstFrames = (SAMPLE_RATE * 0.03).toInt()
        testBurst = ShortArray(burstFrames) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-i / (burstFrames * 0.25))
            (sin(2 * PI * 2200.0 * t) * 30000.0 * env).toInt().toShort()
        }
        testClap = clap
        testSkip = if (clap) 4 else 0
        testGap = SAMPLE_RATE * gapMs / 1000
        val first = if (clap) SAMPLE_RATE / 2 else testGap / 2
        testStarts = IntArray(bursts) { first + it * testGap }
        testIn = ShortArray(testStarts.last() + testGap + SAMPLE_RATE / 3)
        testPos = 0
        testCallback = onResult
        isPlaying = false
        mode = Mode.LATENCY_TEST
        setPhase(RecordPhase.NONE, -1)
    }

    /** Output sample for the current test frame; stores the (ungained) mic input. */
    private fun latencyTestSample(x: Int): Int {
        if (testPos >= testIn.size) return 0
        var y = 0
        for (k in testStarts.indices) {
            val i = testPos - testStarts[k]
            if (i < 0) break
            if (testClap) {
                if (i < clickLen) { y = (if (k % 4 == 0) clickAccent[i] else clickNormal[i]).toInt() * 2; break }
            } else if (i < testBurst.size) { y = testBurst[i].toInt(); break }
        }
        testIn[testPos] = x.toShort()
        testPos++
        if (testPos >= testIn.size) finishLatencyTest()
        return y
    }

    private fun finishLatencyTest() {
        val cb = testCallback
        val data = testIn
        val clap = testClap
        val starts = testStarts.toList().drop(testSkip)
        val gap = testGap
        mode = Mode.IDLE
        testCallback = null
        thread {
            val detected = ArrayList<Int>()
            val rts = ArrayList<Int>()
            for (exp in starts) {
                val lo = if (clap) (exp - SAMPLE_RATE / 5).coerceAtLeast(0) else exp
                val hi = (if (clap) exp + SAMPLE_RATE / 3 else exp + gap).coerceAtMost(data.size)
                val fs = if (clap) 0 else (exp - (SAMPLE_RATE * 0.05).toInt()).coerceAtLeast(0)
                val fe = if (clap) (SAMPLE_RATE * 0.3).toInt() else exp
                var sum = 0.0
                var cnt = 0
                for (i in fs until fe) { sum += abs(data[i].toDouble()); cnt++ }
                val floor = if (cnt > 0) sum / cnt else 0.0
                var peak = 0.0
                for (i in lo until hi) peak = max(peak, abs(data[i].toDouble()))
                if (clap && peak < 1500.0) continue
                val thr = if (clap) max(floor * 4.0, peak * 0.3) else (floor * 4.0).coerceAtLeast(500.0)
                for (i in lo until hi) {
                    if (abs(data[i].toDouble()) > thr) { detected.add(i); rts.add(i - exp); break }
                }
            }
            val med = if (rts.isNotEmpty()) rts.sorted()[rts.size / 2] else 0
            val ms = (med * 1000L / SAMPLE_RATE).toInt().coerceIn(0, 300)
            cb?.invoke(ms, data, starts, detected)
        }
    }
}

fun writeWav(file: File, data: ShortArray, sampleRate: Int) {
    val byteRate = sampleRate * 2
    val dataSize = data.size * 2
    FileOutputStream(file).use { out ->
        fun writeIntLE(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
        }
        fun writeShortLE(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); writeIntLE(36 + dataSize)
        out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
        writeIntLE(16); writeShortLE(1); writeShortLE(1)
        writeIntLE(sampleRate); writeIntLE(byteRate)
        writeShortLE(2); writeShortLE(16)
        out.write("data".toByteArray()); writeIntLE(dataSize)
        val bytes = ByteArray(dataSize)
        for (i in data.indices) {
            bytes[i * 2] = (data[i].toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = ((data[i].toInt() shr 8) and 0xff).toByte()
        }
        out.write(bytes)
    }
}
