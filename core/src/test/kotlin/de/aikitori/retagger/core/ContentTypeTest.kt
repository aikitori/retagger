package de.aikitori.retagger.core

import de.aikitori.retagger.core.Mp4Fixture.Layout
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContentTypeTest {
    @TempDir
    lateinit var dir: File

    /** M4A named ".mp3", damaged like older versions did: ID3v2 tag in front, empty ID3v1 tag at the end. */
    private fun damaged(): Pair<File, ByteArray> {
        val file = File(dir, "Ascheberg - Duesenjaeger.mp3")
        Mp4Fixture.create(file, Layout.FRAGMENTED)
        val original = file.readBytes()
        ID3v23Tag().apply { setField(FieldKey.TITLE, "Ascheberg") }.write(file, 0)
        file.appendBytes("TAG".toByteArray() + ByteArray(125))
        return file to original
    }

    @Test
    fun `detects the real format behind a wrong extension`() {
        val (file, _) = damaged()
        assertEquals("m4a", ContentType.detect(file))
        assertEquals("mp3", ContentType.detect(javaClass.getResource("/silence.mp3")!!.file.let(::File)))
        assertTrue(ContentType.matches("m4a", "M4B"))
        assertFalse(ContentType.matches("m4a", "mp3"))
    }

    @Test
    fun `repair restores the original M4A byte for byte`() {
        val (file, original) = damaged()
        assertTrue(ContentType.repair(file))
        assertArrayEquals(original, file.readBytes())
        assertFalse(ContentType.repair(file))
    }

    @Test
    fun `writing tags to M4A named mp3 repairs it and uses MP4 tags`() {
        val (file, _) = damaged()
        TagIO.write(file, Tags().with(Field.TITLE, "Ascheberg").with(Field.ARTIST, "Duesenjaeger"))

        assertEquals("ftyp", String(file.readBytes(), 4, 4))
        Mp4Fixture.checkChunks(file, Layout.FRAGMENTED)
        val read = TagIO.read(file)
        assertEquals(mapOf(Field.TITLE to "Ascheberg", Field.ARTIST to "Duesenjaeger"), read.tags.fields)
        assertEquals("m4a", read.actualType)
    }

    @Test
    fun `real MP3 files are left alone`() {
        val file = File(dir, "a.mp3")
        javaClass.getResourceAsStream("/silence.mp3")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        TagIO.write(file, Tags().with(Field.TITLE, "x"))
        assertFalse(ContentType.repair(file))
        assertEquals(null, TagIO.read(file).actualType)
    }
}
