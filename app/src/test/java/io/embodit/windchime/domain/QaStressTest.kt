package io.embodit.windchime.domain

import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/** QA #2 independent stress: same tube 10x, faster tapping, solo vs mixed, discontinuities (clicks). */
class QaStressTest {
    private val sr = 44100
    private val snd = File("src/main/assets/sounds")
    private val cache = HashMap<String, Mixer.Sample>()
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
    private fun peaks(y: FloatArray, on: List<Int>, win: Double = 0.1) = on.map { o -> var p = 0f
        for (i in o until minOf(o + (win * sr).toInt(), y.size / 2)) p = maxOf(p, abs(y[2 * i]), abs(y[2 * i + 1])); 20 * log10(p.toDouble()) }

    @Test fun stress() {
        val tap = Strike(0, Phys.TAP_SOUND_V, Strike.Source.TOKEN).volume
        val sb = StringBuilder("# QA #2 stress (app Mixer + real CC0 samples, rendered, not recorded), tap volume ${"%.3f".format(tap)}\n")
        for (k in ChimeKind.entries) {
            // solo peak of each tube / variant
            val solo = (0 until 5).map { t -> (0..1).map { v -> val (y, on) = render(k, listOf(Triple(0.2, t, tap)), 1.5); peaks(y, on)[0] } }
            sb.append("${k.id} solo peaks per tube [v1,v2]: ${solo.map { it.map { "%.2f".format(it) } }}\n")
            for (tube in listOf(0, 2, 4)) for (ms in listOf(100, 150, 250, 400)) {
                val ev = (0 until 10).map { Triple(0.2 + it * ms / 1000.0, tube, tap) }
                val (y, on) = render(k, ev, 0.2 + 10 * ms / 1000.0 + 2.0)
                val pk = peaks(y, on); val mx = y.maxOf { abs(it) }
                var jump = 0f; for (i in 1 until y.size / 2) jump = maxOf(jump, abs(y[2 * i] - y[2 * i - 2]))
                sb.append("${k.id} same tube ${tube + 1} x10 @${ms}ms: spread ${"%.2f".format(pk.max() - pk.min())} dB max ${"%.2f".format(pk.max())} min ${"%.2f".format(pk.min())} | bus peak ${"%.2f".format(20 * log10(mx.toDouble()))} dBFS | max step ${"%.3f".format(jump)}\n")
            }
            for (ms in listOf(100, 150)) {
                val seq = listOf(0, 1, 2, 3, 4, 4, 3, 2, 1, 0)
                val ev = seq.mapIndexed { i, t -> Triple(0.2 + i * ms / 1000.0, t, tap) }
                val (y, on) = render(k, ev, 0.2 + 10 * ms / 1000.0 + 2.0)
                val pk = peaks(y, on); val rel = seq.mapIndexed { i, t -> pk[i] - solo[t][(seq.take(i).count { it == t }) % 2] }
                sb.append("${k.id} 10-tile melody @${ms}ms: spread ${"%.2f".format(pk.max() - pk.min())} dB max ${"%.2f".format(pk.max())} | vs own solo ${rel.map { "%+.2f".format(it) }}\n")
            }
        }
        for (k in ChimeKind.entries) {
            var sj = 0f; for (t in 0 until 5) { val pcm = sample(k, t, 0).pcm; for (i in 1 until pcm.size) sj = maxOf(sj, abs(pcm[i] - pcm[i - 1]) / 32768f * tap) }
            sb.append("${k.id} solo sample max step x tap vol: ${"%.3f".format(sj)}\n")
            for (tube in listOf(1, 3)) for (ms in listOf(250, 300)) {
                val ev = (0 until 10).map { Triple(0.2 + it * ms / 1000.0, tube, tap) }
                val (y, on) = render(k, ev, 0.2 + 10 * ms / 1000.0 + 2.0); val pk = peaks(y, on)
                sb.append("${k.id} same tube ${tube + 1} x10 @${ms}ms: spread ${"%.2f".format(pk.max() - pk.min())} dB peaks ${pk.map { "%.2f".format(it) }}\n")
            }
        }
        run { val ev = (0 until 10).map { Triple(0.2 + it * 0.25, 0, Strike(0, Phys.TAP_SOUND_V, Strike.Source.TOKEN).volume) }
            val (y, on) = render(ChimeKind.entries.first { it.id == "brass" }, ev, 4.7); val pk = peaks(y, on)
            sb.append("brass same tube 1 x10 @250ms peaks ${pk.map { "%.2f".format(it) }} (wav written)\n")
            val n = y.size; val bb = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sr).putInt(sr * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 2)
            for (v in y) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort()); File("build/wind-chime-brass-tube1-x10-250ms.wav").writeBytes(bb.array()) }
        File("build/qa-stress.txt").apply { parentFile.mkdirs() }.writeText(sb.toString())
        print(sb)
    }
}
