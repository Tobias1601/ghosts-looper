package com.diy.loopstation

import kotlin.concurrent.thread

/**
 * Simple toggleable click metronome. Beat 0 of every bar is accented (different tone)
 * so it's audibly distinct as "the 1". Exposes the exact nanoTime of the last downbeat
 * so recordings can be scheduled to start precisely on it.
 */
class Metronome(private val beatsPerBar: Int = 4) {
    @Volatile private var running = false
    private var thread: Thread? = null
    var bpm: Int = 100

    @Volatile var lastDownbeatNanos: Long = 0L
        private set

    fun start() {
        if (running) return
        running = true
        thread = thread(start = true) {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90)
            var beat = 0
            lastDownbeatNanos = System.nanoTime()
            while (running) {
                val tone = if (beat == 0) android.media.ToneGenerator.TONE_PROP_BEEP2
                else android.media.ToneGenerator.TONE_PROP_BEEP
                toneGen.startTone(tone, 40)
                if (beat == 0) lastDownbeatNanos = System.nanoTime()
                try {
                    Thread.sleep(60000L / bpm)
                } catch (e: InterruptedException) {
                    break
                }
                beat = (beat + 1) % beatsPerBar
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

    /** How long until the next downbeat (start of a bar), based on the current bpm. */
    fun nanosUntilNextDownbeat(): Long {
        if (!running) return 0L
        val barDurNanos = (60_000_000_000L / bpm) * beatsPerBar
        val elapsed = (System.nanoTime() - lastDownbeatNanos) % barDurNanos
        return if (elapsed <= 0L) 0L else barDurNanos - elapsed
    }
}
