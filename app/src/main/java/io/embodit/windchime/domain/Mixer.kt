package io.embodit.windchime.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The app's own mix bus (v0.1.1, QA #1 q1-1). Pure Kotlin so the unit test renders exactly what the headset plays.
 *
 * Every new note plays at its own level (strike volume x master volume, never turned down for what is already
 * sounding). Two stages keep the sum in check instead:
 *  1. **Tail duck** (a sidechain on the older notes, "steal the oldest" made soft): at a note's onset the mixer looks at
 *     the next [ATTACK_S] of the new note and of everything still ringing. If the older notes would move the attack
 *     peak of the mix more than [ATTACK_TOL] (about +-0.4 dB) away from the new note's own peak, the older chime notes
 *     are turned down (over [DUCK_RAMP] frames) by the largest factor that keeps it inside. Their tails keep ringing,
 *     only quieter; effects (gust / rustle) are never ducked.
 *  2. **Bus limiter**: look-ahead [LOOKAHEAD] frames, ceiling [CEILING] (-1 dBFS), instant attack, [RELEASE_S] release,
 *     so nothing ever goes over -1 dBFS (gale + gust whoosh included).
 * Polyphony: at most 2 notes per tube (the older one fades over 80 ms) and [MAX_VOICES] in all (the oldest fades).
 *
 * v0.1.2 (QA #2 q2-1): a note that is already fading out (the third strike on one tube steals the oldest copy) used to
 * be counted as "not duckable", i.e. inside the reference level the new note was judged against, and it was never
 * turned down. At the same pitch it added to / cancelled the new attack (1.2-2.7 dB spread, limiter hits). Now every
 * older chime note, fading or not, is on the ducked side; only effects (gust / rustle) stay in the reference.
 * The attack window is now the 100 ms QA measures and the tolerance +-0.21 dB.
 *
 * v0.1.4 (profile D55 补, headset v0.1.3: underruns ~70/s = crackle): the output is unchanged sample for sample, but the
 * render loop is now real-time safe. Producers ([play], [fadeAll]) never take a lock the audio thread holds: notes go
 * through a lock-free inbox and the [Voice] is built on the caller's thread. [render] allocates nothing (indexed loops,
 * preallocated duck buffers, an onset ring instead of boxed Longs) and the look-ahead limiter's window minimum is a
 * monotonic queue (O(1) per frame instead of a 96-entry scan per frame). [renderNanos] reports the cost per block.
 */
class Mixer(val sampleRate: Int = 44100) {
    /** A decoded mono sample (16-bit), with its attack window peak precomputed. */
    class Sample(val pcm: ShortArray) {
        val peak: Float = pcm.maxOfOrNull { abs(it.toInt()) }?.let { it / 32768f } ?: 0f
    }

    private class Voice(val s: Sample, val key: String, val duckable: Boolean, val gl: Float, val gr: Float, val serial: Long) {
        /** The note starts after the older notes have ramped down (samples peak on their first frames). */
        var delay = DUCK_RAMP
        var pos = 0
        var duck = 1f
        var duckTarget = 1f
        var fade = 1f
        var fadeStep = 0f
        val done get() = pos >= s.pcm.size || (fadeStep > 0f && fade <= 0f)
    }

    /** A queued note; [at] = the output frame it starts on (0 = at once). Built by the producer, not the audio thread. */
    private class Cmd(val v: Voice, val at: Long)

    /** Producer -> audio thread (lock-free; the audio thread only polls, which allocates nothing). */
    private val inbox = java.util.concurrent.ConcurrentLinkedQueue<Cmd>()
    /** Audio-thread-only from here down. */
    private val pending = ArrayList<Cmd>(64)
    private val voices = ArrayList<Voice>(MAX_VOICES * 2)
    private val serial = java.util.concurrent.atomic.AtomicLong()
    private val fadeRequests = java.util.concurrent.atomic.AtomicInteger()
    private var fadesSeen = 0
    /** Frames rendered so far: the mixer's own clock for scheduled notes (v0.1.3, D55). Written by the audio thread. */
    @Volatile var clock = 0L
        private set
    private val onsetRing = LongArray(ONSET_LOG)
    private var onsetCount = 0
    /** Onset frame of every note (test hook; the last [ONSET_LOG] only). */
    val onsets: List<Long> get() = (maxOf(0, onsetCount - ONSET_LOG) until onsetCount).map { onsetRing[it % ONSET_LOG] }
    @Volatile private var activeCount = 0

    // limiter state
    private val delayL = FloatArray(LOOKAHEAD)
    private val delayR = FloatArray(LOOKAHEAD)
    private var ring = 0
    // sliding minimum of the required gain over the last LOOKAHEAD frames (monotonic queue: values rise front to back)
    private val qVal = FloatArray(LOOKAHEAD + 1)
    private val qAt = LongArray(LOOKAHEAD + 1)
    private var qHead = 0
    private var qSize = 0
    // duck analysis buffers (one attack window), reused at every onset
    private val attackN = (ATTACK_S * sampleRate).toInt()
    private val newL = FloatArray(attackN); private val newR = FloatArray(attackN)
    private val oldL = FloatArray(attackN); private val oldR = FloatArray(attackN)
    private val fixL = FloatArray(attackN); private val fixR = FloatArray(attackN)
    /** Wall time of the last [render] call and the worst since [takeRenderStats] (ns), for the 5 s MIX log. */
    @Volatile var renderNanos = 0L
        private set
    private var renderNanosMax = 0L
    private var renderNanosSum = 0L
    private var renderCalls = 0
    private var limGain = 1f
    private val releaseCoef = 1f - exp(-1.0 / (RELEASE_S * sampleRate)).toFloat()

    /** Last onset's decision, for the log and the test: duck factor applied to the older notes. */
    @Volatile var lastDuck = 1f
        private set
    /** Lowest limiter gain since the last [takeMinLimiterGain]. */
    private var minLim = 1f

    /** Notes sounding or queued (any thread; approximate between blocks). */
    val active: Int get() = activeCount + if (inbox.isEmpty()) 0 else 1

    /**
     * Queue a note (any thread). [pan] -1..1 (left..right), equal-gain law clipped at 1 per side as before.
     * [delayS] > 0 starts it that much later on the output clock, to the frame (v0.1.3, D55: the physics thread hands
     * over strikes in batches, each placed at its own simulated time instead of all at the batch end).
     */
    fun play(s: Sample, gain: Float, pan: Float = 0f, key: String = "", duckable: Boolean = true, delayS: Float = 0f) {
        if (gain <= 0.0005f) return
        val at = if (delayS <= 0f) 0L else clock + (delayS * sampleRate).toLong()
        val gl = gain * min(1f, 1f - pan)
        val gr = gain * min(1f, 1f + pan)
        inbox.add(Cmd(Voice(s, key, duckable, gl, gr, serial.getAndIncrement()), at))
    }

    /** Fade everything out over 80 ms (mute, background). Any thread; applied at the next block. */
    fun fadeAll() { fadeRequests.incrementAndGet() }

    /** Audio thread (or a test). */
    fun takeMinLimiterGain(): Float { val m = minLim; minLim = 1f; return m }

    /** Average and worst [render] wall time since the last call, in microseconds (audio thread). */
    fun takeRenderStats(): Pair<Long, Long> {
        val avg = if (renderCalls == 0) 0L else renderNanosSum / renderCalls / 1000
        val mx = renderNanosMax / 1000
        renderNanosSum = 0L; renderNanosMax = 0L; renderCalls = 0
        return avg to mx
    }

    /** Takes the producers' notes into the start-frame-sorted [pending] list (stable: equal frames keep call order). */
    private fun drainInbox() {
        val fr = fadeRequests.get()
        if (fr != fadesSeen) {
            fadesSeen = fr
            // notes queued before the fade request are dropped, as before
            while (inbox.poll() != null) { /* drop */ }
            pending.clear()
            for (i in 0 until voices.size) startFade(voices[i])
        }
        while (true) {
            val c = inbox.poll() ?: break
            var i = pending.size
            while (i > 0 && pending[i - 1].at > c.at) i--
            pending.add(i, c)
        }
    }

    /** Renders [frames] interleaved stereo frames into [out] (overwrites). Audio thread only; allocates nothing. */
    fun render(out: FloatArray, frames: Int) {
        val t0 = System.nanoTime()
        drainInbox()
        for (f in 0 until frames) {
            while (pending.isNotEmpty() && pending[0].at <= clock) onset(pending.removeAt(0))
            clock++
            var l = 0f; var r = 0f
            for (vi in 0 until voices.size) {
                val v = voices[vi]
                if (v.delay > 0) { v.delay--; continue }
                if (v.pos >= v.s.pcm.size) continue
                val x = v.s.pcm[v.pos++] / 32768f
                if (v.duck != v.duckTarget) {
                    val step = 1f / DUCK_RAMP
                    v.duck = if (v.duck > v.duckTarget) max(v.duckTarget, v.duck - step) else min(v.duckTarget, v.duck + step)
                }
                if (v.fadeStep > 0f) v.fade = max(0f, v.fade - v.fadeStep)
                val g = v.duck * v.fade
                l += x * v.gl * g; r += x * v.gr * g
            }
            // look-ahead limiter: the frame leaving the delay line gets the smallest gain any frame in the window needs
            val req = min(1f, CEILING / max(1e-9f, max(abs(l), abs(r))))
            val frameNo = clock
            // monotonic queue: drop larger values from the back, expired frames from the front
            while (qSize > 0 && qVal[(qHead + qSize - 1) % (LOOKAHEAD + 1)] >= req) qSize--
            val back = (qHead + qSize) % (LOOKAHEAD + 1)
            qVal[back] = req; qAt[back] = frameNo; qSize++
            while (qAt[qHead] <= frameNo - LOOKAHEAD) { qHead = (qHead + 1) % (LOOKAHEAD + 1); qSize-- }
            val target = min(1f, qVal[qHead])
            limGain = if (target < limGain) target else limGain + (target - limGain) * releaseCoef
            if (limGain < minLim) minLim = limGain
            val ol = delayL[ring]; val or = delayR[ring]
            delayL[ring] = l; delayR[ring] = r
            ring = (ring + 1) % LOOKAHEAD
            out[2 * f] = (ol * limGain).coerceIn(-CEILING, CEILING)
            out[2 * f + 1] = (or * limGain).coerceIn(-CEILING, CEILING)
        }
        // drop finished voices in place (no iterator, no lambda)
        var w = 0
        for (i in 0 until voices.size) { val v = voices[i]; if (!v.done) voices[w++] = v }
        while (voices.size > w) voices.removeAt(voices.size - 1)
        activeCount = voices.size + pending.size
        val dt = System.nanoTime() - t0
        renderNanos = dt; renderNanosSum += dt; renderCalls++
        if (dt > renderNanosMax) renderNanosMax = dt
    }

    private fun startFade(v: Voice) { if (v.fadeStep == 0f) v.fadeStep = 1f / (FADE_S * sampleRate) }

    private fun onset(c: Cmd) {
        val v = c.v
        onsetRing[onsetCount % ONSET_LOG] = clock; onsetCount++
        // polyphony (counted over the live voices, as before; indexed loops, no lists)
        var liveN = 0; var oldestLive: Voice? = null
        var sameN = 0; var oldestSame: Voice? = null
        for (i in 0 until voices.size) {
            val o = voices[i]
            if (o.fadeStep != 0f) continue
            liveN++
            if (oldestLive == null || o.serial < oldestLive.serial) oldestLive = o
            if (v.key.isNotEmpty() && o.key == v.key) {
                sameN++
                if (oldestSame == null || o.serial < oldestSame.serial) oldestSame = o
            }
        }
        if (v.key.isNotEmpty() && sameN >= 2) startFade(oldestSame!!)
        if (liveN >= MAX_VOICES) startFade(oldestLive!!)
        if (v.duckable) lastDuck = duckFor(v)
        voices += v
    }

    /**
     * Picks the duck factor d (1 = untouched) for the older chime notes so the new note's attack window peak of the mix
     * stays within [ATTACK_TOL] of the new note alone. Applied to every older duckable note (multiplicative).
     */
    private fun duckFor(nv: Voice): Float {
        val n = min(attackN, nv.s.pcm.size)
        if (n <= 0) return 1f
        var solo = 0f
        for (i in 0 until n) {
            val x = nv.s.pcm[i] / 32768f
            newL[i] = x * nv.gl; newR[i] = x * nv.gr
            solo = max(solo, max(abs(newL[i]), abs(newR[i])))
        }
        if (solo <= 0f) return 1f
        // oldL/R: duckable older notes (scaled by d); fixL/R: effects only (never ducked, the reference)
        java.util.Arrays.fill(oldL, 0, n, 0f); java.util.Arrays.fill(oldR, 0, n, 0f)
        java.util.Arrays.fill(fixL, 0, n, 0f); java.util.Arrays.fill(fixR, 0, n, 0f)
        var any = false
        for (vi in 0 until voices.size) {
            val v = voices[vi]
            if (v.pos >= v.s.pcm.size) continue
            // fading notes are ducked too (v0.1.2 q2-1): only effects belong in the reference level
            val duckIt = v.duckable
            var fade = v.fade
            // the new note starts DUCK_RAMP frames from now; older notes are at their duck target by then
            val skip = max(0, DUCK_RAMP - v.delay)
            if (v.fadeStep > 0f) fade = max(0f, fade - v.fadeStep * skip)
            for (i in 0 until n) {
                val p = v.pos + skip + i
                if (p < 0) continue
                if (p >= v.s.pcm.size) break
                if (v.fadeStep > 0f) fade = max(0f, fade - v.fadeStep)
                val x = v.s.pcm[p] / 32768f * v.duckTarget * fade
                if (duckIt) { oldL[i] += x * v.gl; oldR[i] += x * v.gr; any = true } else { fixL[i] += x * v.gl; fixR[i] += x * v.gr }
            }
        }
        if (!any) return 1f
        // judged against the new note plus whatever is not ducked (a gust whoosh), i.e. the mix at d = 0
        val base = peakAt(0f, n)
        val lo = base * (1f - ATTACK_TOL); val hi = base * (1f + ATTACK_TOL)
        var chosen = 0f
        for (d in DUCK_STEPS) { val p = peakAt(d, n); if (p in lo..hi) { chosen = d; break } }
        if (chosen < 1f) for (vi in 0 until voices.size) { val v = voices[vi]; if (v.duckable) v.duckTarget *= chosen }
        // a fully ducked note is gone for good: fade it so it frees its voice
        if (chosen == 0f) for (vi in 0 until voices.size) { val v = voices[vi]; if (v.duckable && v.fadeStep == 0f) startFade(v) }
        return chosen
    }

    private fun peakAt(d: Float, n: Int): Float {
        var m = 0f
        for (i in 0 until n) m = max(m, max(abs(newL[i] + fixL[i] + d * oldL[i]), abs(newR[i] + fixR[i] + d * oldR[i])))
        return m
    }

    companion object {
        const val CEILING = 0.891f            // -1 dBFS
        const val LOOKAHEAD = 96              // ~2.2 ms
        const val RELEASE_S = 0.12
        /** Attack window judged at each onset; v0.1.2: 100 ms, the window QA measures (some samples peak again at 100-120 ms). */
        const val ATTACK_S = 0.10f
        /** v0.1.2: +-0.21 dB around the new note's own peak (was +-0.42), so 10 strikes stay well inside 1 dB. */
        const val ATTACK_TOL = 0.025f
        const val DUCK_RAMP = 48              // ~1.1 ms
        /** Onset to output: the duck ramp plus the limiter look-ahead (~3.3 ms). */
        const val LATENCY = DUCK_RAMP + LOOKAHEAD
        const val FADE_S = 0.08f
        const val MAX_VOICES = 16
        const val ONSET_LOG = 256
        val DUCK_STEPS = floatArrayOf(1f, 0.85f, 0.7f, 0.58f, 0.48f, 0.4f, 0.33f, 0.27f, 0.22f, 0.18f, 0.14f, 0.11f, 0.085f, 0.065f, 0.05f, 0.035f, 0.025f, 0.015f)

        /** The pan of a tube (design sound.spatial fallback: +-0.3 by the tube's x). */
        fun tubePan(tube: Int): Float = Geo.TUBE_XZ[tube].first / 0.062f * 0.3f
    }
}
