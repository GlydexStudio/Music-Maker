package com.glydexstudio.musicmaker.audio

import com.glydexstudio.musicmaker.data.BeatPattern
import kotlin.math.tanh

/**
 * Sample-accurate 16-step sequencer + voice mixer. Shared by real-time playback
 * ([BeatEngine]) and offline WAV rendering, so what you hear is exactly what you export.
 * Not thread-safe by itself: only the owning audio thread calls [render].
 */
class Sequencer(@Volatile var pattern: BeatPattern) {

    private class Voice(val sample: FloatArray, val gain: Float, val track: String) {
        var pos = 0
    }

    private val voices = ArrayList<Voice>(MAX_VOICES)
    private var samplesToNextStep = 0.0

    /** Index of the step that will fire next. */
    var nextStep = 0
        private set

    /** Called with the step index every time a step fires (on the rendering thread). */
    var onStep: ((step: Int, frameOffset: Int) -> Unit)? = null

    var sequencing = false

    val hasActiveVoices: Boolean get() = voices.isNotEmpty()

    fun reset(step: Int = 0) {
        nextStep = step.mod(BeatPattern.STEPS)
        samplesToNextStep = 0.0
    }

    fun clearVoices() = voices.clear()

    fun trigger(track: String, gain: Float) {
        val sample = DrumSynth.kit[track] ?: return
        if (track == "808") voices.removeAll { it.track == "808" } // 808 is monophonic
        if (track == "hihat") voices.removeAll { it.track == "hihat" && it.pos > 400 }
        if (voices.size >= MAX_VOICES) voices.removeAt(0)
        voices.add(Voice(sample, gain, track))
    }

    /** Mixes [frames] mono samples into [out] (overwrites). */
    fun render(out: FloatArray, frames: Int) {
        var frame = 0
        while (frame < frames) {
            if (sequencing && samplesToNextStep <= 0.0) {
                fireStep(frame)
            }
            val chunkEnd = if (sequencing) {
                minOf(frames, frame + maxOf(1, kotlin.math.ceil(samplesToNextStep).toInt()))
            } else frames
            for (f in frame until chunkEnd) {
                var mix = 0f
                var i = 0
                while (i < voices.size) {
                    val v = voices[i]
                    mix += v.sample[v.pos] * v.gain
                    v.pos++
                    if (v.pos >= v.sample.size) voices.removeAt(i) else i++
                }
                out[f] = tanh(mix * MASTER_GAIN)
            }
            if (sequencing) samplesToNextStep -= (chunkEnd - frame)
            frame = chunkEnd
        }
    }

    private fun fireStep(frameOffset: Int) {
        val p = pattern
        val step = nextStep
        for (track in BeatPattern.TRACKS) {
            if (p.isActive(track, step)) trigger(track, p.volumes.getValue(track))
        }
        onStep?.invoke(step, frameOffset)
        val base = stepSamples(p.bpm)
        // Swing delays the off-beat 16ths: even steps get longer, odd steps shorter.
        val length = if (step % 2 == 0) base * (1 + p.swing * 0.66) else base * (1 - p.swing * 0.66)
        samplesToNextStep += length
        nextStep = (step + 1) % BeatPattern.STEPS
    }

    companion object {
        /** Offline render of [bars] bars plus a 1 s tail, used for WAV export and sharing. */
        fun renderOffline(pattern: BeatPattern, bars: Int): FloatArray {
            val stepLen = stepSamples(pattern.bpm)
            val sequencedFrames = (stepLen * BeatPattern.STEPS * bars).toInt()
            val total = sequencedFrames + DrumSynth.SAMPLE_RATE
            val out = FloatArray(total)
            val offline = Sequencer(pattern).apply { sequencing = true }
            val chunk = FloatArray(1024)
            var written = 0
            while (written < total) {
                if (written >= sequencedFrames) offline.sequencing = false
                val limit = if (offline.sequencing) sequencedFrames - written else total - written
                val n = minOf(chunk.size, limit)
                offline.render(chunk, n)
                System.arraycopy(chunk, 0, out, written, n)
                written += n
            }
            return out
        }

        private const val MAX_VOICES = 24
        private const val MASTER_GAIN = 0.9f
        fun stepSamples(bpm: Int): Double = DrumSynth.SAMPLE_RATE * 60.0 / bpm / 4.0
    }
}
