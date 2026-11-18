package io.embodit.windchime.domain

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * v0.1.4 A/B sample (profile D55 补: a new sound pipeline must not break up and must not sound worse than the last
 * version). One fixed 10 s gale on the brass chime (ChimeSim seed 7, 3 s warm-up) is written as an event list
 * (build/ab/gale10-events.csv); this test renders it through the current [Mixer] with the real CC0 samples
 * (build/ab/gale10-v<version>.wav). The v0.1.2 tag renders the same CSV with its own Mixer (tools/ab_render.sh), and
 * tools/ab_compare.py measures both. Also times the render: microseconds per device burst (192 frames).
 */
class GaleABTest {
    private val sr = 44100
    private val snd = File("src/main/assets/sounds")
    private val out = File("build/ab").apply { mkdirs() }
    private val cache = HashMap<String, Mixer.Sample>()

    private fun ffmpeg(): Boolean = runCatching { ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor() == 0 }.getOrDefault(false)
    private fun decode(f: File): Mixer.Sample {
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", f.path, "-ac", "1", "-ar", "$sr", "-f", "s16le", "-").start()
        val b = p.inputStream.readBytes(); p.waitFor()
        val sb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return Mixer.Sample(ShortArray(sb.remaining()).also { sb.get(it) })
    }
    private fun sample(kind: ChimeKind, tube: Int, v: Int) = cache.getOrPut("$kind$tube$v") { decode(File(snd, "${kind.id}_${tube + 1}_${v + 1}.ogg")) }

    private fun wav(file: File, y: FloatArray) {
        val n = y.size
        val bb = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(sr).putInt(sr * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (v in y) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        file.writeBytes(bb.array())
    }

    @Test fun galeTenSecondsABRender() {
        assumeTrue("ffmpeg on PATH", ffmpeg())
        val kind = ChimeKind.BRASS
        val s = ChimeSim(kind, 7); s.windTarget = Phys.WIND_MEAN[3]; s.run(3f)
        val t0 = s.time
        val ev = ArrayList<Triple<Double, Int, Float>>()
        s.run(10f, 90) { st -> ev += Triple(st.t - t0, st.tube, st.volume) }
        File(out, "gale10-events.csv").writeText("t,tube,volume\n" + ev.joinToString("\n") { "${it.first},${it.second},${it.third}" } + "\n")

        val block = 240 // same harness block as the v0.1.2 render, so the onsets land on the same frames
        val total = (10.0 + 2.0).let { (it * sr).toInt() }
        val y = FloatArray(total * 2)
        val m = Mixer(sr); val buf = FloatArray(block * 2); val next = HashMap<Int, Int>()
        var f = 0; var k = 0
        while (f + block <= total) {
            while (k < ev.size && ev[k].first * sr < f + block) {
                val (_, tube, vol) = ev[k]; val v = next[tube] ?: 0; next[tube] = (v + 1) % 2
                m.play(sample(kind, tube, v), vol, Mixer.tubePan(tube), "${kind.id}:$tube"); k++
            }
            m.render(buf, block); System.arraycopy(buf, 0, y, f * 2, block * 2); f += block
        }
        wav(File(out, "gale10-v0.1.4.wav"), y)
        assertTrue(y.maxOf { abs(it) } <= Mixer.CEILING + 1e-6f)
        File(out, "perf-v0.1.4.txt").writeText(perf(kind, ev))
    }

    /** Render cost per 192-frame burst over the same 10 s (5 warm-up passes for the JIT, then 5 timed). */
    private fun perf(kind: ChimeKind, ev: List<Triple<Double, Int, Float>>): String {
        val block = 192
        val times = ArrayList<Long>()
        repeat(10) { pass ->
            val m = Mixer(sr); val buf = FloatArray(block * 2); val next = HashMap<Int, Int>()
            var f = 0; var k = 0; val total = 12 * sr
            while (f + block <= total) {
                while (k < ev.size && ev[k].first * sr < f + block) {
                    val (_, tube, vol) = ev[k]; val v = next[tube] ?: 0; next[tube] = (v + 1) % 2
                    m.play(sample(kind, tube, v), vol, Mixer.tubePan(tube), "${kind.id}:$tube"); k++
                }
                val a = System.nanoTime(); m.render(buf, block); val d = System.nanoTime() - a
                if (pass >= 5) times += d
                f += block
            }
        }
        times.sort()
        val avg = times.average() / 1000; val p99 = times[(times.size * 0.99).toInt()] / 1000.0; val mx = times.last() / 1000.0
        val budget = block * 1e6 / sr
        return "v0.1.4 Mixer, 10 s brass gale (${ev.size} strikes), JVM on the build Mac: per 192-frame burst avg ${"%.1f".format(avg)} us, p99 ${"%.1f".format(p99)} us, max ${"%.1f".format(mx)} us of a ${"%.0f".format(budget)} us budget (${"%.2f".format(avg / budget * 100)} % load)\n"
    }

    /** Physics cost per 1/360 s step in a gale (the sim thread's load; JVM on the build Mac). */
    @Test fun physicsStepCost() {
        val s = ChimeSim(ChimeKind.BRASS, 7); s.windTarget = Phys.WIND_MEAN[3]; s.run(3f)
        repeat(20_000) { s.step() }
        val n = 72_000
        val a = System.nanoTime(); repeat(n) { s.step() }; val per = (System.nanoTime() - a) / n.toDouble()
        File(out, "physics-v0.1.4.txt").writeText("ChimeSim.step gale: ${"%.2f".format(per / 1000)} us per step, ${"%.3f".format(per * Phys.HZ / 1e7)} % of one core at ${Phys.HZ} Hz (JVM, build Mac)\n")
    }
}
