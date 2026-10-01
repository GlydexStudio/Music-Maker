package com.glydexstudio.musicmaker.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Synthesizes the drum kit once at start-up (no sample files to ship or license).
 * Each sound is a mono float buffer in [-1, 1] at [SAMPLE_RATE].
 */
object DrumSynth {
    const val SAMPLE_RATE = 44_100

    val kit: Map<String, FloatArray> by lazy {
        mapOf(
            "kick" to kick(),
            "snare" to snare(),
            "hihat" to hihat(),
            "clap" to clap(),
            "808" to bass808(),
        )
    }

    private fun buffer(seconds: Double) = FloatArray((SAMPLE_RATE * seconds).toInt())

    /** Sine with a fast exponential pitch drop and a short click transient. */
    private fun kick(): FloatArray {
        val out = buffer(0.45)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val freq = 45.0 + 110.0 * exp(-t * 32.0)
            phase += 2 * PI * freq / SAMPLE_RATE
            val body = sin(phase) * exp(-t * 7.5)
            val click = if (t < 0.004) (1.0 - t / 0.004) * 0.6 else 0.0
            out[i] = tanh((body + click) * 1.6).toFloat()
        }
        return normalize(out, 0.95f)
    }

    /** Band-limited noise burst plus a short tonal body. */
    private fun snare(): FloatArray {
        val out = buffer(0.28)
        val rnd = Random(7)
        var lp = 0.0
        var prev = 0.0
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val white = rnd.nextDouble(-1.0, 1.0)
            lp += 0.55 * (white - lp)          // tame the very top
            val hp = lp - prev                  // remove low rumble
            prev = lp
            val noise = hp * 2.2 * exp(-t * 22.0)
            phase += 2 * PI * (185.0 + 40.0 * exp(-t * 40.0)) / SAMPLE_RATE
            val tone = sin(phase) * 0.55 * exp(-t * 28.0)
            out[i] = (noise + tone).toFloat()
        }
        return normalize(out, 0.8f)
    }

    /** Short high-passed noise with a very fast decay. */
    private fun hihat(): FloatArray {
        val out = buffer(0.07)
        val rnd = Random(11)
        var prev = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val white = rnd.nextDouble(-1.0, 1.0)
            val hp = white - prev * 0.92
            prev = white
            out[i] = (hp * exp(-t * 70.0)).toFloat()
        }
        return normalize(out, 0.5f)
    }

    /** Three quick noise bursts followed by a short tail, like hands clapping. */
    private fun clap(): FloatArray {
        val out = buffer(0.22)
        val rnd = Random(23)
        var lp = 0.0
        var prev = 0.0
        val bursts = doubleArrayOf(0.0, 0.011, 0.022)
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val white = rnd.nextDouble(-1.0, 1.0)
            lp += 0.35 * (white - lp)
            val band = lp - prev
            prev = lp
            var env = 0.0
            for (b in bursts) if (t >= b) env = maxOf(env, exp(-(t - b) * 160.0))
            if (t >= 0.022) env = maxOf(env, 0.55 * exp(-(t - 0.022) * 18.0))
            out[i] = (band * env * 3.0).toFloat()
        }
        return normalize(out, 0.75f)
    }

    /** Long sub-bass sine (A1 = 55 Hz) with a pitch glide and soft saturation. */
    private fun bass808(): FloatArray {
        val out = buffer(1.1)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val freq = 55.0 * (1.0 + 0.9 * exp(-t * 45.0))
            phase += 2 * PI * freq / SAMPLE_RATE
            val env = exp(-t * 2.4) * (if (t < 0.003) t / 0.003 else 1.0)
            out[i] = tanh(sin(phase) * env * 2.2).toFloat()
        }
        // Short fade-out to avoid a click at the end of the buffer.
        val fade = SAMPLE_RATE / 50
        for (k in 0 until fade) out[out.size - 1 - k] *= k.toFloat() / fade
        return normalize(out, 0.9f)
    }

    private fun normalize(buf: FloatArray, peak: Float): FloatArray {
        var max = 0f
        for (v in buf) max = maxOf(max, abs(v))
        if (max > 0f) {
            val g = peak / max
            for (i in buf.indices) buf[i] *= g
        }
        return buf
    }
}
