package io.embodit.windchime.domain

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/**
 * QA #1 q1-1 acceptance with the app's real CC0 samples and the app's own [Mixer] (decoded with ffmpeg, the same
 * decoder tools/loudness.py uses): 10 taps on the tiles (5 up, 5 down) at 250 / 400 ms per chime, every attack peak
 * within 1 dB of the others and <= -1 dBFS. Also renders 30 s of gale per chime through the same bus.
 * Writes build/loudness/melody-<chime>-<ms>ms.wav, gale-<chime>.wav and melody-peaks.txt for tools/loudness.py.
 */
class MixerRenderTest {
    private val sr = 44100
    private val snd = File("src/main/assets/sounds")
    private val out = File("build/loudness").apply { mkdirs() }

    private fun ffmpeg(): Boolean = runCatching { ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor() == 0 }.getOrDefault(false)

    private fun decode(f: File): Mixer.Sample {
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", f.path, "-ac", "1", "-ar", "$sr", "-f", "s16le", "-").start()
        val b = p.inputStream.readBytes(); p.waitFor()
        val sb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return Mixer.Sample(ShortArray(sb.remaining()).also { sb.get(it) })
    }

    private val cache = HashMap<String, Mixer.Sample>()
    private fun sample(kind: ChimeKind, tube: Int, v: Int) = cache.getOrPut("$kind$tube$v") { decode(File(snd, "${kind.id}_${tube + 1}_${v + 1}.ogg")) }

    /** Renders strikes (time s, tube, volume) the way SoundPlayer plays them (variant alternation, pan, key). */
    private fun render(kind: ChimeKind, events: List<Triple<Double, Int, Float>>, secs: Double): Pair<FloatArray, List<Int>> {
        val m = Mixer(sr)
        val total = (secs * sr).toInt()
        val y = FloatArray(total * 2)
        val block = 240; val buf = FloatArray(block * 2)
        val next = HashMap<Int, Int>()
        val starts = ArrayList<Int>()
        var f = 0; var k = 0
        while (f + block <= total) {
            while (k < events.size && events[k].first * sr < f + block) {
                val (_, tube, vol) = events[k]
                val v = next[tube] ?: 0; next[tube] = (v + 1) % 2
                m.play(sample(kind, tube, v), vol, Mixer.tubePan(tube), "${kind.id}:$tube")
                starts += f + Mixer.LATENCY; k++
            }
            m.render(buf, block); System.arraycopy(buf, 0, y, f * 2, block * 2); f += block
        }
        return y to starts
    }

    private fun attackPeaks(y: FloatArray, onsets: List<Int>) = onsets.map { o ->
        var p = 0f
        for (i in o until minOf(o + (0.1 * sr).toInt(), y.size / 2)) p = maxOf(p, abs(y[2 * i]), abs(y[2 * i + 1]))
        20 * log10(p.toDouble())
    }

    private fun wav(file: File, y: FloatArray) {
        val n = y.size
        val bb = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(sr).putInt(sr * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (v in y) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        file.writeBytes(bb.array())
    }

    @Test fun tenTapMelodiesStayWithinOneDb() {
        assumeTrue("ffmpeg on PATH", ffmpeg())
        val tap = Strike(0, Phys.TAP_SOUND_V, Strike.Source.TOKEN).volume // what a tile tap plays at 100 % master
        val seq = listOf(0, 1, 2, 3, 4, 4, 3, 2, 1, 0)
        val report = StringBuilder("# v0.1.1 Mixer (tail duck + look-ahead limiter -1 dBFS), real CC0 samples, tile tap volume ${"%.3f".format(tap)}, attack window 100 ms\n")
        for (k in ChimeKind.entries) for (ms in listOf(250, 400)) {
            val ev = seq.mapIndexed { i, t -> Triple(0.2 + i * ms / 1000.0, t, tap) }
            val (y, on) = render(k, ev, 0.2 + 10 * ms / 1000.0 + 2.0)
            val pk = attackPeaks(y, on)
            val spread = pk.max() - pk.min()
            report.append("${k.id} ${ms}ms 10-tile melody: attack peaks dBFS ${pk.map { "%.2f".format(it) }} | spread ${"%.2f".format(spread)} dB | max ${"%.2f".format(pk.max())}\n")
            wav(File(out, "melody-${k.id}-${ms}ms.wav"), y)
            assertTrue("${k.id} $ms ms spread $spread: $pk", spread <= 1.0)
            assertTrue("${k.id} $ms ms max ${pk.max()}", pk.max() <= -1.0 + 1e-3)
        }
        File(out, "melody-peaks.txt").writeText(report.toString())
    }

    @Test fun galeThroughTheBus() {
        assumeTrue("ffmpeg on PATH", ffmpeg())
        for (k in ChimeKind.entries) {
            val s = ChimeSim(k, 7); s.windTarget = Phys.WIND_MEAN[3]; s.run(3f)
            val t0 = s.time
            val ev = ArrayList<Triple<Double, Int, Float>>()
            s.run(30f, 90) { st -> ev += Triple(st.t - t0, st.tube, st.volume) }
            val (y, _) = render(k, ev, 30.0)
            wav(File(out, "gale-${k.id}.wav"), y)
            assertTrue(y.maxOf { abs(it) } <= Mixer.CEILING + 1e-6f)
        }
    }
}
