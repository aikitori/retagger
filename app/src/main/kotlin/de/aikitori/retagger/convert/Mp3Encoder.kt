package de.aikitori.retagger.convert

/** Thin wrapper around LAME (see src/main/cpp). */
object Mp3Encoder {
    init {
        System.loadLibrary("lamejni")
    }

    /** Returns a handle, 0 if LAME rejects the settings. [value] is the VBR quality (0–9) or the bitrate in kbps. */
    external fun create(sampleRate: Int, channels: Int, vbr: Boolean, value: Int): Long

    /** Encodes [frames] interleaved 16-bit frames from [pcm]; returns the number of bytes written to [out]. */
    external fun encode(handle: Long, pcm: ShortArray, frames: Int, out: ByteArray): Int

    external fun flush(handle: Long, out: ByteArray): Int

    /** The final Xing/LAME header frame, to be written over the first frame of the stream. */
    external fun lameTag(handle: Long, out: ByteArray): Int

    external fun close(handle: Long)
}
