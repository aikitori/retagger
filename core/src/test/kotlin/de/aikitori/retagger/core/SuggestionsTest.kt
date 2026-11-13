package de.aikitori.retagger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuggestionsTest {
    @Test
    fun `shared part is the artist, the rest the title`() {
        val files = listOf("Ascheberg - Duesenjaeger.mp3", "Schrikdraad - Duesenjaeger.mp3", "Treibsand - Duesenjaeger.mp3")
        val s = Suggestions.from(files[0], listOf("Music", "Duesenjaeger", "Schimmern (2004)"), files)
        assertEquals("Ascheberg", s[Field.TITLE]?.first())
        assertEquals("Duesenjaeger", s[Field.ARTIST]?.first())
        assertEquals("Schimmern", s[Field.ALBUM]?.first())
        assertEquals(listOf("2004"), s[Field.YEAR])
    }

    @Test
    fun `track number, artist - album folder and disc folder`() {
        val files = listOf("01 - Intro.flac", "02 - Song.flac")
        val s = Suggestions.from(files[1], listOf("Musik", "Band - Album [1999]", "CD2"), files)
        assertEquals(listOf("2"), s[Field.TRACK])
        assertEquals(listOf("2"), s[Field.TRACK_TOTAL])
        assertEquals(listOf("Song"), s[Field.TITLE])
        assertEquals("Band", s[Field.ARTIST]?.first())
        assertEquals("Album", s[Field.ALBUM]?.first())
        assertEquals(listOf("1999"), s[Field.YEAR])
        assertEquals(listOf("2"), s[Field.DISC])
    }

    @Test
    fun `single file in a generic folder`() {
        val s = Suggestions.from("Daft_Punk_-_One_More_Time.mp3", listOf("Download"), listOf("Daft_Punk_-_One_More_Time.mp3"))
        assertEquals("One More Time", s[Field.TITLE]?.first())
        assertEquals("Daft Punk", s[Field.ARTIST]?.first())
        assertEquals(null, s[Field.ALBUM])
    }

    @Test
    fun `batch leaves out per-file values`() {
        val files = listOf("01 - A - Band.mp3", "02 - B - Band.mp3")
        val s = Suggestions.forBatch(files, listOf("Album"))
        assertEquals(null, s[Field.TITLE])
        assertEquals(null, s[Field.TRACK])
        assertEquals("Band", s[Field.ARTIST]?.first())
        assertEquals("Album", s[Field.ALBUM]?.first())
    }
}
