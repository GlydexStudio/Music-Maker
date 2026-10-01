package com.glydexstudio.musicmaker.data

import android.content.Context
import android.util.AtomicFile
import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import com.glydexstudio.musicmaker.core.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Native source of truth for song projects.
 * One JSON document per project in filesDir/songs/<id>.json, written atomically.
 * All methods are blocking; call them off the main thread.
 */
class SongRepository(context: Context, private val covers: CoverStore) {

    private val dir = File(context.filesDir, "songs").apply { mkdirs() }
    private val lock = Any()

    /** Lightweight summaries for the library, newest first. */
    fun list(): JSONArray = synchronized(lock) {
        val songs = dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { readFile(it) }
            ?.sortedByDescending { it.optLong("modifiedAt") }
            ?: emptyList()
        JSONArray(songs.map { summary(it) })
    }

    fun get(id: String): JSONObject = synchronized(lock) {
        readFile(fileFor(id)) ?: throw AppException(ErrorCode.NOT_FOUND, "This song no longer exists.")
    }

    /** Creates or updates a project. Unknown fields are dropped, known fields validated. */
    fun save(input: JSONObject): JSONObject = synchronized(lock) {
        val requestedId = Json.str(input, "id", 64)
        val existing = if (requestedId.isNotEmpty()) readFile(fileFor(requestedId)) else null
        val now = System.currentTimeMillis()
        val id = existing?.getString("id") ?: newId()
        val song = normalize(input, id, existing, now)
        write(song)
        song
    }

    fun delete(id: String) = synchronized(lock) {
        val f = fileFor(id)
        if (!f.exists()) throw AppException(ErrorCode.NOT_FOUND, "This song no longer exists.")
        AtomicFile(f).delete()
        covers.delete(id)
    }

    fun duplicate(id: String): JSONObject = synchronized(lock) {
        val src = get(id)
        val now = System.currentTimeMillis()
        val copy = JSONObject(src.toString())
        val newId = newId()
        copy.put("id", newId)
        copy.put("title", (src.optString("title") + " (copy)").take(MAX_TITLE))
        copy.put("favorite", false)
        copy.put("createdAt", now)
        copy.put("modifiedAt", now)
        val cover = copy.optJSONObject("cover")
        if (cover?.optString("type") == "image") {
            if (covers.copy(id, newId)) cover.put("version", now) else copy.put("cover", gradientCover(newId))
        }
        write(copy)
        copy
    }

    fun rename(id: String, title: String): JSONObject = update(id) {
        val clean = Json.clean(title, MAX_TITLE)
        if (clean.isEmpty()) throw AppException(ErrorCode.BAD_REQUEST, "Title cannot be empty.")
        it.put("title", clean)
    }

    fun setFavorite(id: String, favorite: Boolean): JSONObject = update(id) { it.put("favorite", favorite) }

    fun saveBeat(id: String, beat: JSONObject): JSONObject = update(id) { song ->
        val pattern = BeatPattern.from(beat)
        song.put("beat", pattern.toJson())
        song.put("bpm", pattern.bpm)
    }

    fun loadBeat(id: String): JSONObject? = get(id).optJSONObject("beat")

    fun setCover(id: String, cover: JSONObject): JSONObject = update(id) { it.put("cover", cover) }

    fun addPromotion(id: String, item: JSONObject): JSONObject = update(id) { song ->
        val list = song.optJSONArray("promotion") ?: JSONArray()
        val clean = normalizePromo(item) ?: throw AppException(ErrorCode.BAD_REQUEST, "Promotion text is empty.")
        val next = JSONArray().put(clean)
        for (i in 0 until list.length()) if (next.length() < MAX_PROMOS) next.put(list.get(i))
        song.put("promotion", next)
    }

    fun deleteAll() = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
        covers.deleteAll()
    }

    fun stats(): JSONObject = synchronized(lock) {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        JSONObject()
            .put("projects", files.size)
            .put("projectBytes", files.sumOf { it.length() })
            .put("coverBytes", covers.totalBytes())
    }

    private fun update(id: String, block: (JSONObject) -> Unit): JSONObject = synchronized(lock) {
        val song = get(id)
        block(song)
        song.put("modifiedAt", System.currentTimeMillis())
        write(song)
        song
    }

    private fun normalize(input: JSONObject, id: String, existing: JSONObject?, now: Long): JSONObject {
        val base = existing ?: JSONObject()
        fun pickStr(key: String, max: Int, def: String = ""): String =
            if (input.has(key)) Json.str(input, key, max, def) else base.optString(key, def)

        val title = pickStr("title", MAX_TITLE).ifBlank { "Untitled song" }
        val promotion = JSONArray()
        (if (input.has("promotion")) input.optJSONArray("promotion") else base.optJSONArray("promotion"))?.let { arr ->
            for (i in 0 until minOf(arr.length(), MAX_PROMOS)) {
                arr.optJSONObject(i)?.let { normalizePromo(it) }?.let { promotion.put(it) }
            }
        }
        val beatSrc = if (input.has("beat")) input.optJSONObject("beat") else base.optJSONObject("beat")
        val ideaSrc = if (input.has("idea")) input.optJSONObject("idea") else base.optJSONObject("idea")
        val coverSrc = (if (input.has("cover")) input.optJSONObject("cover") else base.optJSONObject("cover"))

        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("artist", pickStr("artist", 80))
            .put("cover", normalizeCover(coverSrc, id))
            .put("genre", pickStr("genre", 40))
            .put("mood", pickStr("mood", 40))
            .put("language", (if (input.has("language")) Json.oneOf(input, "language", SettingsRepository.LANGUAGES, "en") else base.optString("language", "en")))
            .put("bpm", if (input.has("bpm")) Json.int(input, "bpm", 60, 200, 120) else base.optInt("bpm", 120))
            .put("key", normalizeKey(pickStr("key", 12)))
            .put("lyrics", pickStr("lyrics", MAX_LYRICS))
            .put("beat", beatSrc?.let { BeatPattern.from(it).toJson() } ?: JSONObject.NULL)
            .put("idea", ideaSrc?.let { normalizeIdea(it) } ?: JSONObject.NULL)
            .put("promotion", promotion)
            .put("status", if (input.has("status")) Json.oneOf(input, "status", STATUSES, "draft") else base.optString("status", "draft"))
            .put("favorite", if (input.has("favorite")) Json.bool(input, "favorite", false) else base.optBoolean("favorite", false))
            .put("createdAt", base.optLong("createdAt", now).takeIf { it > 0 } ?: now)
            .put("modifiedAt", now)
    }

    private fun normalizeCover(src: JSONObject?, id: String): JSONObject {
        if (src != null && src.optString("type") == "image" && covers.fileFor(id).exists()) {
            return JSONObject().put("type", "image").put("version", src.optLong("version", System.currentTimeMillis()))
        }
        if (src != null && src.optString("type") == "gradient") {
            return JSONObject().put("type", "gradient").put("seed", src.optInt("seed", id.hashCode()))
        }
        return gradientCover(id)
    }

    private fun gradientCover(id: String) = JSONObject().put("type", "gradient").put("seed", id.hashCode() and 0x7fffffff)

    private fun normalizeIdea(o: JSONObject): JSONObject = JSONObject()
        .put("title", Json.str(o, "title", MAX_TITLE))
        .put("concept", Json.str(o, "concept", 1200))
        .put("story", Json.str(o, "story", 1500))
        .put("hook", Json.str(o, "hook", 400))
        .put("verseIdeas", JSONArray(Json.stringList(o.opt("verseIdeas"), 8, 300)))
        .put("beatDirection", Json.str(o, "beatDirection", 600))

    private fun normalizePromo(o: JSONObject): JSONObject? {
        val text = Json.str(o, "text", 5000)
        val tags = Json.stringList(o.opt("hashtags"), 30, 60)
        if (text.isBlank() && tags.isEmpty()) return null
        return JSONObject()
            .put("id", Json.str(o, "id", 64).ifBlank { newId() })
            .put("platform", Json.str(o, "platform", 30))
            .put("type", Json.str(o, "type", 40))
            .put("text", text)
            .put("hashtags", JSONArray(tags))
            .put("createdAt", o.optLong("createdAt", System.currentTimeMillis()))
    }

    private fun summary(song: JSONObject): JSONObject {
        val lyrics = song.optString("lyrics")
        val beat = song.optJSONObject("beat")
        return JSONObject()
            .put("id", song.getString("id"))
            .put("title", song.optString("title"))
            .put("artist", song.optString("artist"))
            .put("cover", song.optJSONObject("cover") ?: JSONObject())
            .put("genre", song.optString("genre"))
            .put("mood", song.optString("mood"))
            .put("bpm", song.optInt("bpm"))
            .put("key", song.optString("key"))
            .put("status", song.optString("status", "draft"))
            .put("favorite", song.optBoolean("favorite"))
            .put("createdAt", song.optLong("createdAt"))
            .put("modifiedAt", song.optLong("modifiedAt"))
            .put("hasLyrics", lyrics.isNotBlank())
            .put("lyricsPreview", lyrics.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("[") } ?: "")
            .put("hasBeat", beat != null && BeatPattern.from(beat).hasNotes())
            .put("hasIdea", song.optJSONObject("idea") != null)
            .put("promoCount", song.optJSONArray("promotion")?.length() ?: 0)
    }

    private fun readFile(f: File): JSONObject? {
        if (!f.exists()) return null
        return try {
            JSONObject(String(AtomicFile(f).readFully(), Charsets.UTF_8))
        } catch (e: Exception) {
            null // A corrupt file must never break the whole library.
        }
    }

    private fun write(song: JSONObject) {
        val atomic = AtomicFile(fileFor(song.getString("id")))
        val stream = try {
            atomic.startWrite()
        } catch (e: Exception) {
            throw AppException(ErrorCode.STORAGE, "Could not write to device storage.", e)
        }
        try {
            stream.write(song.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw AppException(ErrorCode.STORAGE, "Saving failed. Is the device storage full?", e)
        }
    }

    private fun fileFor(id: String): File {
        if (!ID_PATTERN.matches(id)) throw AppException(ErrorCode.BAD_REQUEST, "Invalid song id.")
        return File(dir, "$id.json")
    }

    private fun newId() = UUID.randomUUID().toString()

    companion object {
        const val MAX_TITLE = 120
        const val MAX_LYRICS = 20_000
        private const val MAX_PROMOS = 50
        private val ID_PATTERN = Regex("^[A-Za-z0-9-]{1,64}$")
        val STATUSES = setOf("draft", "writing", "recording", "finished", "released")
        private val KEY_PATTERN = Regex("^[A-G](#|b)?m?$")

        /** Accepts "Am", "A minor", "F# maj", "Bb" and returns compact notation or "". */
        fun normalizeKey(raw: String): String {
            val s = raw.trim().replace("♯", "#").replace("♭", "b")
            if (s.isEmpty()) return ""
            val m = Regex("^([A-Ga-g])\\s*(#|b)?\\s*(m|min|minor|maj|major)?$", RegexOption.IGNORE_CASE).find(s)
                ?: return ""
            val note = m.groupValues[1].uppercase()
            val acc = m.groupValues[2]
            val quality = m.groupValues[3].lowercase()
            val minor = quality == "m" || quality.startsWith("min")
            val result = note + acc + if (minor) "m" else ""
            return if (KEY_PATTERN.matches(result)) result else ""
        }
    }
}
