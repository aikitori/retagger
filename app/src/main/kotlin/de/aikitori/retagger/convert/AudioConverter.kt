package de.aikitori.retagger.convert

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import de.aikitori.retagger.core.Resampler
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Receives decoded interleaved 16-bit PCM. */
private interface PcmSink {
    fun write(pcm: ShortArray, frames: Int)
    fun finish()
    fun release()
}

/** Decodes any audio file Android can play (MediaCodec) and encodes it to MP3 (LAME), M4A (AAC) or Opus. */
object AudioConverter {
    private const val TIMEOUT_US = 10_000L

    /**
     * Converts [input] to [output] (both local files). [progress] gets 0..1, [cancelled] is polled
     * regularly and aborts with a [CancellationException].
     */
    fun convert(input: File, output: File, quality: AudioQuality, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var sink: PcmSink? = null
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
                val frames = (if (float) info.size / 4 else info.size / 2) / channels
                if (frames > 0) {
                    val outChannels = minOf(channels, 2)
                    if (sink == null) sink = createSink(output, quality, sampleRate, outChannels)
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
                    sink.write(pcm, frames)
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
            (sink ?: throw IOException("No audio data")).finish()
            progress(1f)
        } finally {
            sink?.release()
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun createSink(output: File, quality: AudioQuality, sampleRate: Int, channels: Int): PcmSink = when (quality.target) {
        Target.MP3 -> Mp3Sink(output, quality, sampleRate, channels)
        // Android's AAC encoder takes at most 48 kHz, its Opus encoder only 48 kHz (and some lower rates)
        Target.M4A -> minOf(sampleRate, 48000).let { rate ->
            resampled(sampleRate, rate, channels) {
                CodecSink(output, MediaFormat.MIMETYPE_AUDIO_AAC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, rate, channels, quality.value)
            }
        }
        Target.OPUS -> resampled(sampleRate, 48000, channels) {
            CodecSink(output, MediaFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, 48000, channels, quality.value)
        }
    }

    private fun resampled(from: Int, to: Int, channels: Int, create: () -> PcmSink): PcmSink {
        val sink = create()
        if (from == to) return sink
        val resampler = Resampler(from, to, channels)
        return object : PcmSink {
            override fun write(pcm: ShortArray, frames: Int) {
                val out = resampler.process(pcm, frames)
                if (out.isNotEmpty()) sink.write(out, out.size / channels)
            }

            override fun finish() {
                val out = resampler.flush()
                if (out.isNotEmpty()) sink.write(out, out.size / channels)
                sink.finish()
            }

            override fun release() = sink.release()
        }
    }
}

/** MP3 with LAME, including the Xing/LAME header for exact VBR lengths. */
private class Mp3Sink(output: File, quality: AudioQuality, sampleRate: Int, channels: Int) : PcmSink {
    private val out = RandomAccessFile(output, "rw").apply { setLength(0) }
    private val encoder = Mp3Encoder.create(sampleRate, channels, quality.vbr, quality.value)
        .also { if (it == 0L) throw IOException("LAME does not support $sampleRate Hz / $channels channels") }
    private var buffer = ByteArray(7200)

    override fun write(pcm: ShortArray, frames: Int) {
        val needed = (frames * 1.25).toInt() + 7200
        if (buffer.size < needed) buffer = ByteArray(needed)
        val written = Mp3Encoder.encode(encoder, pcm, frames, buffer)
        if (written < 0) throw IOException("LAME error $written")
        out.write(buffer, 0, written)
    }

    override fun finish() {
        out.write(buffer, 0, Mp3Encoder.flush(encoder, buffer))
        // LAME reserved the first frame for the Xing/LAME header, fill it in now
        val tag = Mp3Encoder.lameTag(encoder, buffer)
        if (tag > 0) {
            out.seek(0)
            out.write(buffer, 0, tag)
        }
    }

    override fun release() {
        Mp3Encoder.close(encoder)
        out.close()
    }
}

/** Android's own encoder (AAC, Opus) into a container written by MediaMuxer (MP4, Ogg). */
private class CodecSink(output: File, mime: String, container: Int, private val sampleRate: Int, private val channels: Int, kbps: Int) : PcmSink {
    private val encoder = MediaCodec.createEncoderByType(mime)
    private val muxer = MediaMuxer(output.path, container)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var framesIn = 0L
    private var done = false

    init {
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
    }

    override fun write(pcm: ShortArray, frames: Int) {
        var offset = 0 // in samples
        val total = frames * channels
        while (offset < total) {
            val index = encoder.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain()
                continue
            }
            val buffer = encoder.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            buffer.clear()
            // whole frames only
            val count = minOf(total - offset, buffer.capacity() / 2 / channels * channels)
            buffer.asShortBuffer().put(pcm, offset, count)
            encoder.queueInputBuffer(index, 0, count * 2, framesIn * 1_000_000 / sampleRate, 0)
            framesIn += count / channels
            offset += count
            drain()
        }
    }

    override fun finish() {
        while (true) {
            val index = encoder.dequeueInputBuffer(10_000)
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, framesIn * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain()
        }
        while (!done) drain(wait = true)
        muxer.stop()
    }

    private fun drain(wait: Boolean = false) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && track >= 0) {
                        muxer.writeSampleData(track, encoder.getOutputBuffer(index)!!, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        done = true
                        return
                    }
                }
                else -> return
            }
        }
    }

    override fun release() {
        runCatching { encoder.stop() }
        encoder.release()
        runCatching { muxer.release() }
    }
}
