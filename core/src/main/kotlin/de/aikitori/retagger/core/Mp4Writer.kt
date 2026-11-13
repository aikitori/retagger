package de.aikitori.retagger.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces the `ilst` atom (iTunes metadata) of an MP4/M4A file.
 *
 * jaudiotagger's own writer only adjusts some chunk offset tables and gives up on files with
 * several tracks, `co64` tables or several `mdat` atoms ("incorrect offsets written").
 * This writer rebuilds `moov` and shifts every `stco`/`co64` entry of every track that points
 * behind it. It keeps some padding (`free`) so later edits usually don't move the audio at all.
 */
internal object Mp4Writer {
    private const val PADDING = 2048

    private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val end get() = start + size
        val contentStart get() = start + headerSize
    }

    fun write(file: File, ilst: ByteArray) {
        val temp = File(file.parentFile, file.name + ".tmp")
        val replaced = RandomAccessFile(file, "rw").use { raf ->
            val top = boxes(raf.length()) { pos, len -> ByteArray(len).also { raf.seek(pos); raf.readFully(it) } }
            val moov = top.singleOrNull { it.type == "moov" } ?: throw IOException("No moov atom")
            if (moov.size > Int.MAX_VALUE) throw IOException("moov atom too large")
            val oldMoov = ByteArray(moov.size.toInt()).also { raf.seek(moov.start); raf.readFully(it) }
            // audio data behind moov moves when moov changes size
            val dataBehind = top.any { it.start >= moov.end && it.type == "mdat" }
            val read = { pos: Long, len: Int -> ByteArray(len).also { raf.seek(pos); raf.readFully(it) } }
            if (dataBehind) checkFragments(top.filter { it.start >= moov.end }, read)

            var newMoov = rebuildMoov(oldMoov, ilst, padding = 0)
            if (dataBehind) {
                val shrink = oldMoov.size - newMoov.size
                // fill the gap with padding so nothing has to move; otherwise make room once, with spare space
                val padding = when {
                    shrink == 0 -> 0
                    shrink >= 8 -> shrink
                    else -> PADDING
                }
                newMoov = rebuildMoov(oldMoov, ilst, padding)
            }
            val delta = (newMoov.size - oldMoov.size).toLong()
            if (dataBehind && delta != 0L) shiftOffsets(newMoov, moov.end, delta)

            when {
                delta == 0L -> {
                    raf.seek(moov.start)
                    raf.write(newMoov)
                    false
                }
                moov.end == raf.length() -> {
                    raf.seek(moov.start)
                    raf.write(newMoov)
                    raf.setLength(moov.start + newMoov.size)
                    false
                }
                else -> {
                    // start + new moov + rest into a new file, which then replaces the old one
                    temp.outputStream().buffered().use { out ->
                        copy(raf, 0, moov.start, out)
                        out.write(newMoov)
                        copy(raf, moov.end, raf.length(), out)
                    }
                    true
                }
            }
        }
        if (replaced) {
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
    }

    private fun copy(raf: RandomAccessFile, from: Long, to: Long, out: java.io.OutputStream) {
        raf.seek(from)
        val buffer = ByteArray(1 shl 16)
        var left = to - from
        while (left > 0) {
            val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Unexpected end of file")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    /**
     * Fragmented files (DASH, e.g. from download tools) stay valid when moov grows as long as the fragments
     * address their data relative to their own moof. An absolute base-data-offset or an mfra index would break.
     */
    private fun checkFragments(behind: List<Box>, read: (Long, Int) -> ByteArray) {
        if (behind.any { it.type == "mfra" }) throw IOException("Fragmented MP4 with mfra index is not supported")
        for (moof in behind.filter { it.type == "moof" }) {
            if (moof.size > Int.MAX_VALUE) throw IOException("moof atom too large")
            val data = read(moof.start, moof.size.toInt())
            for (traf in children(data, moof.headerSize, data.size).filter { it.type == "traf" }) {
                val tfhd = children(data, traf.contentStart.toInt(), traf.end.toInt()).firstOrNull { it.type == "tfhd" } ?: continue
                val flags = ByteBuffer.wrap(data).getInt(tfhd.contentStart.toInt()) and 0xffffff
                if (flags and 0x1 != 0) throw IOException("Fragmented MP4 with absolute data offsets is not supported")
            }
        }
    }

    /** Box headers in [0, length); [read] returns raw bytes at a position. */
    private fun boxes(length: Long, offset: Long = 0, read: (Long, Int) -> ByteArray): List<Box> {
        val result = mutableListOf<Box>()
        var pos = offset
        while (pos + 8 <= length) {
            val header = read(pos, 8)
            var size = ByteBuffer.wrap(header).int.toLong() and 0xffffffffL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L) {
                size = ByteBuffer.wrap(read(pos + 8, 8)).long
                headerSize = 16
            } else if (size == 0L) {
                size = length - pos
            }
            if (size < headerSize || pos + size > length) throw IOException("Broken $type atom at $pos")
            result += Box(type, pos, headerSize, size)
            pos += size
        }
        return result
    }

    private fun children(data: ByteArray, from: Int, to: Int): List<Box> =
        boxes(to.toLong(), from.toLong()) { pos, len -> data.copyOfRange(pos.toInt(), pos.toInt() + len) }

    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val size = 8 + content.sumOf { it.size }
        val out = ByteArrayOutputStream(size)
        out.write(ByteBuffer.allocate(4).putInt(size).array())
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        content.forEach(out::write)
        return out.toByteArray()
    }

    private fun ByteArray.slice(box: Box) = copyOfRange(box.start.toInt(), box.end.toInt())

    private fun rebuildMoov(moov: ByteArray, ilst: ByteArray, padding: Int): ByteArray {
        val top = children(moov, 0, moov.size).single()
        val kids = children(moov, top.headerSize, moov.size)
        val udta = kids.firstOrNull { it.type == "udta" }
        val newUdta = rebuildUdta(udta?.let { moov.copyOfRange(it.contentStart.toInt(), it.end.toInt()) }, ilst, padding)
        val parts = kids.filter { it !== udta }.map { moov.slice(it) } + listOf(newUdta)
        return box("moov", *parts.toTypedArray())
    }

    private fun rebuildUdta(content: ByteArray?, ilst: ByteArray, padding: Int): ByteArray {
        val kids = content?.let { children(it, 0, it.size) }.orEmpty()
        val meta = kids.firstOrNull { it.type == "meta" }
        val newMeta = rebuildMeta(meta?.let { content!!.copyOfRange(it.contentStart.toInt(), it.end.toInt()) }, ilst, padding)
        val parts = kids.map { if (it === meta) newMeta else content!!.slice(it) }.toMutableList()
        if (meta == null) parts += newMeta
        return box("udta", *parts.toTypedArray())
    }

    private fun rebuildMeta(content: ByteArray?, ilst: ByteArray, padding: Int): ByteArray {
        // iTunes writes meta as a full box (4 bytes version/flags), QuickTime without
        val full = content == null || content.size < 8 || String(content, 4, 4, Charsets.ISO_8859_1) != "hdlr"
        val start = if (full && content != null) 4 else 0
        val kids = content?.let { children(it, start, it.size) }.orEmpty()
        val hdlr = kids.firstOrNull { it.type == "hdlr" }?.let { content!!.slice(it) } ?: box(
            "hdlr",
            ByteArray(8), // version/flags, pre_defined
            "mdirappl".toByteArray(Charsets.ISO_8859_1),
            ByteArray(9), // reserved, empty name
        )
        val others = kids.filter { it.type != "hdlr" && it.type != "ilst" && it.type != "free" }.map { content!!.slice(it) }
        val parts = mutableListOf<ByteArray>()
        if (full) parts += content?.copyOfRange(0, 4) ?: ByteArray(4)
        parts += hdlr
        parts += ilst
        parts += others
        if (padding >= 8) parts += box("free", ByteArray(padding - 8))
        return box("meta", *parts.toTypedArray())
    }

    /** Adds [delta] to every chunk offset that points at or behind [from]. */
    private fun shiftOffsets(moov: ByteArray, from: Long, delta: Long) {
        fun walk(start: Int, end: Int) {
            for (box in children(moov, start, end)) {
                when (box.type) {
                    "trak", "mdia", "minf", "stbl" -> walk(box.contentStart.toInt(), box.end.toInt())
                    "stco", "co64" -> {
                        val buffer = ByteBuffer.wrap(moov)
                        val count = buffer.getInt(box.contentStart.toInt() + 4)
                        val wide = box.type == "co64"
                        var pos = box.contentStart.toInt() + 8
                        repeat(count) {
                            if (wide) {
                                val offset = buffer.getLong(pos)
                                if (offset >= from) buffer.putLong(pos, offset + delta)
                                pos += 8
                            } else {
                                val offset = buffer.getInt(pos).toLong() and 0xffffffffL
                                if (offset >= from) {
                                    val moved = offset + delta
                                    if (moved > 0xffffffffL) throw IOException("File too large for stco")
                                    buffer.putInt(pos, moved.toInt())
                                }
                                pos += 4
                            }
                        }
                    }
                }
            }
        }
        val top = children(moov, 0, moov.size).single()
        walk(top.headerSize, moov.size)
    }
}
