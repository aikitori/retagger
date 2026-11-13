package de.aikitori.retagger.core

import org.jaudiotagger.tag.images.AndroidArtwork

/** Width and height from the header of PNG and JPEG files, without decoding the image. */
object ImageSize {
    fun of(data: ByteArray): Pair<Int, Int>? = png(data) ?: jpeg(data)

    private fun u8(data: ByteArray, i: Int) = data[i].toInt() and 0xff
    private fun u16(data: ByteArray, i: Int) = (u8(data, i) shl 8) or u8(data, i + 1)
    private fun u32(data: ByteArray, i: Int) = (u16(data, i) shl 16) or u16(data, i + 2)

    private fun png(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 24 || u8(data, 0) != 0x89 || data[1] != 'P'.code.toByte()) return null
        return u32(data, 16) to u32(data, 20)
    }

    private fun jpeg(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 4 || u8(data, 0) != 0xff || u8(data, 1) != 0xd8) return null
        var i = 2
        while (i + 9 < data.size) {
            if (u8(data, i) != 0xff) return null
            val marker = u8(data, i + 1)
            // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC)
            if (marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                return u16(data, i + 7) to u16(data, i + 5)
            }
            i += 2 + u16(data, i + 2)
        }
        return null
    }
}

/**
 * Artwork for jaudiotagger that reads the image size itself. The built-in variants need
 * javax.imageio for that (missing on Android) or throw (FLAC and Vorbis).
 */
internal class SizedArtwork(data: ByteArray, mime: String) : AndroidArtwork() {
    init {
        binaryData = data
        mimeType = mime
        pictureType = 3 // front cover
        setImageFromData()
    }

    override fun setImageFromData(): Boolean {
        val (w, h) = ImageSize.of(binaryData) ?: (0 to 0)
        width = w
        height = h
        return true
    }
}
