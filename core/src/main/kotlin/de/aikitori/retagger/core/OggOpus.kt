package de.aikitori.retagger.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Reads and writes tags of Ogg Opus files (RFC 7845), which jaudiotagger doesn't support.
 * Tags are Vorbis comments in the OpusTags packet, the cover a base64 FLAC picture block.
 */
object OggOpus {
    private val keys = mapOf(
        Field.TITLE to "TITLE",
        Field.ARTIST to "ARTIST",
        Field.ALBUM to "ALBUM",
        Field.ALBUM_ARTIST to "ALBUMARTIST",
        Field.YEAR to "DATE",
        Field.TRACK to "TRACKNUMBER",
        Field.TRACK_TOTAL to "TRACKTOTAL",
        Field.DISC to "DISCNUMBER",
        Field.DISC_TOTAL to "DISCTOTAL",
        Field.GENRE to "GENRE",
        Field.COMPOSER to "COMPOSER",
        Field.COMMENT to "COMMENT",
    )
    private val aliases = mapOf(
        "TOTALTRACKS" to Field.TRACK_TOTAL,
        "TOTALDISCS" to Field.DISC_TOTAL,
        "ALBUM ARTIST" to Field.ALBUM_ARTIST,
        "DESCRIPTION" to Field.COMMENT,
    )
    private const val PICTURE = "METADATA_BLOCK_PICTURE"

    private class Page(
        val type: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val lacing: ByteArray,
        val data: ByteArray,
        val raw: ByteArray,
    )

    private class Header(val pages: List<Page>, val head: ByteArray, val tags: ByteArray, val audioStart: Long)

    fun read(file: File): AudioFileTags = RandomAccessFile(file, "r").use { raf ->
        val header = header(raf)
        val comments = parseTags(header.tags).second
        var tags = Tags()
        var artwork: Artwork? = null
        for ((key, value) in comments) {
            val upper = key.uppercase()
            if (upper == PICTURE) {
                if (artwork == null) artwork = runCatching { picture(Base64.getDecoder().decode(value.trim())) }.getOrNull()
                continue
            }
            val field = keys.entries.firstOrNull { it.value == upper }?.key ?: aliases[upper] ?: continue
            if (tags[field].isNotEmpty()) continue
            // "3/12" in TRACKNUMBER
            if ((field == Field.TRACK || field == Field.DISC) && '/' in value) {
                tags = tags.with(field, value.substringBefore('/'))
                val total = if (field == Field.TRACK) Field.TRACK_TOTAL else Field.DISC_TOTAL
                if (tags[total].isEmpty()) tags = tags.with(total, value.substringAfter('/'))
            } else {
                tags = tags.with(field, value)
            }
        }
        val preSkip = ByteBuffer.wrap(header.head, 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val samples = (lastGranule(raf) - preSkip).coerceAtLeast(0)
        val seconds = (samples / 48000).toInt()
        val kbps = if (seconds > 0) ((raf.length() - header.audioStart) * 8 / 1000 / seconds).toString() else ""
        AudioFileTags(Tags(tags.fields, artwork), AudioInfo("Opus", seconds, kbps))
    }

    fun write(file: File, tags: Tags) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = header(raf)
                val (vendor, old) = parseTags(header.tags)
                // keep what we don't edit, e.g. ENCODER or R128 gain
                val known = keys.values.toSet() + aliases.keys + PICTURE
                val comments = old.filter { (key, _) -> key.uppercase() !in known }.toMutableList()
                for ((field, key) in keys) {
                    val value = tags[field]
                    if (value.isNotEmpty()) comments += key to value
                }
                tags.artwork?.let { comments += PICTURE to Base64.getEncoder().encodeToString(pictureBlock(it)) }
                val first = header.pages.first()
                val tagPages = paginate(buildTags(vendor, comments), first.serial, firstSequence = 1)
                val shift = tagPages.size - (header.pages.size - 1)

                temp.outputStream().buffered().use { out ->
                    out.write(first.raw)
                    tagPages.forEach { out.write(it) }
                    var pos = header.audioStart
                    while (pos < raf.length()) {
                        val page = readPage(raf, pos) ?: throw IOException("Broken Ogg page at $pos")
                        pos += page.raw.size
                        if (shift == 0) {
                            out.write(page.raw)
                        } else {
                            writePage(out, page.type, page.granule, page.serial, page.sequence + shift, page.lacing, page.data)
                        }
                    }
                }
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    /**
     * OpusHead (alone on the first page) and OpusTags, which end on a page boundary before the audio.
     * A missing OpusTags packet is treated as empty, it is added on the next write.
     */
    private fun header(raf: RandomAccessFile): Header {
        val first = readPage(raf, 0) ?: throw IOException("Not an Ogg Opus file")
        val head = first.data
        if (head.size < 19 || String(head, 0, 8, Charsets.ISO_8859_1) != "OpusHead") throw IOException("Not an Ogg Opus file")
        val pages = mutableListOf(first)
        var pos = first.raw.size.toLong()
        val second = readPage(raf, pos)
        if (second == null || second.data.size < 8 || String(second.data, 0, 8, Charsets.ISO_8859_1) != "OpusTags") {
            return Header(pages, head, emptyTags(), pos)
        }
        val tags = ByteArrayOutputStream()
        var page: Page = second
        while (true) {
            pages += page
            pos += page.raw.size
            var offset = 0
            var complete = false
            for (l in page.lacing) {
                val length = l.toInt() and 0xff
                tags.write(page.data, offset, length)
                offset += length
                if (length < 255) {
                    complete = true
                    break
                }
            }
            if (complete) break
            page = readPage(raf, pos) ?: throw IOException("OpusTags cut off")
        }
        return Header(pages, head, tags.toByteArray(), pos)
    }

    private fun emptyTags() = buildTags("Retagger".toByteArray(), emptyList())

    private fun parseTags(packet: ByteArray): Pair<ByteArray, List<Pair<String, String>>> {
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(8)
        val vendor = ByteArray(buffer.int).also { buffer.get(it) }
        val count = buffer.int
        val comments = (0 until count).mapNotNull {
            val text = String(ByteArray(buffer.int).also { b -> buffer.get(b) }, Charsets.UTF_8)
            if ('=' in text) text.substringBefore('=') to text.substringAfter('=') else null
        }
        return vendor to comments
    }

    private fun buildTags(vendor: ByteArray, comments: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        out.write("OpusTags".toByteArray(Charsets.ISO_8859_1))
        int(vendor.size)
        out.write(vendor)
        int(comments.size)
        for ((key, value) in comments) {
            val bytes = "$key=$value".toByteArray(Charsets.UTF_8)
            int(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun pictureBlock(art: Artwork): ByteArray {
        val (width, height) = ImageSize.of(art.data) ?: (0 to 0)
        val mime = art.mimeType.toByteArray(Charsets.ISO_8859_1)
        return ByteBuffer.allocate(32 + mime.size + art.data.size)
            .putInt(3) // front cover
            .putInt(mime.size).put(mime)
            .putInt(0) // no description
            .putInt(width).putInt(height).putInt(24).putInt(0)
            .putInt(art.data.size).put(art.data)
            .array()
    }

    private fun picture(block: ByteArray): Artwork {
        val buffer = ByteBuffer.wrap(block)
        buffer.int // picture type
        val mime = String(ByteArray(buffer.int).also { buffer.get(it) }, Charsets.ISO_8859_1)
        val description = buffer.int
        buffer.position(buffer.position() + description + 16) // description, size, depth, colours
        val data = ByteArray(buffer.int).also { buffer.get(it) }
        return Artwork(data, mime.ifEmpty { TagIO.guessMime(data) })
    }

    /** Splits a packet into pages of up to 255 segments; header pages carry granule 0 when the packet ends. */
    private fun paginate(packet: ByteArray, serial: Int, firstSequence: Int): List<ByteArray> {
        val lacing = ByteArray(packet.size / 255 + 1) { i -> if (i < packet.size / 255) 255.toByte() else (packet.size % 255).toByte() }
        val pages = mutableListOf<ByteArray>()
        var segment = 0
        var offset = 0
        while (segment < lacing.size) {
            val count = minOf(255, lacing.size - segment)
            val pageLacing = lacing.copyOfRange(segment, segment + count)
            val length = pageLacing.sumOf { it.toInt() and 0xff }
            val last = segment + count == lacing.size
            val out = ByteArrayOutputStream()
            writePage(
                out,
                type = if (segment == 0) 0 else 1, // continued packet
                granule = if (last) 0 else -1, // -1: no packet ends on this page
                serial = serial,
                sequence = firstSequence + pages.size,
                lacing = pageLacing,
                data = packet.copyOfRange(offset, offset + length),
            )
            pages += out.toByteArray()
            segment += count
            offset += length
        }
        return pages
    }

    private fun readPage(raf: RandomAccessFile, pos: Long): Page? {
        if (pos + 27 > raf.length()) return null
        val head = ByteArray(27)
        raf.seek(pos)
        raf.readFully(head)
        if (String(head, 0, 4, Charsets.ISO_8859_1) != "OggS") return null
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val lacing = ByteArray(head[26].toInt() and 0xff).also { raf.readFully(it) }
        val data = ByteArray(lacing.sumOf { it.toInt() and 0xff }).also { raf.readFully(it) }
        return Page(head[5].toInt(), buffer.getLong(6), buffer.getInt(14), buffer.getInt(18), lacing, data, head + lacing + data)
    }

    private fun writePage(out: OutputStream, type: Int, granule: Long, serial: Int, sequence: Int, lacing: ByteArray, data: ByteArray) {
        val page = ByteBuffer.allocate(27 + lacing.size + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray(Charsets.ISO_8859_1))
            .put(0)
            .put(type.toByte())
            .putLong(granule)
            .putInt(serial)
            .putInt(sequence)
            .putInt(0) // CRC, filled in below
            .put(lacing.size.toByte())
            .put(lacing)
            .put(data)
            .array()
        ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc(page))
        out.write(page)
    }

    /** Granule position of the last page, i.e. the number of 48 kHz samples including pre-skip. */
    private fun lastGranule(raf: RandomAccessFile): Long {
        val tail = minOf(raf.length(), 65_536L + 27 + 255)
        val bytes = ByteArray(tail.toInt())
        raf.seek(raf.length() - tail)
        raf.readFully(bytes)
        for (i in bytes.size - 27 downTo 0) {
            if (bytes[i] == 'O'.code.toByte() && bytes[i + 1] == 'g'.code.toByte() && bytes[i + 2] == 'g'.code.toByte() && bytes[i + 3] == 'S'.code.toByte()) {
                return ByteBuffer.wrap(bytes, i + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
            }
        }
        return 0
    }

    private val crcTable = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
        r
    }

    /** Ogg's CRC-32: polynomial 0x04c11db7, no reflection, initial value and final XOR 0. */
    internal fun crc(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (b.toInt() and 0xff)) and 0xff]
        return crc
    }
}
