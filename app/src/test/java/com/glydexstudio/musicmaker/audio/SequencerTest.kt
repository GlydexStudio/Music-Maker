package com.glydexstudio.musicmaker.audio

import com.glydexstudio.musicmaker.data.BeatPattern
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class SequencerTest {

    private fun pattern(bpm: Int, kick: List<Int>) = BeatPattern.from(
        JSONObject().put("bpm", bpm).put("steps", JSONObject().put("kick", JSONArray(kick)))
    )

    @Test
    fun firesSixteenStepsPerBarAtExactTempo() {
        val p = pattern(120, List(16) { if (it % 4 == 0) 1 else 0 })
        val seq = Sequencer(p).apply { sequencing = true }
        val fired = mutableListOf<Pair<Int, Int>>()
        var offset = 0
        seq.onStep = { step, frame -> fired += step to offset + frame }
        val bar = (Sequencer.stepSamples(120) * 16).toInt()
        val buf = FloatArray(256)
        while (offset < bar) {
            val n = minOf(256, bar - offset)
            seq.render(buf, n)
            offset += n
        }
        assertEquals((0 until 16).toList(), fired.map { it.first })
        // 120 BPM = 8 sixteenths per second = 5512.5 samples per step.
        assertTrue(abs(fired[8].second - 8 * 5512.5) <= 1.0)
    }

    @Test
    fun beatPatternValidatesInput() {
        val p = BeatPattern.from(JSONObject().put("bpm", 999).put("steps", JSONObject().put("snare", JSONArray(listOf(true, 0, 1)))))
        assertEquals(200, p.bpm)
        assertTrue(p.isActive("snare", 0))
        assertTrue(p.isActive("snare", 2))
        assertEquals(16, p.steps.getValue("snare").size)
    }

    @Test
    fun rendersAudibleKickAndValidWav() {
        val p = pattern(140, listOf(1))
        val samples = Sequencer.renderOffline(p, 1)
        assertTrue(samples.take(2000).maxOf { abs(it) } > 0.3f)
        val out = ByteArrayOutputStream()
        WavWriter.write(out, samples, DrumSynth.SAMPLE_RATE)
        val bytes = out.toByteArray()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(44 + samples.size * 2, bytes.size)
    }
}
