package com.diy.loopstation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val SAMPLE_RATE = 44100

/** Global audio settings, adjustable before/while recording. */
object AudioSettings {
    /** Positive = mic/system adds delay, so we cut that much off the start of every
     *  recording to pull it back in time. Negative = pad silence at the start instead. */
    var latencyMs: Int = 0
    /** Uses AudioTrack low-latency performance mode + VOICE_COMMUNICATION input path. */
    var lowLatencyMode: Boolean = true

    fun offsetFrames(): Int = (latencyMs * SAMPLE_RATE) / 1000
}

/**
 * One loop slot. Playback uses a continuously-streaming AudioTrack (MODE_STREAM) that
 * re-reads [buffer] every cycle, so live overdub changes to the array are heard on the
 * very next pass with no glitch or restart - exactly like a hardware loop pedal.
 */
class Track(val index: Int) {
    val bufferLock = Any()
    @Volatile var buffer: ShortArray = ShortArray(0)
    @Volatile var lastBuffer: ShortArray = ShortArray(0) // for Undo
    var volume: Float = 1f
    var muted: Boolean = false
    var isEmpty: Boolean = true
    /** The full, un-trimmed audio from the last actual recording/overdub pass. Edits
     *  (reEditTrack) always read from this - never from the already-edited [buffer] -
     *  so moving the in/out points back out after narrowing them never loses audio. */
    @Volatile var rawCapture: ShortArray = ShortArray(0)
    @Volatile var lastRawCapture: ShortArray = ShortArray(0)
    /** Frame offsets of metronome clicks within [buffer], only set when this track's
     *  master-defining recording happened with the metronome running. Frame 0 is
     *  always click 1 by construction. Empty if no metronome was used. */
    @Volatile var clickGridFrames: List<Int> = emptyList()

    @Volatile private var playing = false
    private var playThread: Thread? = null
    @Volatile private var currentAudioTrack: AudioTrack? = null

    fun stopPlayback() {
        playing = false
        try { playThread?.join(300) } catch (e: InterruptedException) {}
        playThread = null
    }

    /** (Re)starts the continuous playback loop from frame 0. */
    fun startPlayback() {
        stopPlayback()
        playing = true
        playThread = thread(start = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val builder = AudioTrack.Builder()
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
                .setBufferSizeInBytes(max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (AudioSettings.lowLatencyMode) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val at = builder.build()
            currentAudioTrack = at
            at.setVolume(if (muted) 0f else volume)
            at.play()
            var pos = 0
            val chunk = ShortArray(1024)
            while (playing) {
                val len = buffer.size
                if (len == 0) {
                    Thread.sleep(15)
                    continue
                }
                if (pos >= len) pos = 0
                val n = min(chunk.size, len - pos)
                synchronized(bufferLock) {
                    System.arraycopy(buffer, pos, chunk, 0, n)
                }
                at.write(chunk, 0, n)
                pos += n
                if (pos >= len) pos = 0
            }
            try { at.stop(); at.release() } catch (e: Exception) {}
            currentAudioTrack = null
        }
    }

    fun updateVolume() {
        currentAudioTrack?.setVolume(if (muted) 0f else volume)
    }
}

/**
 * Core looper, Boss-pedal style:
 * - The first track ever recorded has no fixed length: play until you stop, then
 *   trim start/end in the UI to commit the master loop length.
 * - Every other recording is a continuous overdub: press to start, it mixes live
 *   into the track's loop buffer (wrapping around) for as many passes as you like,
 *   press again to stop. If the track is currently empty, [multiplier] (0.5/1/2)
 *   sets its own loop length relative to the master loop, so tracks can be twice as
 *   long or half as long and still stay perfectly in phase (integer frame ratios).
 */
class LooperEngine {
    val tracks = List(4) { Track(it) }
    @Volatile var masterLoopLengthFrames = 0
    @Volatile var sessionStartNanos = 0L
    private val recording = AtomicBoolean(false)
    @Volatile var activeRecordingTrackIndex = -1
        private set
    var metronome: Metronome? = null
    private val minBufSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )

    fun isMasterSet() = masterLoopLengthFrames > 0

    /** Restarts every non-empty track's playback at the same instant to re-align phase. */
    private fun resyncAll() {
        sessionStartNanos = System.nanoTime()
        tracks.forEach { if (!it.isEmpty) it.startPlayback() }
    }

    private fun waitForBoundary(rec: AudioRecord, frameSpan: Int, discardBuf: ShortArray) {
        if (frameSpan <= 0) return
        val durNanos = (frameSpan.toLong() * 1_000_000_000L) / SAMPLE_RATE
        val elapsed = (System.nanoTime() - sessionStartNanos) % durNanos
        val waitNanos = durNanos - elapsed
        val deadline = System.nanoTime() + waitNanos
        while (System.nanoTime() < deadline && recording.get()) {
            rec.read(discardBuf, 0, discardBuf.size)
        }
    }

    private fun shiftForLatency(raw: ShortArray, offsetFrames: Int): ShortArray {
        if (offsetFrames == 0) return raw
        return if (offsetFrames > 0) {
            if (offsetFrames >= raw.size) ShortArray(0) else raw.copyOfRange(offsetFrames, raw.size)
        } else {
            ShortArray(-offsetFrames) + raw
        }
    }

    /**
     * Starts recording on [track].
     * - If no master loop exists yet: free-length capture. Call stopRecording() when
     *   done; [onFinished] then receives the raw audio (non-null) so the UI can open
     *   the trim editor and call finalizeMasterTrim()/cancelMasterTrim().
     * - Otherwise: continuous overdub, aligned to this track's own loop boundary,
     *   mixed live until stopRecording() is called; [onFinished] then receives null.
     */
    fun startRecording(track: Track, multiplier: Float = 1f, onFinished: (masterCandidateRaw: ShortArray?) -> Unit) {
        if (recording.get()) return
        recording.set(true)
        activeRecordingTrackIndex = track.index

        val audioSource = if (AudioSettings.lowLatencyMode)
            MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val rec = AudioRecord(
            audioSource, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 4
        )
        rec.startRecording()

        val wasFirstTrack = !isMasterSet()
        track.lastBuffer = track.buffer.copyOf()
        track.lastRawCapture = track.rawCapture.copyOf()

        thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val readBuf = ShortArray(512)
            val offsetFrames = AudioSettings.offsetFrames()

            if (wasFirstTrack) {
                // Optional: wait for the metronome's next downbeat so the take starts on "the 1".
                val mt = metronome
                if (mt != null && mt.isRunning) {
                    val waitNanos = mt.nanosUntilNextDownbeat()
                    val deadline = System.nanoTime() + waitNanos
                    while (System.nanoTime() < deadline && recording.get()) {
                        rec.read(readBuf, 0, readBuf.size)
                    }
                }

                val chunks = ArrayList<ShortArray>()
                var total = 0
                while (recording.get()) {
                    val n = rec.read(readBuf, 0, readBuf.size)
                    if (n > 0) { chunks.add(readBuf.copyOf(n)); total += n }
                }
                rec.stop(); rec.release()

                var raw = ShortArray(total)
                var pos = 0
                for (c in chunks) { c.copyInto(raw, pos); pos += c.size }
                raw = shiftForLatency(raw, offsetFrames)

                // Frame 0 is defined as click 1 by construction (we waited for the
                // downbeat before capturing). Build the rest of the click grid purely
                // from the frame domain - no nanoTime precision issues.
                track.clickGridFrames = if (mt != null && mt.isRunning) {
                    val intervalFrames = (SAMPLE_RATE * 60L / mt.bpm).toInt().coerceAtLeast(1)
                    val grid = ArrayList<Int>()
                    var f = 0
                    while (f < raw.size) { grid.add(f); f += intervalFrames }
                    grid
                } else emptyList()

                track.buffer = raw
                track.rawCapture = raw.copyOf()
                activeRecordingTrackIndex = -1
                recording.set(false)
                onFinished(raw)
            } else {
                val targetFrames = if (track.isEmpty)
                    (masterLoopLengthFrames * multiplier).roundToInt().coerceAtLeast(1)
                else track.buffer.size

                if (track.isEmpty) {
                    synchronized(track.bufferLock) { track.buffer = ShortArray(targetFrames) }
                    track.startPlayback()
                }

                // Always align a *fresh* recording to the master loop's own frame-0 -
                // regardless of this track's own multiplier - so half/double-length
                // tracks still lock in perfectly. An ongoing overdub on an already
                // non-empty track instead waits for that track's own loop boundary.
                val boundarySpan = if (track.isEmpty) masterLoopLengthFrames else track.buffer.size
                waitForBoundary(rec, boundarySpan, readBuf)

                var writePos = 0
                while (recording.get()) {
                    val n = rec.read(readBuf, 0, readBuf.size)
                    if (n > 0) {
                        synchronized(track.bufferLock) {
                            val len = track.buffer.size
                            if (len > 0) {
                                for (i in 0 until n) {
                                    val idx = (((writePos + i - offsetFrames) % len) + len) % len
                                    // Slight headroom on both signals before summing, so
                                    // repeated overdub passes don't hard-clip into distortion.
                                    val existing = track.buffer[idx].toInt() * 0.88f
                                    val incoming = readBuf[i].toInt() * 0.88f
                                    val mixed = (existing + incoming).toInt().coerceIn(-32768, 32767)
                                    track.buffer[idx] = mixed.toShort()
                                }
                            }
                        }
                        writePos = (writePos + n) % targetFrames.coerceAtLeast(1)
                    }
                }
                rec.stop(); rec.release()
                track.isEmpty = false
                track.rawCapture = track.buffer.copyOf()
                activeRecordingTrackIndex = -1
                recording.set(false)
                onFinished(null)
            }
        }
    }

    fun stopRecording() {
        recording.set(false)
    }

    fun finalizeMasterTrim(track: Track, startFrame: Int, endFrame: Int) {
        val source = if (track.rawCapture.isNotEmpty()) track.rawCapture else track.buffer
        val s = startFrame.coerceIn(0, source.size)
        val e = endFrame.coerceIn(s + 1, source.size)
        track.buffer = source.copyOfRange(s, e)
        track.isEmpty = false
        masterLoopLengthFrames = track.buffer.size
        resyncAll()
    }

    fun cancelMasterTrim(track: Track) {
        track.buffer = track.lastBuffer
        track.rawCapture = track.lastRawCapture
        track.isEmpty = track.buffer.isEmpty()
    }

    /** Re-edit an already-committed track: keeps the overall length (so sync with
     *  other tracks is preserved), but moves the selected startFrame..endFrame window
     *  to the front of the loop and silences the rest. Always reads from the track's
     *  persistent rawCapture (never from the already-edited buffer), so you can widen
     *  the window back out after narrowing it without losing audio. Zoom for precision. */
    fun reEditTrack(track: Track, startFrame: Int, endFrame: Int) {
        val source = if (track.rawCapture.isNotEmpty()) track.rawCapture else track.buffer
        val srcLen = source.size
        if (srcLen == 0) return
        val s = startFrame.coerceIn(0, srcLen)
        val e = endFrame.coerceIn(s + 1, srcLen)
        val segment = source.copyOfRange(s, e)
        val outLen = track.buffer.size.coerceAtLeast(1)
        val newBuf = ShortArray(outLen)
        val copyLen = min(segment.size, outLen)
        segment.copyInto(newBuf, 0, 0, copyLen)
        synchronized(track.bufferLock) {
            track.lastBuffer = track.buffer
            track.lastRawCapture = track.rawCapture
            track.buffer = newBuf
        }
    }

    fun undo(track: Track) {
        synchronized(track.bufferLock) {
            track.buffer = track.lastBuffer
            track.rawCapture = track.lastRawCapture
        }
        track.isEmpty = track.buffer.isEmpty()
        if (track.isEmpty) track.stopPlayback() else track.startPlayback()
        if (tracks.all { it.isEmpty }) masterLoopLengthFrames = 0
    }

    fun clear(track: Track) {
        track.lastBuffer = track.buffer
        track.lastRawCapture = track.rawCapture
        synchronized(track.bufferLock) { track.buffer = ShortArray(0) }
        track.rawCapture = ShortArray(0)
        track.isEmpty = true
        track.stopPlayback()
        if (tracks.all { it.isEmpty }) masterLoopLengthFrames = 0
    }

    fun clearAll() { tracks.forEach { clear(it) } }
    fun stopAll() { tracks.forEach { it.stopPlayback() } }
    fun playAll() { resyncAll() }

    fun exportWav(track: Track, outFile: File) {
        writeWav(outFile, track.buffer, SAMPLE_RATE)
    }

    /**
     * Automatic round-trip latency test - the way professional audio tools actually
     * measure it (see Android's own loopback latency test): play a short, sharp burst
     * through the speaker several times, record continuously through the mic at the
     * same time, and detect exactly when each burst's echo arrives by looking for a
     * jump well above the ambient noise floor. No human reaction, no tapping, no
     * dragging - just a direct acoustic measurement. Reports the median round-trip
     * time across all detected bursts (median is robust against one bad detection).
     */
    fun runAutoLatencyTest(
        bursts: Int = 5,
        gapMs: Int = 450,
        onResult: (measuredMs: Int, buffer: ShortArray, expectedFrames: List<Int>, detectedFrames: List<Int>) -> Unit
    ) {
        if (recording.get()) return
        recording.set(true)

        val audioSource = if (AudioSettings.lowLatencyMode)
            MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val rec = AudioRecord(
            audioSource, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 4
        )
        rec.startRecording()

        thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            // Short, sharp, decaying burst - easy to pick out from background noise.
            val burstFrames = (SAMPLE_RATE * 0.03).toInt().coerceAtLeast(64)
            val burstBuf = ShortArray(burstFrames) { i ->
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = kotlin.math.exp(-i / (burstFrames * 0.25))
                (kotlin.math.sin(2 * Math.PI * 2200.0 * t) * 32000.0 * envelope).toInt().toShort()
            }

            val playSource = if (AudioSettings.lowLatencyMode)
                MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
            val at = AudioTrack.Builder()
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
                .setBufferSizeInBytes(burstFrames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            at.write(burstBuf, 0, burstBuf.size)

            val gapFrames = SAMPLE_RATE * gapMs / 1000
            val totalFrames = gapFrames * (bursts + 1)
            val out = ShortArray(totalFrames)
            val readBuf = ShortArray(64)
            val expected = ArrayList<Int>()

            var pos = 0
            var burstsLeft = bursts
            var nextBurstAt = gapFrames / 2

            while (recording.get() && pos < totalFrames) {
                if (burstsLeft > 0 && pos >= nextBurstAt) {
                    at.stop()
                    at.reloadStaticData()
                    at.play()
                    expected.add(pos)
                    nextBurstAt += gapFrames
                    burstsLeft--
                }
                val toRead = min(readBuf.size, totalFrames - pos)
                val n = rec.read(readBuf, 0, toRead)
                if (n > 0) {
                    for (i in 0 until n) out[pos + i] = readBuf[i]
                    pos += n
                }
            }
            at.release()
            rec.stop(); rec.release()
            recording.set(false)

            // For each expected burst: estimate the ambient noise floor just before
            // it, then scan forward for the first sample that jumps well above it.
            val detected = ArrayList<Int>()
            val roundTripFrames = ArrayList<Int>()
            for (exp in expected) {
                val floorStart = (exp - (SAMPLE_RATE * 0.05).toInt()).coerceAtLeast(0)
                var floorSum = 0.0
                var floorCount = 0
                for (i in floorStart until exp) {
                    floorSum += kotlin.math.abs(out[i].toDouble())
                    floorCount++
                }
                val noiseFloor = if (floorCount > 0) floorSum / floorCount else 0.0
                val threshold = (noiseFloor * 4.0).coerceAtLeast(500.0)
                val searchEnd = (exp + gapFrames).coerceAtMost(out.size)
                var found = -1
                for (i in exp until searchEnd) {
                    if (kotlin.math.abs(out[i].toDouble()) > threshold) {
                        found = i
                        break
                    }
                }
                if (found >= 0) {
                    detected.add(found)
                    roundTripFrames.add(found - exp)
                }
            }

            val measuredFrames = if (roundTripFrames.isNotEmpty())
                roundTripFrames.sorted()[roundTripFrames.size / 2]
            else 0
            val measuredMs = (measuredFrames * 1000L / SAMPLE_RATE).toInt().coerceIn(0, 300)

            onResult(measuredMs, out, expected, detected)
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
