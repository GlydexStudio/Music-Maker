package com.glydexstudio.musicmaker.ai

import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import com.glydexstudio.musicmaker.core.Json
import org.json.JSONObject

/** A ready-to-run prompt: system + user text and a generation budget. */
data class Prompt(val system: String, val user: String, val maxTokens: Int)

/**
 * Builds every prompt used by the app. Each operation asks for one JSON object with a fixed
 * schema, always in the user-selected language. Qwen3's "thinking" mode is switched off with
 * the official /no_think soft switch to keep responses short and parseable.
 */
class PromptBuilder(private val contextSize: Int, private val lengthSetting: String) {

    // ---------------------------------------------------------------- lyrics

    fun lyrics(req: JSONObject): Prompt {
        val action = Json.oneOf(req, "action", LYRIC_ACTIONS, "generate")
        val mode = Json.oneOf(req, "mode", LYRIC_MODES, "full")
        val lang = language(req)
        val brief = brief(req)
        val text = Json.str(req, "text", 12_000)

        val needsText = action in setOf("rewrite", "continue", "expand", "shorten", "rhymes")
        if (needsText && text.isBlank()) {
            throw AppException(ErrorCode.BAD_REQUEST, "Write or generate some lyrics first.")
        }

        val budget = when (action) {
            "punchlines" -> 320
            "generate", "regenerate" -> scaled(MODE_TOKENS.getValue(mode))
            "continue" -> scaled(MODE_TOKENS.getValue(if (mode == "full") "verse" else mode) + 80)
            else -> {
                val factor = when (action) { "expand" -> 1.7; "shorten" -> 0.7; else -> 1.15 }
                (estimateTokens(text) * factor + 80).toInt()
            }
        }.coerceIn(96, 1600)

        val task = when (action) {
            "generate" -> "Write ${modeDescription(mode)}."
            "regenerate" -> "Write ${modeDescription(mode)}. Make it clearly different from any previous attempt: new angle, new images, new rhyme sounds."
            "rewrite" -> "Rewrite the lyrics below with fresh wording and imagery. Keep the meaning, the section labels, the structure and roughly the same length."
            "continue" -> "Continue the song below with ${if (mode == "full") "the next section that naturally follows" else modeDescription(mode)}. Return ONLY the new lines, never repeat the existing lyrics."
            "expand" -> "Expand the lyrics below to about one and a half times the length: add lines, detail and imagery inside each section. Keep the labels."
            "shorten" -> "Shorten the lyrics below to about half the length. Keep the strongest lines, the hook and the section labels."
            "rhymes" -> "Improve the rhymes in the lyrics below: stronger end rhymes, internal rhymes and multisyllabic rhymes. Keep the meaning, structure and labels."
            else -> "Write 6 original, quotable punchlines (standout one-liners) that fit this song."
        }

        val schema = if (action == "punchlines") {
            """{"punchlines": ["<line 1>", "<line 2>", "..."]}"""
        } else {
            """{"title": "<short song title>", "lyrics": "<lyrics with \n between lines>"}"""
        }

        val user = buildString {
            appendLine("TASK: $task")
            appendLine()
            appendLine("SONG BRIEF:")
            append(brief)
            appendLine()
            appendLine("RULES:")
            appendLine("- Write every lyric line in $lang.")
            appendLine("- Label sections in square brackets in English, e.g. [Verse 1], [Chorus], [Hook], [Bridge].")
            appendLine("- One lyric line per line. No numbering, no explanations, no chords.")
            appendLine("- ${contentRule(req)}")
            appendLine("- Fit the rhythm of ${Json.int(req, "bpm", 60, 200, 120)} BPM: ${tempoFeel(Json.int(req, "bpm", 60, 200, 120))}.")
            appendLine("- Be original. Never reproduce existing copyrighted lyrics.")
            if (needsText) {
                appendLine()
                appendLine("LYRICS:")
                appendLine("<<<")
                appendLine(fitText(text, budget))
                appendLine(">>>")
            }
            appendLine()
            appendLine("Respond with exactly this JSON shape (keys in English):")
            appendLine(schema)
            append(NO_THINK)
        }
        return Prompt(system(lang), user, budget)
    }

    // ---------------------------------------------------------------- song idea

    fun songIdea(req: JSONObject): Prompt {
        val lang = language(req)
        val spark = Json.str(req, "spark", 1000)
        val bpm = req.optInt("bpm", 0)
        val user = buildString {
            appendLine("TASK: Invent one original, specific and commercially strong song concept.")
            appendLine()
            if (spark.isNotBlank()) appendLine("ARTIST'S STARTING POINT: $spark")
            appendLine("Genre: ${Json.str(req, "genre", 40).ifBlank { "choose the best fit" }}")
            appendLine("Mood: ${Json.str(req, "mood", 40).ifBlank { "choose the best fit" }}")
            if (bpm in 60..200) appendLine("Tempo: $bpm BPM")
            appendLine()
            appendLine("RULES:")
            appendLine("- Write all text values in $lang. Keep JSON keys in English.")
            appendLine("- bpm is an integer between 60 and 200. key is a musical key like \"Am\", \"C\", \"F#m\".")
            appendLine("- verseIdeas has 2 to 4 short items.")
            appendLine("- beatDirection describes drums, bass, instruments and energy for a producer.")
            appendLine()
            appendLine("Respond with exactly this JSON shape:")
            appendLine("""{"title": "...", "concept": "...", "story": "...", "hook": "...", "verseIdeas": ["...", "..."], "mood": "...", "genre": "...", "bpm": 120, "key": "Am", "beatDirection": "..."}""")
            append(NO_THINK)
        }
        return Prompt(system(lang), user, scaled(560).coerceAtMost(900))
    }

    // ---------------------------------------------------------------- promotion

    fun promotion(req: JSONObject): Prompt {
        val lang = language(req)
        val platform = Json.oneOf(req, "platform", PLATFORMS.keys, "general")
        val type = Json.oneOf(req, "type", PROMO_TYPES.keys, "caption")
        val song = req.optJSONObject("song") ?: JSONObject()
        val title = Json.str(song, "title", 120)
        if (title.isBlank() && Json.str(song, "description", 600).isBlank()) {
            throw AppException(ErrorCode.BAD_REQUEST, "Choose a song or describe what you are promoting.")
        }
        val lyricsExcerpt = Json.str(song, "lyrics", 900)
        val budget = when (type) {
            "hashtags" -> 160
            "caption", "teaser" -> 260
            "youtube_description", "artist_bio" -> 640
            else -> 420
        }
        val user = buildString {
            appendLine("TASK: ${PROMO_TYPES.getValue(type)}")
            appendLine("PLATFORM: ${PLATFORMS.getValue(platform)}")
            appendLine()
            appendLine("RELEASE INFO:")
            if (title.isNotBlank()) appendLine("- Song title: $title")
            Json.str(song, "artist", 80).takeIf { it.isNotBlank() }?.let { appendLine("- Artist: $it") }
            Json.str(song, "genre", 40).takeIf { it.isNotBlank() }?.let { appendLine("- Genre: $it") }
            Json.str(song, "mood", 40).takeIf { it.isNotBlank() }?.let { appendLine("- Mood: $it") }
            Json.str(song, "description", 600).takeIf { it.isNotBlank() }?.let { appendLine("- About: $it") }
            Json.str(song, "concept", 600).takeIf { it.isNotBlank() }?.let { appendLine("- Concept: $it") }
            if (lyricsExcerpt.isNotBlank()) appendLine("- Lyrics excerpt:\n$lyricsExcerpt")
            Json.str(req, "tone", 40).takeIf { it.isNotBlank() }?.let { appendLine("- Tone: $it") }
            appendLine()
            appendLine("RULES:")
            appendLine("- Write the text in $lang. Keep JSON keys in English.")
            appendLine("- Do not invent streaming numbers, awards, collaborators or release dates that were not given.")
            appendLine("- hashtags: 5 to 15 relevant tags, each starting with #, no spaces inside a tag.")
            if (type == "hashtags") appendLine("- text: one short line introducing the tags.")
            appendLine()
            appendLine("Respond with exactly this JSON shape:")
            appendLine("""{"text": "...", "hashtags": ["#tag1", "#tag2"]}""")
            append(NO_THINK)
        }
        return Prompt(system(lang), user, scaled(budget))
    }

    /** Appended on the retry after a malformed response. */
    fun strictRetry(p: Prompt): Prompt = p.copy(
        user = p.user.removeSuffix(NO_THINK) +
            "\nIMPORTANT: Your previous answer was not valid JSON. Output ONLY the JSON object, starting with { and ending with }." +
            NO_THINK,
    )

    // ---------------------------------------------------------------- helpers

    private fun system(lang: String) =
        "You are the songwriting engine inside Music Maker, a professional music creation studio. " +
            "You write original, vivid, singable material with strong hooks and natural rhythm. " +
            "Write all creative text in $lang. " +
            "Always answer with a single valid JSON object and nothing else: no markdown, no code fences, no comments."

    private fun brief(req: JSONObject) = buildString {
        fun line(label: String, key: String, max: Int = 200) {
            val v = Json.str(req, key, max)
            if (v.isNotBlank()) appendLine("- $label: $v")
        }
        line("Working title", "title", 120)
        line("Genre", "genre", 40)
        line("Mood", "mood", 40)
        line("Topic", "topic", 600)
        line("Style notes", "style", 300)
    }.ifBlank { "- Free choice: surprise me with something emotionally specific.\n" }

    private fun contentRule(req: JSONObject) = if (Json.bool(req, "explicit", false)) {
        "Explicit language is allowed where it fits the style, but no slurs or hate toward any group."
    } else {
        "Keep it clean: no profanity, slurs or sexual content."
    }

    private fun tempoFeel(bpm: Int) = when {
        bpm < 80 -> "slow, spacious phrasing"
        bpm < 100 -> "laid-back, relaxed flow"
        bpm < 125 -> "mid-tempo, steady groove"
        bpm < 150 -> "energetic, punchy phrasing (half-time feel works too)"
        else -> "fast, driving rhythm"
    }

    private fun modeDescription(mode: String) = when (mode) {
        "verse" -> "one verse of 8 to 16 lines, labelled [Verse]"
        "chorus" -> "a chorus of 4 to 8 lines built around one memorable, repeatable line, labelled [Chorus]"
        "hook" -> "a short, catchy hook of 2 to 4 repetitive lines, labelled [Hook]"
        "bridge" -> "a bridge of 4 to 6 lines that shifts perspective or energy, labelled [Bridge]"
        else -> if (lengthSetting == "short") {
            "complete song lyrics structured as [Verse 1], [Chorus], [Verse 2], [Chorus]"
        } else {
            "complete song lyrics structured as [Verse 1], [Chorus], [Verse 2], [Chorus], [Bridge], [Chorus]"
        }
    }

    private fun language(req: JSONObject) = LANGUAGE_NAMES[Json.oneOf(req, "language", LANGUAGE_NAMES.keys, "en")]!!

    private fun scaled(tokens: Int) = (tokens * when (lengthSetting) { "short" -> 0.7; "long" -> 1.4; else -> 1.0 }).toInt()

    /** Rough token estimate; Romanian/Hungarian tokenize denser than English, so be conservative. */
    private fun estimateTokens(text: String) = text.length / 3

    /** Trims long input so prompt + answer fit into the context window. */
    private fun fitText(text: String, answerBudget: Int): String {
        val maxInputTokens = (contextSize - answerBudget - 600).coerceAtLeast(256)
        val maxChars = maxInputTokens * 3
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    companion object {
        private const val NO_THINK = "\n/no_think"
        val LYRIC_ACTIONS = setOf("generate", "regenerate", "rewrite", "continue", "expand", "shorten", "rhymes", "punchlines")
        val LYRIC_MODES = setOf("verse", "chorus", "hook", "bridge", "full")
        private val MODE_TOKENS = mapOf("verse" to 340, "chorus" to 220, "hook" to 140, "bridge" to 200, "full" to 900)
        val LANGUAGE_NAMES = mapOf("en" to "English", "ro" to "Romanian (limba română)", "hu" to "Hungarian (magyar nyelv)")
        val PLATFORMS = mapOf(
            "tiktok" to "TikTok - short, punchy, scroll-stopping, under 150 characters for captions",
            "instagram" to "Instagram - visual, personal, line breaks allowed, emojis sparingly",
            "youtube" to "YouTube - searchable, informative, strong first line",
            "spotify" to "Spotify - concise, artist-voice, suited to a profile or Canvas",
            "general" to "General social media - works across platforms",
        )
        val PROMO_TYPES = mapOf(
            "caption" to "Write a post caption for the song.",
            "hashtags" to "Create a set of hashtags for the song.",
            "video_idea" to "Describe one short-form video idea (hook in the first 2 seconds, shot list, on-screen text, which part of the song to use).",
            "teaser" to "Write a teaser post that builds anticipation without revealing everything.",
            "announcement" to "Write a release announcement post.",
            "youtube_description" to "Write a YouTube video description: opening lines, short story of the song, credits placeholder lines and a call to action.",
            "artist_bio" to "Write a short artist bio (3 to 5 sentences) based on this release.",
            "song_description" to "Write a compelling description of the song for playlists and press.",
        )
    }
}
