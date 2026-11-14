package de.aikitori.retagger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    @ParameterizedTest
    @CsvSource("44100, 48000", "96000, 48000", "22050, 48000")
    fun `keeps length, pitch and level of a sine`(inRate: Int, outRate: Int) {
        val seconds = 2
        val freq = 1000.0
        val frames = inRate * seconds
        val input = ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / inRate
            (10000 * sin(2 * PI * freq * t + if (i % 2 == 1) PI / 2 else 0.0)).toInt().toShort()
        }
        val resampler = Resampler(inRate, outRate, 2)
        // feed in uneven chunks like a decoder does
        val out = mutableListOf<Short>()
        var pos = 0
        val chunks = intArrayOf(1000, 4096, 17, 2048)
        var c = 0
        while (pos < frames) {
            val n = minOf(chunks[c++ % chunks.size], frames - pos)
            out += resampler.process(input.copyOfRange(pos * 2, (pos + n) * 2), n).toList()
            pos += n
        }
        out += resampler.flush().toList()

        val outFrames = out.size / 2
        assertEquals(outRate * seconds.toDouble(), outFrames.toDouble(), 2.0)
        // compare with the ideal sine, leaving out the edges
        var error = 0.0
        var signal = 0.0
        for (f in 100 until outFrames - 100) for (ch in 0..1) {
            val ideal = 10000 * sin(2 * PI * freq * f / outRate + if (ch == 1) PI / 2 else 0.0)
            val e = out[f * 2 + ch] - ideal
            error += e * e
            signal += ideal * ideal
        }
        val relative = sqrt(error / signal)
        assertTrue(relative < 0.01, "relative error $relative")
        assertTrue(abs(out.drop(400).take(2000).maxOf { it.toInt() } - 10000) < 150)
    }
}
