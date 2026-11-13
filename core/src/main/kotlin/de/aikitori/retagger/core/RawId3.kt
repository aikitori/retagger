package de.aikitori.retagger.core

import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Reads the ID3v2 tag at the start of an MP3 directly. jaudiotagger's normal path refuses files
 * in which it cannot find a valid MPEG frame right after the tag, even though the tag itself is fine.
 */
internal object RawId3 {
    class Result(val tag: AbstractID3v2Tag?, val audioStart: Long)

    fun read(file: File): Result = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 10) return Result(null, 0)
        val header = ByteArray(10)
        raf.readFully(header)
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return Result(null, 0)
        }
        val footer = if (header[5].toInt() and 0x10 != 0) 10 else 0
        val size = 10 + syncsafe(header, 6) + footer
        val bytes = ByteArray(size)
        raf.seek(0)
        raf.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes)
        val tag = when (header[3].toInt()) {
            2 -> ID3v22Tag(buffer, file.name)
            3 -> ID3v23Tag(buffer, file.name)
            4 -> ID3v24Tag(buffer, file.name)
            else -> null
        }
        Result(tag, size.toLong())
    }

    private fun syncsafe(data: ByteArray, at: Int) =
        (0 until 4).fold(0) { acc, i -> (acc shl 7) or (data[at + i].toInt() and 0x7f) }
}
