package de.aikitori.retagger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BatchEditTest {
    private val a = Tags().with(Field.ALBUM, "X").with(Field.TITLE, "Eins").with(Field.GENRE, "Rock")
    private val b = Tags().with(Field.ALBUM, "X").with(Field.TITLE, "Zwei")

    @Test
    fun `shared values and differences`() {
        val common = BatchEdit.commonValues(listOf(a, b))
        assertEquals("X", common[Field.ALBUM])
        assertEquals(null, common[Field.TITLE])
        assertEquals(null, common[Field.GENRE])
        assertEquals("", common[Field.ARTIST])
    }

    @Test
    fun `sets only changed fields, empty removes`() {
        val edit = BatchEdit(fields = mapOf(Field.ARTIST to "Band", Field.GENRE to ""))
        val result = edit.apply(a, 0, 2)
        assertEquals(mapOf(Field.ALBUM to "X", Field.TITLE to "Eins", Field.ARTIST to "Band"), result.fields)
    }

    @Test
    fun `numbers tracks`() {
        val edit = BatchEdit(numberTracks = true)
        assertEquals("2", edit.apply(b, 1, 5)[Field.TRACK])
        assertEquals("5", edit.apply(b, 1, 5)[Field.TRACK_TOTAL])
    }
}

class ImageSizeTest {
    @Test
    fun `reads size from PNG and JPEG`() {
        val png = javaClass.getResourceAsStream("/cover.png")!!.readBytes()
        assertEquals(2 to 2, ImageSize.of(png))
        // SOI, APP0 with 16 bytes, SOF0 with 300 x 200
        val jpeg = byteArrayOf(-1, -40, -1, -32, 0, 16) + ByteArray(14) +
            byteArrayOf(-1, -64, 0, 17, 8, 0, 200.toByte(), 1, 44, 3) + ByteArray(10)
        assertEquals(300 to 200, ImageSize.of(jpeg))
    }
}
