package de.aikitori.retagger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FilenamePatternTest {
    @Test
    fun `parses fields from the file name`() {
        val pattern = FilenamePattern("%track% - %artist% - %title%")
        assertEquals(
            mapOf(Field.TRACK to "3", Field.ARTIST to "Die Ärzte", Field.TITLE to "Schrei nach Liebe - Live"),
            pattern.parse("03 - Die Ärzte - Schrei nach Liebe - Live"),
        )
    }

    @Test
    fun `underscores become spaces`() {
        assertEquals(
            mapOf(Field.ARTIST to "Daft Punk", Field.TITLE to "One More Time"),
            FilenamePattern("%artist%-%title%").parse("Daft_Punk-One_More_Time"),
        )
    }

    @Test
    fun `returns null if the name does not match`() {
        assertNull(FilenamePattern("%track% - %title%").parse("Ohne Nummer"))
    }

    @Test
    fun `unknown placeholders stay literal`() {
        assertEquals(mapOf(Field.TITLE to "x"), FilenamePattern("%foo% %title%").parse("%foo% x"))
    }

    @Test
    fun `formats two-digit track numbers and replaces illegal characters`() {
        val tags = Tags().with(Field.TRACK, "4").with(Field.TITLE, "AC/DC: Live?")
        assertEquals("04 - AC_DC_ Live_", FilenamePattern("%track% - %title%").format(tags))
    }
}

class NaturalOrderTest {
    @Test
    fun `sorts numbers numerically`() {
        val names = listOf("10 - Zehn.mp3", "2 - Zwei.mp3", "01 - Eins.mp3", "b.mp3", "A.mp3")
        assertEquals(listOf("01 - Eins.mp3", "2 - Zwei.mp3", "10 - Zehn.mp3", "A.mp3", "b.mp3"), names.sortedWith(NaturalOrder))
    }
}
