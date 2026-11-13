package de.aikitori.tagwerk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FilenamePatternTest {
    @Test
    fun `liest Felder aus dem Dateinamen`() {
        val pattern = FilenamePattern("%track% - %artist% - %title%")
        assertEquals(
            mapOf(Field.TRACK to "3", Field.ARTIST to "Die Ärzte", Field.TITLE to "Schrei nach Liebe - Live"),
            pattern.parse("03 - Die Ärzte - Schrei nach Liebe - Live"),
        )
    }

    @Test
    fun `Unterstriche werden zu Leerzeichen`() {
        assertEquals(
            mapOf(Field.ARTIST to "Daft Punk", Field.TITLE to "One More Time"),
            FilenamePattern("%artist%-%title%").parse("Daft_Punk-One_More_Time"),
        )
    }

    @Test
    fun `passt der Name nicht, gibt es null`() {
        assertNull(FilenamePattern("%track% - %title%").parse("Ohne Nummer"))
    }

    @Test
    fun `unbekannte Platzhalter bleiben Text`() {
        assertEquals(mapOf(Field.TITLE to "x"), FilenamePattern("%foo% %title%").parse("%foo% x"))
    }

    @Test
    fun `baut Dateinamen mit zweistelliger Tracknummer und ersetzt verbotene Zeichen`() {
        val tags = Tags().with(Field.TRACK, "4").with(Field.TITLE, "AC/DC: Live?")
        assertEquals("04 - AC_DC_ Live_", FilenamePattern("%track% - %title%").format(tags))
    }
}

class NaturalOrderTest {
    @Test
    fun `Zahlen werden als Zahlen sortiert`() {
        val names = listOf("10 - Zehn.mp3", "2 - Zwei.mp3", "01 - Eins.mp3", "b.mp3", "A.mp3")
        assertEquals(listOf("01 - Eins.mp3", "2 - Zwei.mp3", "10 - Zehn.mp3", "A.mp3", "b.mp3"), names.sortedWith(NaturalOrder))
    }
}
