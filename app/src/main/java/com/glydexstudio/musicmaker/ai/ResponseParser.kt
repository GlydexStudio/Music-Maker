package com.glydexstudio.musicmaker.ai

import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import com.glydexstudio.musicmaker.core.Json
import com.glydexstudio.musicmaker.data.SongRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns raw model text into validated JSON for the WebView.
 * Small models often wrap JSON in prose, add code fences, put raw newlines inside strings or
 * leave trailing commas, so extraction is tolerant - but every field is validated afterwards.
 * Nothing here ever throws anything other than [AppException].
 */
object ResponseParser {

    private val thinkBlock = Regex("(?s)<think>.*?</think>")
    private val fence = Regex("```(?:json|JSON)?")
    private val trailingComma = Regex(",\\s*([}\\]])")

    fun clean(raw: String): String {
        var t = raw.replace(thinkBlock, "")
        val openThink = t.indexOf("<think>")
        if (openThink >= 0) t = t.substring(0, openThink) // unterminated reasoning block
        return t.replace(fence, "").trim()
    }

    /** Finds the first balanced JSON object in [text] and parses it, repairing common defects. */
    fun extractObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val candidate = balancedSlice(text, start) ?: (text.substring(start) + "}")
        val repaired = escapeControlCharsInStrings(candidate).replace(trailingComma, "$1")
        return try {
            JSONObject(repaired)
        } catch (e: Exception) {
            null
        }
    }

    fun lyrics(text: String, punchlines: Boolean): JSONObject {
        val obj = extractObject(text)
        if (punchlines) {
            val lines = Json.stringList(obj?.opt("punchlines") ?: fallbackLines(text), 12, 300)
            if (lines.isEmpty()) invalid()
            return JSONObject().put("punchlines", JSONArray(lines))
        }
        val lyrics = when {
            obj != null -> obj.opt("lyrics")?.let { v ->
                if (v is JSONArray) (0 until v.length()).joinToString("\n") { v.optString(it) } else v.toString()
            } ?: ""
            looksLikeJson(text) -> ""
            else -> text // Plain-text lyrics are still real model output; accept them.
        }
        val normalized = normalizeLyrics(lyrics)
        if (normalized.isBlank()) invalid()
        return JSONObject()
            .put("title", obj?.let { Json.str(it, "title", SongRepository.MAX_TITLE) } ?: "")
            .put("lyrics", normalized)
    }

    fun songIdea(text: String): JSONObject {
        val o = extractObject(text) ?: invalid()
        val title = Json.str(o, "title", SongRepository.MAX_TITLE)
        val concept = Json.str(o, "concept", 1200)
        if (title.isBlank() || concept.isBlank()) invalid()
        val bpm = o.opt("bpm").let { v ->
            when (v) {
                is Number -> v.toInt()
                is String -> Regex("\\d{2,3}").find(v)?.value?.toInt()
                else -> null
            }
        }?.takeIf { it in 60..200 }
        return JSONObject()
            .put("title", title)
            .put("concept", concept)
            .put("story", Json.str(o, "story", 1500))
            .put("hook", Json.str(o, "hook", 400))
            .put("verseIdeas", JSONArray(Json.stringList(o.opt("verseIdeas"), 6, 300)))
            .put("mood", Json.str(o, "mood", 40))
            .put("genre", Json.str(o, "genre", 40))
            .put("bpm", bpm ?: JSONObject.NULL)
            .put("key", SongRepository.normalizeKey(Json.str(o, "key", 16)))
            .put("beatDirection", Json.str(o, "beatDirection", 600))
    }

    fun promotion(text: String): JSONObject {
        val o = extractObject(text) ?: invalid()
        val tags = Json.stringList(o.opt("hashtags"), 40, 60)
            .flatMap { it.split(Regex("\\s+")) }
            .map { "#" + it.trimStart('#').replace(Regex("[^\\p{L}\\p{N}_]"), "") }
            .filter { it.length > 1 }
            .distinctBy { it.lowercase() }
            .take(30)
        val body = Json.str(o, "text", 5000)
        if (body.isBlank() && tags.isEmpty()) invalid()
        return JSONObject().put("text", body).put("hashtags", JSONArray(tags))
    }

    private fun normalizeLyrics(raw: String): String = Json.clean(raw, SongRepository.MAX_LYRICS)
        .replace("\\n", "\n")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun fallbackLines(text: String): String =
        if (looksLikeJson(text)) "" else text.lines().filter { it.isNotBlank() }.joinToString("\n")

    private fun looksLikeJson(text: String) = text.trimStart().startsWith("{")

    private fun invalid(): Nothing =
        throw AppException(ErrorCode.INVALID_RESPONSE, "The AI returned an unreadable answer. Try again.")

    private fun balancedSlice(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Escapes raw newlines / tabs that models put inside JSON string values. */
    private fun escapeControlCharsInStrings(s: String): String {
        val out = StringBuilder(s.length + 16)
        var inString = false
        var escaped = false
        for (c in s) {
            if (inString) {
                when {
                    escaped -> { escaped = false; out.append(c) }
                    c == '\\' -> { escaped = true; out.append(c) }
                    c == '"' -> { inString = false; out.append(c) }
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> {}
                    c == '\t' -> out.append("\\t")
                    else -> out.append(c)
                }
            } else {
                if (c == '"') inString = true
                out.append(c)
            }
        }
        return out.toString()
    }
}
