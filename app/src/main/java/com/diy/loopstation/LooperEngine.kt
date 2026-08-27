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

/** One loop slot: holds the recorded audio and its own AudioTrack for gapless looping. */
class Track(val index: Int) {
    @Volatile var buffer: ShortArray = ShortArray(0)
    @Volatile var lastBuffer: ShortArray = ShortArray(0) // for Undo
    private var audioTrack: AudioTrack? = null
    var volume: Float = 1f
    var muted: Boolean = false
    var isEmpty: Boolean = true

    fun stopPlayback() {
        audioTrack?.let {
            try { it.stop() } catch (e: Exception) {}
            it.release()
        }
        audioTrack = null
    }

    /** (Re)starts playback from frame 0 - used to keep all tracks phase-aligned. */
    fun startPlayback() {
        if (buffer.isEmpty()) return
        stopPlayback()
        val bytes = buffer.size * 2
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
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
        if (AudioSettings.lowLatencyMode) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = builder.build()
        track.write(buffer, 0, buffer.size)
        track.setLoopPoints(0, buffer.size, -1)
        track.setVolume(if (muted) 0f else volume)
        track.play()
        audioTrack = track
    }

    fun updateVolume() {
        audioTrack?.setVolume(if (muted) 0f else volume)
    }
}

/**
 * Core looper. The first track recorded becomes the "master candidate": its raw audio
 * is handed back to the UI for manual start/end trimming before it's committed as the
 * master loop length. Every following recording is aligned to the next loop-start
 * boundary and captured for exactly the master length, so all tracks stay in phase.
 */
class LooperEngine {
    val tracks = List(4) { Track(it) }
    @Volatile var masterLoopLengthFrames = 0
    private var sessionStartNanos = 0L
    private val recording = AtomicBoolean(false)
    private val minBufSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )

    fun isMasterSet() = masterLoopLengthFrames > 0

    private fun loopDurationNanos(): Long =
        (masterLoopLengthFrames.toLong() * 1_000_000_000L) / SAMPLE_RATE

    /** Restarts every non-empty track's playback at the same instant to re-align phase. */
    private fun resyncAll() {
        sessionStartNanos = System.nanoTime()
        tracks.forEach { if (!it.isEmpty) it.startPlayback() }
    }

    private fun shiftForLatency(raw: ShortArray, offsetFrames: Int): ShortArray {
        if (offsetFrames == 0) return raw
        return if (offsetFrames > 0) {
            if (offsetFrames >= raw.size) ShortArray(0) else raw.copyOfRange(offsetFrames, raw.size)
        } else {
            ShortArray(-offsetFrames) + raw
        }
    }

    private fun fitToLength(data: ShortArray, target: Int): ShortArray {
        if (data.size == target) return data
        if (data.size > target) return data.copyOfRange(0, target)
        return data + ShortArray(target - data.size)
    }

    /**
     * onFinished(isMasterCandidate) - if true, the recording is NOT yet committed:
     * call finalizeMasterTrim() or cancelMasterTrim() next. If false, the track was
     * synced/overdubbed automatically and is already playing.
     */
    fun startRecording(track: Track, onFinished: (isMasterCandidate: Boolean) -> Unit) {
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
            val readBuf = ShortArray(1024)
            val wasFirstTrack = !isMasterSet()

            // If a master loop already exists, wait for the next loop-start boundary
            // before we start actually capturing, so the new track lines up in time.
            if (isMasterSet()) {
                val durNanos = loopDurationNanos()
                val elapsed = (System.nanoTime() - sessionStartNanos) % durNanos
                val waitNanos = durNanos - elapsed
                val deadline = System.nanoTime() + waitNanos
                while (System.nanoTime() < deadline && recording.get()) {
                    rec.read(readBuf, 0, readBuf.size) // drain mic, discard
                }
            }

            // Capture a little extra so latency-shifting never runs us short.
            val offsetFrames = AudioSettings.offsetFrames()
            val extraForLatency = max(0, offsetFrames) + max(0, -offsetFrames)
            val targetFrames = if (isMasterSet()) masterLoopLengthFrames else Int.MAX_VALUE
            val captureTarget = if (targetFrames == Int.MAX_VALUE) Int.MAX_VALUE else targetFrames + extraForLatency

            val chunks = ArrayList<ShortArray>()
            var totalFrames = 0
            while (recording.get() && totalFrames < captureTarget) {
                val n = rec.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    val remaining = if (captureTarget == Int.MAX_VALUE) n else captureTarget - totalFrames
                    val toCopy = if (n > remaining) remaining else n
                    chunks.add(readBuf.copyOf(toCopy))
                    totalFrames += toCopy
                }
                if (captureTarget != Int.MAX_VALUE && totalFrames >= captureTarget) {
                    recording.set(false)
                }
            }
            rec.stop()
            rec.release()

            var rawAudio = ShortArray(totalFrames)
            var pos = 0
            for (c in chunks) { c.copyInto(rawAudio, pos); pos += c.size }

            rawAudio = shiftForLatency(rawAudio, offsetFrames)
            if (isMasterSet()) rawAudio = fitToLength(rawAudio, masterLoopLengthFrames)

            if (wasFirstTrack) {
                // Hand raw audio to the UI for manual trim - do NOT commit yet.
                track.buffer = rawAudio
                onFinished(true)
            } else {
                track.lastBuffer = track.buffer
                track.buffer = if (track.isEmpty) rawAudio else mixAudio(track.buffer, rawAudio)
                track.isEmpty = false
                resyncAll()
                onFinished(false)
            }
        }
    }

    fun stopRecording() {
        recording.set(false)
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
            val totalFrames = intervalFrames * (totalTicks + 1) // one extra interval as tail
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
                    // quieter/lower tone for the count-in, accented tone for measured clicks
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
            rec.stop()
            rec.release()
            toneGen.release()
            recording.set(false)
            onResult(out.copyOf(pos), clickFrames)
        }
    }

    /** Call after the user drags trim handles on the master-candidate track's waveform. */
    fun finalizeMasterTrim(track: Track, startFrame: Int, endFrame: Int) {
        val s = startFrame.coerceIn(0, track.buffer.size)
        val e = endFrame.coerceIn(s + 1, track.buffer.size)
        track.lastBuffer = ShortArray(0)
        track.buffer = track.buffer.copyOfRange(s, e)
        track.isEmpty = false
        masterLoopLengthFrames = track.buffer.size
        resyncAll()
    }

    fun cancelMasterTrim(track: Track) {
        track.buffer = ShortArray(0)
        track.isEmpty = true
    }

    private fun mixAudio(a: ShortArray, b: ShortArray): ShortArray {
        val len = maxOf(a.size, b.size)
        val out = ShortArray(len)
        for (i in 0 until len) {
            val av = if (i < a.size) a[i].toInt() else 0
            val bv = if (i < b.size) b[i].toInt() else 0
            var sum = av + bv
            if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE.toInt()
            if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE.toInt()
            out[i] = sum.toShort()
        }
        return out
    }

    fun undo(track: Track) {
        track.buffer = track.lastBuffer
        track.isEmpty = track.buffer.isEmpty()
        if (track.isEmpty) track.stopPlayback() else resyncAll()
        if (tracks.all { it.isEmpty }) masterLoopLengthFrames = 0
    }

    fun clear(track: Track) {
        track.lastBuffer = track.buffer
        track.buffer = ShortArray(0)
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

/** Simple toggleable click metronome, independent of the loop tracks. */
class Metronome {
    @Volatile private var running = false
    private var thread: Thread? = null
    var bpm: Int = 100

    fun start() {
        if (running) return
        running = true
        thread = thread(start = true) {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
            while (running) {
                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 40)
                try { Thread.sleep(60000L / bpm) } catch (e: InterruptedException) { break }
            }
            toneGen.release()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    val isRunning get() = running
}
