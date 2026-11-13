package de.aikitori.retagger.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Builds small AAC-in-MP4 files with different layouts. Every chunk is filled with its own marker byte,
 * so [checkChunks] can verify that all chunk offsets still point at the right data after writing.
 */
object Mp4Fixture {
    enum class Layout { MOOV_FIRST, MOOV_LAST, FRAGMENTED }

    private const val CHUNK = 100
    private const val CHUNKS = 2

    fun marker(track: Int, chunk: Int) = (track * 16 + chunk + 1).toByte()

    fun create(file: File, layout: Layout, tracks: Int = 1, co64: Boolean = false) {
        val ftyp = box("ftyp", "M4A ".latin(), i32(0), "M4A isomiso2".latin())
        val payload = ByteArrayOutputStream()
        for (t in 0 until tracks) for (c in 0 until CHUNKS) payload.write(ByteArray(CHUNK) { marker(t, c) })
        val data = payload.toByteArray()

        val bytes = when (layout) {
            Layout.MOOV_FIRST -> {
                val size = moov(tracks, co64, 0).size
                ftyp + moov(tracks, co64, (ftyp.size + size + 8).toLong()) + box("mdat", data)
            }
            Layout.MOOV_LAST -> ftyp + box("mdat", data) + moov(tracks, co64, (ftyp.size + 8).toLong())
            Layout.FRAGMENTED -> {
                val moov = moov(1, co64, null)
                val moof = { offset: Int -> moof(offset) }
                // trun's data offset counts from the start of moof (default-base-is-moof)
                val size = moof(0).size
                ftyp + moov + moof(size + 8) + box("mdat", data.copyOfRange(0, CHUNK * CHUNKS))
            }
        }
        file.writeBytes(bytes)
    }

    /** Reads every chunk through the file's own offset tables and checks its marker. */
    fun checkChunks(file: File, layout: Layout, tracks: Int = 1) {
        val bytes = file.readBytes()
        val top = boxes(bytes, 0, bytes.size)
        if (layout == Layout.FRAGMENTED) {
            val moof = top.single { it.type == "moof" }
            val traf = boxes(bytes, moof.content, moof.end).single { it.type == "traf" }
            val trun = boxes(bytes, traf.content, traf.end).single { it.type == "trun" }
            val offset = moof.start + ByteBuffer.wrap(bytes).getInt(trun.content + 8)
            for (c in 0 until CHUNKS) expect(bytes, offset + c * CHUNK, marker(0, c))
            return
        }
        val moov = top.single { it.type == "moov" }
        val traks = boxes(bytes, moov.content, moov.end).filter { it.type == "trak" }
        check(traks.size == tracks) { "expected $tracks tracks, found ${traks.size}" }
        for ((t, trak) in traks.withIndex()) {
            val stbl = path(bytes, trak, "mdia", "minf", "stbl")
            val table = boxes(bytes, stbl.content, stbl.end).single { it.type == "stco" || it.type == "co64" }
            val buffer = ByteBuffer.wrap(bytes)
            for (c in 0 until CHUNKS) {
                val offset = if (table.type == "co64") {
                    buffer.getLong(table.content + 8 + c * 8).toInt()
                } else {
                    buffer.getInt(table.content + 8 + c * 4)
                }
                expect(bytes, offset, marker(t, c))
            }
        }
    }

    private fun expect(bytes: ByteArray, offset: Int, marker: Byte) {
        val chunk = bytes.copyOfRange(offset, offset + CHUNK)
        check(chunk.all { it == marker }) { "chunk at $offset does not hold marker $marker" }
    }

    private class B(val type: String, val start: Int, val content: Int, val end: Int)

    private fun boxes(bytes: ByteArray, from: Int, to: Int): List<B> {
        val result = mutableListOf<B>()
        var pos = from
        while (pos + 8 <= to) {
            val size = ByteBuffer.wrap(bytes).getInt(pos)
            result += B(String(bytes, pos + 4, 4, Charsets.ISO_8859_1), pos, pos + 8, pos + size)
            pos += size
        }
        return result
    }

    private fun path(bytes: ByteArray, from: B, vararg types: String): B =
        types.fold(from) { box, type -> boxes(bytes, box.content, box.end).single { it.type == type } }

    // --- building ---

    private fun moov(tracks: Int, co64: Boolean, dataStart: Long?): ByteArray {
        val traks = (0 until tracks).map { t ->
            val offsets = if (dataStart == null) emptyList() else (0 until CHUNKS).map { c -> dataStart + (t * CHUNKS + c) * CHUNK }
            trak(t + 1, offsets, co64, fragmented = dataStart == null)
        }
        val mvex = if (dataStart == null) listOf(box("mvex", full("trex", i32(1), i32(1), i32(0), i32(0), i32(0)))) else emptyList()
        return box("moov", mvhd(tracks), *mvex.toTypedArray(), *traks.toTypedArray())
    }

    private fun moof(dataOffset: Int) = box(
        "moof",
        full("mfhd", i32(1)),
        box(
            "traf",
            full("tfhd", i32(1), flags = 0x020000),
            // data offset and sample size present
            full("trun", i32(CHUNKS), i32(dataOffset), *Array(CHUNKS) { i32(CHUNK) }, flags = 0x000201),
        ),
    )

    private fun mvhd(tracks: Int) = full(
        "mvhd", i32(0), i32(0), i32(1000), i32(1000), i32(0x10000), i16(0x100), ByteArray(10), matrix(), ByteArray(24), i32(tracks + 1),
    )

    private fun trak(id: Int, offsets: List<Long>, co64: Boolean, fragmented: Boolean): ByteArray {
        val samples = if (fragmented) 0 else CHUNKS
        val stco = if (co64) {
            full("co64", i32(offsets.size), *offsets.map { i64(it) }.toTypedArray())
        } else {
            full("stco", i32(offsets.size), *offsets.map { i32(it.toInt()) }.toTypedArray())
        }
        val stbl = box(
            "stbl",
            full("stsd", i32(1), mp4a()),
            full("stts", *(if (samples > 0) arrayOf(i32(1), i32(samples), i32(1024)) else arrayOf(i32(0)))),
            full("stsc", *(if (samples > 0) arrayOf(i32(1), i32(1), i32(1), i32(1)) else arrayOf(i32(0)))),
            // same order as ffmpeg; jaudiotagger can't read an empty stco at the very end
            stco,
            full("stsz", i32(0), i32(samples), *Array(samples) { i32(CHUNK) }),
        )
        val minf = box("minf", full("smhd", i32(0)), box("dinf", full("dref", i32(1), full("url ", flags = 1))), stbl)
        val mdia = box(
            "mdia",
            full("mdhd", i32(0), i32(0), i32(44100), i32(44100 * 5), i16(0x55c4), i16(0)),
            full("hdlr", i32(0), "soun".latin(), ByteArray(12), "SoundHandler\u0000".latin()),
            minf,
        )
        val tkhd = full("tkhd", i32(0), i32(0), i32(id), i32(0), i32(5000), ByteArray(8), i16(0), i16(0), i16(0x100), i16(0), matrix(), i32(0), i32(0), flags = 7)
        return box("trak", tkhd, mdia)
    }

    private fun mp4a(): ByteArray {
        val decoderConfig = byteArrayOf(0x40, 0x15, 0, 0, 0) + i32(128000) + i32(128000) + byteArrayOf(0x05, 0x02, 0x12, 0x10)
        val es = byteArrayOf(0, 1, 0) + byteArrayOf(0x04, decoderConfig.size.toByte()) + decoderConfig + byteArrayOf(0x06, 0x01, 0x02)
        val esds = full("esds", byteArrayOf(0x03, es.size.toByte()) + es)
        return box("mp4a", ByteArray(6), i16(1), ByteArray(8), i16(2), i16(16), i16(0), i16(0), i32(44100 shl 16), esds)
    }

    private fun matrix() = i32(0x10000) + i32(0) + i32(0) + i32(0) + i32(0x10000) + i32(0) + i32(0) + i32(0) + i32(0x40000000)

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val content = parts.fold(ByteArray(0)) { acc, part -> acc + part }
        return i32(8 + content.size) + type.latin() + content
    }

    private fun full(type: String, vararg parts: ByteArray, flags: Int = 0) = box(type, i32(flags), *parts)

    private fun i16(v: Int) = ByteBuffer.allocate(2).putShort(v.toShort()).array()
    private fun i32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
    private fun i64(v: Long) = ByteBuffer.allocate(8).putLong(v).array()
    private fun String.latin() = toByteArray(Charsets.ISO_8859_1)
}
