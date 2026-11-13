package de.aikitori.retagger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class TagIOTest {
    @TempDir
    lateinit var dir: File

    private fun copy(name: String): File {
        val target = File(dir, name)
        javaClass.getResourceAsStream("/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private val png = javaClass.getResourceAsStream("/cover.png")!!.readBytes()

    // ID3v2.3 stores the year with four digits only
    private fun value(field: Field, number: String, year: String) = when {
        field == Field.YEAR -> year
        field.numeric -> number
        else -> "Value ${field.name} äöü"
    }

    @ParameterizedTest
    @ValueSource(strings = ["silence.mp3", "silence.flac"])
    fun `writes and reads all fields and the cover`(name: String) {
        val file = copy(name)
        val tags = Field.entries.fold(Tags()) { tags, field ->
            tags.with(field, value(field, "07", "2024"))
        }.copy(artwork = Artwork(png, "image/png"))

        TagIO.write(file, tags)
        val read = TagIO.read(file).tags

        for (field in Field.entries) {
            assertEquals(value(field, "7", "2024"), read[field], field.name)
        }
        assertEquals(Artwork(png, "image/png"), read.artwork)
    }

    @ParameterizedTest
    @ValueSource(strings = ["silence.mp3", "silence.flac"])
    fun `empty fields and cover are removed`(name: String) {
        val file = copy(name)
        TagIO.write(file, Tags().with(Field.TITLE, "A").with(Field.ARTIST, "B").copy(artwork = Artwork(png, "image/png")))
        TagIO.write(file, Tags().with(Field.TITLE, "A"))

        val read = TagIO.read(file).tags
        assertEquals(mapOf(Field.TITLE to "A"), read.fields)
        assertNull(read.artwork)
    }

    @org.junit.jupiter.api.Test
    fun `rejects an invalid track number`() {
        val file = copy("silence.mp3")
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            TagIO.write(file, Tags(mapOf(Field.TRACK to "eins")))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["silence.mp3", "silence.flac"])
    fun `cover works in Android mode`(name: String) {
        val file = copy(name)
        val options = org.jaudiotagger.tag.TagOptionSingleton.getInstance()
        try {
            TagIO.useAndroidMode()
            TagIO.write(file, Tags().copy(artwork = Artwork(png, "image/png")))
            assertEquals(Artwork(png, "image/png"), TagIO.read(file).tags.artwork)
        } finally {
            options.isAndroid = false
        }
    }
}
