package com.glydexstudio.musicmaker.ai

import com.glydexstudio.musicmaker.core.AppException
import com.glydexstudio.musicmaker.core.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResponseParserTest {

    @Test
    fun stripsThinkBlocksAndFences() {
        val raw = "<think>\nplanning...\n</think>\n```json\n{\"title\": \"Neon\", \"lyrics\": \"[Verse]\\nLine one\"}\n```"
        val out = ResponseParser.lyrics(ResponseParser.clean(raw), punchlines = false)
        assertEquals("Neon", out.getString("title"))
        assertEquals("[Verse]\nLine one", out.getString("lyrics"))
    }

    @Test
    fun repairsRawNewlinesAndTrailingCommasInsideJson() {
        val raw = "Sure! Here it is:\n{\"title\": \"Rain\", \"lyrics\": \"[Chorus]\nWe dance\nIn the rain\",}\nHope you like it."
        val out = ResponseParser.lyrics(ResponseParser.clean(raw), punchlines = false)
        assertEquals("[Chorus]\nWe dance\nIn the rain", out.getString("lyrics"))
    }

    @Test
    fun acceptsPlainTextLyricsWhenModelIgnoresJson() {
        val out = ResponseParser.lyrics("[Hook]\nOh oh oh\nWe go", punchlines = false)
        assertEquals("[Hook]\nOh oh oh\nWe go", out.getString("lyrics"))
    }

    @Test
    fun rejectsBrokenJsonWithAppException() {
        try {
            ResponseParser.songIdea("{\"title\": ")
            fail("expected exception")
        } catch (e: AppException) {
            assertEquals(ErrorCode.INVALID_RESPONSE, e.code)
        }
    }

    @Test
    fun songIdeaClampsAndNormalizes() {
        val raw = "{\"title\":\"Glass\",\"concept\":\"Leaving\",\"bpm\":\"about 128 bpm\",\"key\":\"F# minor\",\"verseIdeas\":\"- one\\n- two\"}"
        val out = ResponseParser.songIdea(raw)
        assertEquals(128, out.getInt("bpm"))
        assertEquals("F#m", out.getString("key"))
        assertEquals(2, out.getJSONArray("verseIdeas").length())
        assertEquals("one", out.getJSONArray("verseIdeas").getString(0))
    }

    @Test
    fun songIdeaDropsOutOfRangeBpm() {
        val out = ResponseParser.songIdea("{\"title\":\"A\",\"concept\":\"B\",\"bpm\":900}")
        assertTrue(out.isNull("bpm"))
    }

    @Test
    fun promotionCleansHashtags() {
        val out = ResponseParser.promotion("{\"text\":\"Out now\",\"hashtags\":[\"newmusic\",\"#New Music\",\"#newmusic\",\"#pop!\"]}")
        val tags = (0 until out.getJSONArray("hashtags").length()).map { out.getJSONArray("hashtags").getString(it) }
        assertEquals(listOf("#newmusic", "#New", "#Music", "#pop"), tags)
    }

    @Test
    fun punchlinesFromArray() {
        val out = ResponseParser.lyrics("{\"punchlines\": [\"a line\", \"another\"]}", punchlines = true)
        assertEquals(2, out.getJSONArray("punchlines").length())
    }
}
