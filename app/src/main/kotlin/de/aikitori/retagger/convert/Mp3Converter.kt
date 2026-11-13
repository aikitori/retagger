package de.aikitori.retagger.convert

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

enum class Mp3Quality(val vbr: Boolean, val value: Int) {
    VBR_V0(true, 0),
    VBR_V2(true, 2),
    CBR_320(false, 320),
    CBR_192(false, 192),
    CBR_128(false, 128),
}

/** Decodes any audio file Android can play (MediaCodec) and encodes it to MP3 with LAME. */
object Mp3Converter {
    private const val TIMEOUT_US = 10_000L

    /**
     * Converts [input] to [output] (both local files). [progress] gets 0..1, [cancelled] is polled
     * regularly and aborts with a [CancellationException].
     */
    fun convert(input: File, output: File, quality: Mp3Quality, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var encoder = 0L
        try {
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            RandomAccessFile(output, "rw").use { out ->
                out.setLength(0)
                var mp3 = ByteArray(0)
                var pcm = ShortArray(0)
                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var float = false
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                var lastProgress = -1f

                while (true) {
                    if (cancelled()) throw CancellationException()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // the real PCM format, e.g. HE-AAC decodes to twice the container's sample rate
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        continue
                    }
                    if (index < 0) continue

                    val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val samples = if (float) info.size / 4 else info.size / 2
                    val frames = samples / channels
                    if (frames > 0) {
                        if (encoder == 0L) {
                            encoder = Mp3Encoder.create(sampleRate, minOf(channels, 2), quality.vbr, quality.value)
                            if (encoder == 0L) throw IOException("LAME does not support $sampleRate Hz / $channels channels")
                        }
                        val outChannels = minOf(channels, 2)
                        if (pcm.size < frames * outChannels) pcm = ShortArray(frames * outChannels)
                        val input16 = if (float) null else buffer.asShortBuffer()
                        val inputF = if (float) buffer.asFloatBuffer() else null
                        // keep front left/right of multichannel audio
                        for (f in 0 until frames) {
                            for (c in 0 until outChannels) {
                                val i = f * channels + c
                                pcm[f * outChannels + c] = input16?.get(i)
                                    ?: (inputF!!.get(i).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                            }
                        }
                        val needed = (frames * 1.25).toInt() + 7200
                        if (mp3.size < needed) mp3 = ByteArray(needed)
                        val written = Mp3Encoder.encode(encoder, pcm, frames, mp3)
                        if (written < 0) throw IOException("LAME error $written")
                        out.write(mp3, 0, written)
                    }
                    codec.releaseOutputBuffer(index, false)

                    if (duration > 0) {
                        val p = (info.presentationTimeUs.toFloat() / duration).coerceIn(0f, 1f)
                        if (p - lastProgress >= 0.01f) {
                            lastProgress = p
                            progress(p)
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }

                if (encoder == 0L) throw IOException("No audio data")
                if (mp3.size < 7200) mp3 = ByteArray(7200)
                out.write(mp3, 0, Mp3Encoder.flush(encoder, mp3))
                // LAME reserved the first frame for the Xing/LAME header, fill it in now
                val tag = Mp3Encoder.lameTag(encoder, mp3)
                if (tag > 0) {
                    out.seek(0)
                    out.write(mp3, 0, tag)
                }
                progress(1f)
            }
        } finally {
            if (encoder != 0L) Mp3Encoder.close(encoder)
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
