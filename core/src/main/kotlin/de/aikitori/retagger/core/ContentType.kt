package de.aikitori.retagger.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Recognises the real format of a file from its first bytes, independent of the extension.
 * Download tools sometimes save M4A audio as ".mp3"; tagging such a file as MP3 breaks it.
 */
object ContentType {
    private val FAMILIES = listOf(
        setOf("m4a", "m4b", "mp4", "m4p"),
        setOf("ogg", "oga", "opus"),
        setOf("aif", "aiff", "aifc"),
    )

    /** The usual extension for the content, e.g. "m4a"; null if unknown. Leading ID3v2 tags are skipped. */
    fun detect(file: File): String? = RandomAccessFile(file, "r").use { raf ->
        val start = id3Prefix(raf)
        if (raf.length() - start < 36) return null
        val head = ByteArray(36)
        raf.seek(start)
        raf.readFully(head)
        fun text(from: Int, to: Int) = String(head, from, to - from, Charsets.ISO_8859_1)
        when {
            text(4, 8) == "ftyp" -> "m4a"
            text(0, 4) == "OggS" -> if (text(28, 36) == "OpusHead") "opus" else "ogg"
            text(0, 4) == "fLaC" -> "flac"
            text(0, 4) == "RIFF" && text(8, 12) == "WAVE" -> "wav"
            text(0, 4) == "FORM" -> "aiff"
            text(0, 4) == "DSD " -> "dsf"
            head[0] == 0x30.toByte() && head[1] == 0x26.toByte() && head[2] == 0xb2.toByte() -> "wma"
            head[0] == 0xff.toByte() && head[1].toInt() and 0xe0 == 0xe0 -> "mp3"
            else -> null
        }
    }

    /** True if [extension] fits [type] (e.g. "m4b" for "m4a"). */
    fun matches(type: String, extension: String): Boolean {
        val ext = extension.lowercase()
        return ext == type || FAMILIES.any { type in it && ext in it }
    }

    /**
     * Removes tags that don't belong to a non-MP3 file: ID3v2 tags in front and an ID3v1 tag at the end
     * of an MP4. The audio itself stays byte for byte the same. Returns true if the file was changed.
     */
    fun repair(file: File): Boolean {
        val type = detect(file) ?: return false
        if (type == "mp3") return false
        val (start, end) = RandomAccessFile(file, "r").use { raf ->
            val start = id3Prefix(raf)
            var end = raf.length()
            if (type == "m4a" && end - start > 128 && hasId3v1(raf) && mp4EndsAt(raf, start, end - 128)) end -= 128
            start to end
        }
        if (start == 0L && end == file.length()) return false
        val temp = File(file.parentFile, file.name + ".repair")
        try {
            RandomAccessFile(file, "r").use { raf ->
                temp.outputStream().buffered().use { out ->
                    raf.seek(start)
                    val buffer = ByteArray(1 shl 16)
                    var left = end - start
                    while (left > 0) {
                        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        out.write(buffer, 0, n)
                        left -= n
                    }
                }
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        return true
    }

    /** Length of all ID3v2 tags at the start of the file. */
    private fun id3Prefix(raf: RandomAccessFile): Long {
        var pos = 0L
        val header = ByteArray(10)
        while (pos + 10 <= raf.length()) {
            raf.seek(pos)
            raf.readFully(header)
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) break
            val size = (6 until 10).fold(0L) { acc, i -> (acc shl 7) or (header[i].toLong() and 0x7f) }
            pos += 10 + size + if (header[5].toInt() and 0x10 != 0) 10 else 0
        }
        return pos
    }

    private fun hasId3v1(raf: RandomAccessFile): Boolean {
        val tag = ByteArray(3)
        raf.seek(raf.length() - 128)
        raf.readFully(tag)
        return String(tag, Charsets.ISO_8859_1) == "TAG"
    }

    /** True if the top-level MP4 atoms from [start] end exactly at [end]. */
    private fun mp4EndsAt(raf: RandomAccessFile, start: Long, end: Long): Boolean {
        var pos = start
        val header = ByteArray(16)
        while (pos + 8 <= end) {
            raf.seek(pos)
            raf.readFully(header, 0, 8)
            var size = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xffffffffL
            if (size == 1L) {
                raf.readFully(header, 8, 8)
                size = ByteBuffer.wrap(header, 8, 8).long
            }
            if (size < 8) return false
            pos += size
        }
        return pos == end
    }
}
