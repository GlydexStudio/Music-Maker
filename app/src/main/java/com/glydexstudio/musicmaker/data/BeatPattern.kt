package com.glydexstudio.musicmaker.data

import com.glydexstudio.musicmaker.core.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable, validated beat pattern: 5 tracks x 16 steps.
 * This is the single structure shared by storage, the audio engine and WAV export.
 */
class BeatPattern(
    val bpm: Int,
    /** 0.0 = straight, 0.5 = heavy shuffle on off-beat 16ths. */
    val swing: Double,
    val steps: Map<String, BooleanArray>,
    val volumes: Map<String, Float>,
    val muted: Set<String>,
) {
    fun isActive(track: String, step: Int): Boolean =
        track !in muted && steps[track]?.getOrNull(step) == true

    fun hasNotes(): Boolean = steps.values.any { arr -> arr.any { it } }

    fun toJson(): JSONObject {
        val stepsJson = JSONObject()
        val volJson = JSONObject()
        TRACKS.forEach { t ->
            stepsJson.put(t, JSONArray(steps.getValue(t).map { if (it) 1 else 0 }))
            volJson.put(t, volumes.getValue(t).toDouble())
        }
        return JSONObject()
            .put("bpm", bpm)
            .put("swing", swing)
            .put("steps", stepsJson)
            .put("volumes", volJson)
            .put("muted", JSONArray(TRACKS.filter { it in muted }))
    }

    companion object {
        const val STEPS = 16
        val TRACKS = listOf("kick", "snare", "hihat", "clap", "808")

        /** Accepts any JSON shape from JS or disk and returns a fully valid pattern. */
        fun from(json: JSONObject?): BeatPattern {
            val src = json ?: JSONObject()
            val stepsJson = src.optJSONObject("steps") ?: JSONObject()
            val volJson = src.optJSONObject("volumes") ?: JSONObject()
            val mutedJson = src.optJSONArray("muted") ?: JSONArray()
            val steps = TRACKS.associateWith { t ->
                val arr = stepsJson.optJSONArray(t)
                BooleanArray(STEPS) { i ->
                    when (val v = arr?.opt(i)) {
                        is Boolean -> v
                        is Number -> v.toInt() != 0
                        else -> false
                    }
                }
            }
            val volumes = TRACKS.associateWith { t ->
                Json.double(volJson, t, 0.0, 1.0, 0.85).toFloat()
            }
            val muted = (0 until mutedJson.length()).map { mutedJson.optString(it) }
                .filter { it in TRACKS }.toSet()
            return BeatPattern(
                bpm = Json.int(src, "bpm", 60, 200, 140),
                swing = Json.double(src, "swing", 0.0, 0.5, 0.0),
                steps = steps,
                volumes = volumes,
                muted = muted,
            )
        }
    }
}
