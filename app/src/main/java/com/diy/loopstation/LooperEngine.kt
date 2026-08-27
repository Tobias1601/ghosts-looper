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

        thread {
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

                track.buffer = raw
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

                waitForBoundary(rec, targetFrames, readBuf)

                var writePos = 0
                while (recording.get()) {
                    val n = rec.read(readBuf, 0, readBuf.size)
                    if (n > 0) {
                        synchronized(track.bufferLock) {
                            val len = track.buffer.size
                            if (len > 0) {
                                for (i in 0 until n) {
                                    val idx = (((writePos + i - offsetFrames) % len) + len) % len
                                    val mixed = (track.buffer[idx].toInt() + readBuf[i].toInt())
                                        .coerceIn(-32768, 32767)
                                    track.buffer[idx] = mixed.toShort()
                                }
                            }
                        }
                        writePos = (writePos + n) % targetFrames.coerceAtLeast(1)
                    }
                }
                rec.stop(); rec.release()
                track.isEmpty = false
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
        val s = startFrame.coerceIn(0, track.buffer.size)
        val e = endFrame.coerceIn(s + 1, track.buffer.size)
        track.buffer = track.buffer.copyOfRange(s, e)
        track.isEmpty = false
        masterLoopLengthFrames = track.buffer.size
        resyncAll()
    }

    fun cancelMasterTrim(track: Track) {
        track.buffer = track.lastBuffer
        track.isEmpty = track.buffer.isEmpty()
    }

    /** Re-edit an already-committed track: keeps the overall length (so sync with
     *  other tracks is preserved), but moves the selected startFrame..endFrame window
     *  to the front of the loop and silences the rest. Zoom in the UI for precision. */
    fun reEditTrack(track: Track, startFrame: Int, endFrame: Int) {
        val len = track.buffer.size
        if (len == 0) return
        val s = startFrame.coerceIn(0, len)
        val e = endFrame.coerceIn(s + 1, len)
        val segment = track.buffer.copyOfRange(s, e)
        val newBuf = ShortArray(len)
        val copyLen = min(segment.size, len)
        segment.copyInto(newBuf, 0, 0, copyLen)
        synchronized(track.bufferLock) {
            track.lastBuffer = track.buffer
            track.buffer = newBuf
        }
    }

    fun undo(track: Track) {
        synchronized(track.bufferLock) { track.buffer = track.lastBuffer }
        track.isEmpty = track.buffer.isEmpty()
        if (track.isEmpty) track.stopPlayback() else track.startPlayback()
        if (tracks.all { it.isEmpty }) masterLoopLengthFrames = 0
    }

    fun clear(track: Track) {
        track.lastBuffer = track.buffer
        synchronized(track.bufferLock) { track.buffer = ShortArray(0) }
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
     * Plays a short count-in (2 quiet lead clicks, not used for measurement) followed
     * by a click-track (4 accented clicks) while recording the mic at the same time.
     * Returns the recorded buffer plus the exact frame index of every *measured* click,
     * so the UI can show them as reference lines against the user's tapped-along transient.
     */
    fun runLatencyTest(
        bpm: Int = 100,
        leadClicks: Int = 2,
        clicks: Int = 4,
        onResult: (buffer: ShortArray, clickFrames: List<Int>) -> Unit
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
            val intervalFrames = (SAMPLE_RATE * 60L / bpm).toInt()
            val totalTicks = leadClicks + clicks
            val totalFrames = intervalFrames * (totalTicks + 1)
            val out = ShortArray(totalFrames)
            val readBuf = ShortArray(512)
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
            val clickFrames = ArrayList<Int>()
            var nextClickAt = 0
            var tickIndex = 0
            var pos = 0

            while (recording.get() && pos < totalFrames) {
                if (tickIndex < totalTicks && pos >= nextClickAt) {
                    val isLeadIn = tickIndex < leadClicks
                    val tone = if (isLeadIn) android.media.ToneGenerator.TONE_PROP_ACK
                    else android.media.ToneGenerator.TONE_PROP_BEEP2
                    toneGen.startTone(tone, 60)
                    if (!isLeadIn) clickFrames.add(pos)
                    nextClickAt += intervalFrames
                    tickIndex++
                }
                val toRead = min(readBuf.size, totalFrames - pos)
                val n = rec.read(readBuf, 0, toRead)
                if (n > 0) {
                    for (i in 0 until n) out[pos + i] = readBuf[i]
                    pos += n
                }
            }
            rec.stop(); rec.release(); toneGen.release()
            recording.set(false)
            onResult(out.copyOf(pos), clickFrames)
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
