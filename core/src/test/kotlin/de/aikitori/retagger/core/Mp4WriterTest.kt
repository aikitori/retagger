package de.aikitori.retagger.core

import de.aikitori.retagger.core.Mp4Fixture.Layout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

class Mp4WriterTest {
    @TempDir
    lateinit var dir: File

    private val png = javaClass.getResourceAsStream("/cover.png")!!.readBytes()

    @ParameterizedTest(name = "{0}, {1} tracks, co64={2}")
    @MethodSource("layouts")
    fun `tags can be written, grown and shrunk without breaking the audio`(layout: Layout, tracks: Int, co64: Boolean) {
        val file = File(dir, "test.m4a")
        Mp4Fixture.create(file, layout, tracks, co64)

        val full = Tags().with(Field.TITLE, "Ali Baba").with(Field.ARTIST, "Dritte Wahl").with(Field.TRACK, "3")
            .with(Field.TRACK_TOTAL, "12").with(Field.YEAR, "2004").copy(artwork = Artwork(png, "image/png"))
        TagIO.write(file, full)
        Mp4Fixture.checkChunks(file, layout, tracks)
        assertEquals(full, TagIO.read(file).tags)

        // grows beyond the padding
        val long = full.with(Field.COMMENT, "x".repeat(5000))
        TagIO.write(file, long)
        Mp4Fixture.checkChunks(file, layout, tracks)
        assertEquals(long, TagIO.read(file).tags)

        // shrinks, the gap becomes padding
        TagIO.write(file, Tags().with(Field.TITLE, "Kurz"))
        Mp4Fixture.checkChunks(file, layout, tracks)
        val read = TagIO.read(file).tags
        assertEquals(mapOf(Field.TITLE to "Kurz"), read.fields)
        assertNull(read.artwork)
    }

    companion object {
        @JvmStatic
        fun layouts() = listOf(
            Arguments.of(Layout.MOOV_FIRST, 1, false),
            Arguments.of(Layout.MOOV_FIRST, 2, false),
            Arguments.of(Layout.MOOV_FIRST, 2, true),
            Arguments.of(Layout.MOOV_LAST, 1, false),
            Arguments.of(Layout.MOOV_LAST, 2, true),
            Arguments.of(Layout.FRAGMENTED, 1, false),
        )
    }
}
