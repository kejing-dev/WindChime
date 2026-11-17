package io.embodit.windchime.domain

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/**
 * QA #2 q2-1 acceptance (based on QA's QaStressTest.kt.txt): the same tube struck 10 times, every tube x every chime
 * x 100 / 150 / 250 / 400 ms, through the app's own [Mixer] with the real CC0 samples (variant alternation, pan, key as
 * SoundPlayer plays them). Attack peaks (100 ms window) spread < 1 dB and the whole bus stays <= -1.5 dBFS (never at
 * the -1 dBFS limiter). Writes build/loudness/stress-v0.1.3.txt.
 */
class MixerStressTest {
    private val sr = 44100
    private val snd = File("src/main/assets/sounds")
    private val cache = HashMap<String, Mixer.Sample>()

    private fun ffmpeg(): Boolean = runCatching { ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor() == 0 }.getOrDefault(false)

    private fun decode(f: File): Mixer.Sample {
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", f.path, "-ac", "1", "-ar", "$sr", "-f", "s16le", "-").start()
        val b = p.inputStream.readBytes(); p.waitFor()
        val sb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return Mixer.Sample(ShortArray(sb.remaining()).also { sb.get(it) })
    }
    private fun sample(kind: ChimeKind, tube: Int, v: Int) = cache.getOrPut("$kind$tube$v") { decode(File(snd, "${kind.id}_${tube + 1}_${v + 1}.ogg")) }

    private fun render(kind: ChimeKind, events: List<Triple<Double, Int, Float>>, secs: Double): Pair<FloatArray, List<Int>> {
        val m = Mixer(sr); val total = (secs * sr).toInt(); val y = FloatArray(total * 2)
        val block = 240; val buf = FloatArray(block * 2); val next = HashMap<Int, Int>(); val starts = ArrayList<Int>()
        var f = 0; var k = 0
        while (f + block <= total) {
            while (k < events.size && events[k].first * sr < f + block) {
                val (_, tube, vol) = events[k]; val v = next[tube] ?: 0; next[tube] = (v + 1) % 2
                m.play(sample(kind, tube, v), vol, Mixer.tubePan(tube), "${kind.id}:$tube"); starts += f + Mixer.LATENCY; k++
            }
            m.render(buf, block); System.arraycopy(buf, 0, y, f * 2, block * 2); f += block
        }
        return y to starts
    }

    private fun peaks(y: FloatArray, on: List<Int>) = on.map { o -> var p = 0f
        for (i in o until minOf(o + (0.1 * sr).toInt(), y.size / 2)) p = maxOf(p, abs(y[2 * i]), abs(y[2 * i + 1])); 20 * log10(p.toDouble()) }

    @Test fun sameTubeTenStrikesEveryTubeEveryChime() {
        assumeTrue("ffmpeg on PATH", ffmpeg())
        val tap = Strike(0, Phys.TAP_SOUND_V, Strike.Source.TOKEN).volume
        val sb = StringBuilder("# v0.1.3 same-tube x10 stress (app Mixer + real CC0 samples, rendered), tap volume ${"%.3f".format(tap)}, attack window 100 ms\n")
        sb.append("# pass: spread < 1.00 dB and bus peak <= -1.50 dBFS\n")
        val fails = ArrayList<String>()
        var worstSpread = 0.0; var worstBus = -99.0
        for (k in ChimeKind.entries) for (tube in 0 until 5) for (ms in listOf(100, 150, 250, 400)) {
            val ev = (0 until 10).map { Triple(0.2 + it * ms / 1000.0, tube, tap) }
            val (y, on) = render(k, ev, 0.2 + 10 * ms / 1000.0 + 2.0)
            val pk = peaks(y, on); val spread = pk.max() - pk.min()
            val bus = 20 * log10(y.maxOf { abs(it) }.toDouble())
            worstSpread = maxOf(worstSpread, spread); worstBus = maxOf(worstBus, bus)
            val ok = spread < 1.0 && bus <= -1.5
            val line = "${k.id} tube ${tube + 1} x10 @${ms}ms: spread ${"%.2f".format(spread)} dB | peaks ${pk.map { "%.2f".format(it) }} | bus ${"%.2f".format(bus)} dBFS | ${if (ok) "PASS" else "FAIL"}"
            sb.append(line).append('\n'); if (!ok) fails += line
        }
        sb.append("# worst spread ${"%.2f".format(worstSpread)} dB, worst bus peak ${"%.2f".format(worstBus)} dBFS, ${60 - fails.size}/60 PASS\n")
        File("build/loudness").apply { mkdirs() }.resolve("stress-v0.1.3.txt").writeText(sb.toString())
        print(sb)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
