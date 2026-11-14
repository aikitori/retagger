package de.aikitori.retagger.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming sample rate converter for interleaved 16-bit PCM (windowed sinc, polyphase table).
 * Needed because Opus only takes 48 kHz and Android's AAC encoder nothing above 48 kHz.
 */
class Resampler(private val inRate: Int, private val outRate: Int, private val channels: Int) {
    private val taps = 16 // per side
    private val phases = 512
    private val step = inRate.toDouble() / outRate
    // below the lower Nyquist frequency, with a little room for the filter slope
    private val cutoff = 0.95 * min(1.0, outRate.toDouble() / inRate)
    private val table = FloatArray((phases + 1) * 2 * taps).also { t ->
        for (p in 0..phases) {
            val frac = p.toDouble() / phases
            for (k in 0 until 2 * taps) {
                val x = k - taps + 1 - frac // distance from the output position
                val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
                val window = 0.42 + 0.5 * cos(PI * x / taps) + 0.08 * cos(2 * PI * x / taps) // Blackman
                t[p * 2 * taps + k] = (cutoff * sinc * window).toFloat()
            }
        }
    }

    // pending input per channel; history[c][0] is the input frame at index `offset`.
    // It starts with silence "before" the signal so the first samples get full filter support.
    private var history = Array(channels) { FloatArray(4096) }
    private var size = taps
    private var offset = -taps.toLong()
    private var position = 0.0 // next output frame, in input frames

    /** Feeds [frames] interleaved frames, returns the converted frames available so far. */
    fun process(input: ShortArray, frames: Int): ShortArray {
        append(input, frames)
        return produce()
    }

    /** Converts what is left at the end of the stream (pads with silence for the filter). */
    fun flush(): ShortArray {
        append(ShortArray(taps * channels), taps)
        return produce()
    }

    private fun append(input: ShortArray, frames: Int) {
        if (size + frames > history[0].size) {
            val capacity = maxOf(history[0].size * 2, size + frames)
            history = Array(channels) { c -> history[c].copyOf(capacity) }
        }
        for (f in 0 until frames) for (c in 0 until channels) {
            history[c][size + f] = input[f * channels + c].toFloat()
        }
        size += frames
    }

    private fun produce(): ShortArray {
        val out = ShortArray(((size / step).toInt() + 2) * channels)
        var n = 0
        // an output frame needs the input up to floor(position) + taps
        while (floor(position).toLong() + taps < offset + size) {
            val base = floor(position).toLong()
            val phase = ((position - base) * phases).roundToInt()
            val start = (base - taps + 1 - offset).toInt()
            val row = phase * 2 * taps
            for (c in 0 until channels) {
                var sum = 0f
                val h = history[c]
                for (k in 0 until 2 * taps) sum += h[start + k] * table[row + k]
                out[n++] = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            position += step
        }
        // drop input that no future output needs
        val keepFrom = (floor(position).toLong() - taps + 1 - offset).toInt().coerceIn(0, size)
        if (keepFrom > 0) {
            for (c in 0 until channels) System.arraycopy(history[c], keepFrom, history[c], 0, size - keepFrom)
            size -= keepFrom
            offset += keepFrom
        }
        return out.copyOf(n)
    }
}
