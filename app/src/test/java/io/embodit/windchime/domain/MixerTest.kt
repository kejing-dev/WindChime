package io.embodit.windchime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/** The mix bus on synthetic rings (no samples needed): new notes keep their level, the bus never passes -1 dBFS. */
class MixerTest {
    private val sr = 44100

    /** A struck-bar-like ring: 5 ms attack, exponential decay [tau], peak [peak]. */
    private fun ring(hz: Double, tau: Double, peak: Double = 0.891, secs: Double = 4.0) = Mixer.Sample(ShortArray((secs * sr).toInt()) { i ->
        val t = i.toDouble() / sr
        val env = minOf(1.0, t / 0.005) * exp(-t / tau)
        (peak * env * sin(2 * PI * hz * t) * 32767).toInt().toShort()
    })

    private fun renderTaps(samples: List<Mixer.Sample>, gain: Float, everyS: Double, n: Int): Pair<FloatArray, List<Int>> {
        val m = Mixer(sr)
        val total = ((n * everyS + 2.0) * sr).toInt()
        val out = FloatArray(total * 2)
        val block = 240
        val buf = FloatArray(block * 2)
        val onsets = (0 until n).map { (it * everyS * sr).toInt() / block * block }
        var f = 0; var k = 0
        while (f + block <= total) {
            while (k < n && onsets[k] <= f) { m.play(samples[k % samples.size], gain, Mixer.tubePan(k % 5), "t:${k % 5}"); k++ }
            m.render(buf, block)
            System.arraycopy(buf, 0, out, f * 2, block * 2)
            f += block
        }
        return out to onsets.map { it + Mixer.LATENCY }
    }

    private fun attackPeaks(y: FloatArray, onsets: List<Int>): List<Double> = onsets.map { o ->
        var p = 0f
        for (i in o until minOf(o + (0.1 * sr).toInt(), y.size / 2)) p = maxOf(p, abs(y[2 * i]), abs(y[2 * i + 1]))
        20 * log10(p.toDouble())
    }

    @Test fun tenFastTapsStayWithinOneDb() {
        val notes = listOf(523.0, 587.0, 659.0, 784.0, 880.0).map { ring(it, 1.2) } // long brass-like rings
        for (every in listOf(0.25, 0.4)) {
            val (y, on) = renderTaps(notes, 0.855f, every, 10)
            val pk = attackPeaks(y, on)
            val spread = pk.max() - pk.min()
            assertTrue("every $every s: peaks $pk spread $spread", spread <= 1.0)
            assertTrue("peak ${pk.max()}", pk.max() <= -1.0 + 1e-3)
        }
    }

    @Test fun aSingleNotePlaysAtItsOwnLevel() {
        val (y, on) = renderTaps(listOf(ring(659.0, 1.2)), 0.855f, 1.0, 1)
        val expect = 20 * log10(0.891 * 0.855)
        assertEquals(expect, attackPeaks(y, on)[0], 0.15)
    }

    @Test fun limiterHoldsTheCeilingUnderPileUps() {
        // 12 full-level non-duckable notes in 60 ms (worst case: a gust whoosh on top of everything)
        val m = Mixer(sr)
        val s = ring(440.0, 2.0, peak = 0.95)
        repeat(12) { m.play(s, 1f, 0f, "fx:$it", duckable = false) }
        val buf = FloatArray(sr * 2)
        m.render(buf, sr)
        assertTrue(buf.maxOf { abs(it) } <= Mixer.CEILING + 1e-6f)
    }

    @Test fun twoPerTubeAndOlderTailsKeepRinging() {
        val notes = listOf(ring(659.0, 1.2))
        val (y, on) = renderTaps(notes, 0.855f, 0.4, 3)
        // between taps the older note is still audible (ducked, not cut): RMS right before tap 2 is > -40 dBFS
        val a = on[2] - (0.02 * sr).toInt()
        var e = 0.0; for (i in a until on[2] - Mixer.LATENCY) e += (y[2 * i] * y[2 * i]).toDouble()
        val rms = 10 * log10(e / (on[2] - Mixer.LATENCY - a))
        assertTrue("rms $rms", rms > -40)
    }
}
