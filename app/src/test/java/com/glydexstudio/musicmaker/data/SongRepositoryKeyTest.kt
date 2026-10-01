package com.glydexstudio.musicmaker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SongRepositoryKeyTest {
    @Test
    fun normalizesMusicalKeys() {
        assertEquals("Am", SongRepository.normalizeKey("A minor"))
        assertEquals("Am", SongRepository.normalizeKey("am"))
        assertEquals("C", SongRepository.normalizeKey("C major"))
        assertEquals("Bb", SongRepository.normalizeKey("B♭"))
        assertEquals("F#m", SongRepository.normalizeKey("F# min"))
        assertEquals("", SongRepository.normalizeKey("H dorian"))
        assertEquals("", SongRepository.normalizeKey(""))
    }
}
