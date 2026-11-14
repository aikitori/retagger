package de.aikitori.retagger.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OggOpusTest {
    @TempDir
    lateinit var dir: File

    private val audioPackets = (0 until 5).map { i -> ByteArray(300) { (i + 1).toByte() } }

    private fun page(type: Int, granule: Long, seq: Int, packet: ByteArray): ByteArray {
        val lacing = ByteArray(packet.size / 255 + 1) { i -> if (i < packet.size / 255) 255.toByte() else (packet.size % 255).toByte() }
        val bytes = ByteBuffer.allocate(27 + lacing.size + packet.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray()).put(0).put(type.toByte()).putLong(granule).putInt(0x1234).putInt(seq).putInt(0)
            .put(lacing.size.toByte()).put(lacing).put(packet).array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, OggOpus.crc(bytes))
        return bytes
    }

    /** A minimal Ogg Opus stream: OpusHead, OpusTags (with an ENCODER comment), 5 audio pages, 2 seconds. */
    private fun opusFile(withTags: Boolean = true): File {
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray()).put(1).put(2).putShort(312).putInt(48000).putShort(0).put(0).array()
        val comment = "ENCODER=test".toByteArray()
        val tags = ByteBuffer.allocate(8 + 4 + 6 + 4 + 4 + comment.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".toByteArray()).putInt(6).put("vendor".toByteArray()).putInt(1).putInt(comment.size).put(comment).array()
        val out = ByteArrayOutputStream()
        out.write(page(2, 0, 0, head))
        if (withTags) out.write(page(0, 0, 1, tags))
        val first = if (withTags) 2 else 1
        audioPackets.forEachIndexed { i, packet ->
            out.write(page(if (i == audioPackets.lastIndex) 4 else 0, 312L + (i + 1) * 19200, first + i, packet))
        }
        return File(dir, "test.opus").apply { writeBytes(out.toByteArray()) }
    }

    /** Checks every page's CRC and sequence number and returns the audio packets. */
    private fun audio(file: File): List<ByteArray> {
        val bytes = file.readBytes()
        val packets = mutableListOf<ByteArray>()
        var pos = 0
        var seq = 0
        while (pos < bytes.size) {
            val buffer = ByteBuffer.wrap(bytes, pos, bytes.size - pos).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("OggS", String(bytes, pos, 4))
            val segments = bytes[pos + 26].toInt() and 0xff
            val size = 27 + segments + (0 until segments).sumOf { bytes[pos + 27 + it].toInt() and 0xff }
            val page = bytes.copyOfRange(pos, pos + size)
            val stored = buffer.getInt(pos + 22)
            ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(22, 0)
            assertEquals(OggOpus.crc(page), stored, "CRC of page $seq")
            assertEquals(seq++, buffer.getInt(pos + 18))
            // header pages have granule 0, or -1 when no packet ends on them
            if (buffer.getLong(pos + 6) > 0) packets += page.copyOfRange(27 + segments, size)
            pos += size
        }
        return packets
    }

    @Test
    fun `adds a missing OpusTags packet`() {
        val file = opusFile(withTags = false)
        assertEquals(Tags(), TagIO.read(file).tags)
        TagIO.write(file, Tags().with(Field.TITLE, "Neu"))
        assertEquals(mapOf(Field.TITLE to "Neu"), TagIO.read(file).tags.fields)
        assertEquals(audioPackets.map { it.toList() }, audio(file).map { it.toList() })
    }

    @Test
    fun `crc matches the Ogg check value`() {
        assertEquals(0x89A1897F.toInt(), OggOpus.crc("123456789".toByteArray()))
    }

    @Test
    fun `writes and reads tags and a large cover that spans several pages`() {
        val file = opusFile()
        assertEquals("opus", ContentType.detect(file))
        // larger than one page (255 * 255 bytes), so the following pages get renumbered
        val png = javaClass.getResourceAsStream("/cover.png")!!.readBytes() + ByteArray(100_000) { it.toByte() }
        val tags = Tags().with(Field.TITLE, "Ascheberg").with(Field.ARTIST, "Duesenjaeger").with(Field.TRACK, "3")
            .with(Field.TRACK_TOTAL, "12").with(Field.YEAR, "2004").copy(artwork = Artwork(png, "image/png"))
        TagIO.write(file, tags)

        val read = TagIO.read(file)
        assertEquals(tags, read.tags)
        assertEquals(2, read.info.seconds)
        assertEquals(audioPackets.map { it.toList() }, audio(file).map { it.toList() })
        // unknown comments survive
        assertEquals(true, String(file.readBytes(), Charsets.ISO_8859_1).contains("ENCODER=test"))

        // shrink again, back to a single tag page
        TagIO.write(file, Tags().with(Field.TITLE, "Kurz"))
        assertEquals(mapOf(Field.TITLE to "Kurz"), TagIO.read(file).tags.fields)
        assertArrayEquals(audioPackets.last(), audio(file).last())
    }
}
