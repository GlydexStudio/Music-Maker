package com.glydexstudio.musicmaker.data

import android.content.Context
import com.glydexstudio.musicmaker.core.Json
import org.json.JSONObject

/**
 * Persists user settings in SharedPreferences as one validated JSON document.
 * Every write goes through [normalize], so the stored document is always well-formed.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var cached: JSONObject? = null

    @Synchronized
    fun get(): JSONObject {
        cached?.let { return JSONObject(it.toString()) }
        val stored = prefs.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val normalized = normalize(stored ?: JSONObject())
        cached = normalized
        return JSONObject(normalized.toString())
    }

    /** Merges [patch] into the current settings, validates and saves. Returns the result. */
    @Synchronized
    fun save(patch: JSONObject): JSONObject {
        val merged = get()
        patch.keys().forEach { key ->
            if (key == "ai" && patch.optJSONObject("ai") != null) {
                val ai = merged.optJSONObject("ai") ?: JSONObject()
                val aiPatch = patch.getJSONObject("ai")
                aiPatch.keys().forEach { ai.put(it, aiPatch.get(it)) }
                merged.put("ai", ai)
            } else {
                merged.put(key, patch.get(key))
            }
        }
        val normalized = normalize(merged)
        prefs.edit().putString(KEY, normalized.toString()).apply()
        cached = normalized
        return JSONObject(normalized.toString())
    }

    @Synchronized
    fun reset() {
        prefs.edit().clear().apply()
        cached = null
    }

    fun ai(): AiSettings {
        val ai = get().getJSONObject("ai")
        return AiSettings(
            creativity = ai.getDouble("creativity").toFloat(),
            length = ai.getString("length"),
            threads = ai.getInt("threads"),
            contextSize = ai.getInt("contextSize"),
            unloadInBackground = ai.getBoolean("unloadInBackground"),
        )
    }

    private fun normalize(s: JSONObject): JSONObject {
        val ai = s.optJSONObject("ai") ?: JSONObject()
        val accent = Json.str(s, "accent", 7, DEFAULT_ACCENT)
        return JSONObject()
            .put("theme", Json.oneOf(s, "theme", THEMES, "dark"))
            .put("accent", if (HEX.matches(accent)) accent.uppercase() else DEFAULT_ACCENT)
            .put("language", Json.oneOf(s, "language", LANGUAGES, "en"))
            .put("genre", Json.str(s, "genre", 40, "Hip-Hop").ifBlank { "Hip-Hop" })
            .put("bpm", Json.int(s, "bpm", 60, 200, 140))
            .put("artistName", Json.str(s, "artistName", 60))
            .put(
                "ai", JSONObject()
                    .put("creativity", Json.double(ai, "creativity", 0.1, 1.5, 0.8))
                    .put("length", Json.oneOf(ai, "length", LENGTHS, "medium"))
                    .put("threads", Json.int(ai, "threads", 0, 8, 0))
                    .put("contextSize", Json.int(ai, "contextSize", 2048, 8192, 4096).let { snapContext(it) })
                    .put("unloadInBackground", Json.bool(ai, "unloadInBackground", true))
            )
    }

    private fun snapContext(v: Int): Int = CONTEXT_SIZES.minBy { kotlin.math.abs(it - v) }

    data class AiSettings(
        val creativity: Float,
        val length: String,
        val threads: Int,
        val contextSize: Int,
        val unloadInBackground: Boolean,
    )

    companion object {
        private const val PREFS = "music_maker_settings"
        private const val KEY = "settings_json"
        private const val DEFAULT_ACCENT = "#A78BFA"
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
        val THEMES = setOf("dark", "light", "system")
        val LANGUAGES = setOf("en", "ro", "hu")
        val LENGTHS = setOf("short", "medium", "long")
        val CONTEXT_SIZES = listOf(2048, 4096, 8192)
    }
}
