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

    /** A queued note; [at] = the output frame it starts on (0 = at once). */
    private class Cmd(val s: Sample, val gain: Float, val pan: Float, val key: String, val duckable: Boolean, val at: Long)

    private val pending = ArrayDeque<Cmd>()
    private val voices = ArrayList<Voice>()
    private var serial = 0L
    /** Frames rendered so far: the mixer's own clock for scheduled notes (v0.1.3, D55). */
    var clock = 0L
        private set
    /** Onset frame of every note (test hook; the last [ONSET_LOG] only). */
    val onsets = ArrayDeque<Long>()

    // limiter state
    private val delayL = FloatArray(LOOKAHEAD)
    private val delayR = FloatArray(LOOKAHEAD)
    private val need = FloatArray(LOOKAHEAD) { 1f } // required gain per frame in the look-ahead ring
    private var ring = 0
    private var limGain = 1f
    private val releaseCoef = 1f - exp(-1.0 / (RELEASE_S * sampleRate)).toFloat()

    /** Last onset's decision, for the log and the test: duck factor applied to the older notes. */
    @Volatile var lastDuck = 1f
        private set
    /** Lowest limiter gain since the last [takeMinLimiterGain]. */
    private var minLim = 1f

    val active: Int get() = synchronized(this) { voices.size + pending.size }

    /**
     * Queue a note (any thread). [pan] -1..1 (left..right), equal-gain law clipped at 1 per side as before.
     * [delayS] > 0 starts it that much later on the output clock, to the frame (v0.1.3, D55: the physics thread hands
     * over strikes in batches, each placed at its own simulated time instead of all at the batch end).
     */
    fun play(s: Sample, gain: Float, pan: Float = 0f, key: String = "", duckable: Boolean = true, delayS: Float = 0f) {
        if (gain <= 0.0005f) return
        synchronized(this) {
            val at = if (delayS <= 0f) 0L else clock + (delayS * sampleRate).toLong()
            val c = Cmd(s, gain, pan, key, duckable, at)
            // keep the queue sorted by start frame (stable: equal frames stay in call order)
            var i = pending.size
            while (i > 0 && pending[i - 1].at > at) i--
            pending.add(i, c)
        }
    }

    /** Fade everything out over 80 ms (mute, background). */
    fun fadeAll() = synchronized(this) {
        pending.clear()
        for (v in voices) startFade(v)
    }

    fun takeMinLimiterGain(): Float = synchronized(this) { val m = minLim; minLim = 1f; m }

    /** Renders [frames] interleaved stereo frames into [out] (overwrites). */
    fun render(out: FloatArray, frames: Int) = synchronized(this) {
        for (f in 0 until frames) {
            while (pending.isNotEmpty() && pending.first().at <= clock) onset(pending.removeFirst())
            clock++
            var l = 0f; var r = 0f
            for (v in voices) {
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
            need[ring] = req
            var target = 1f
            for (k in 0 until LOOKAHEAD) if (need[k] < target) target = need[k]
            limGain = if (target < limGain) target else limGain + (target - limGain) * releaseCoef
            if (limGain < minLim) minLim = limGain
            val ol = delayL[ring]; val or = delayR[ring]
            delayL[ring] = l; delayR[ring] = r
            ring = (ring + 1) % LOOKAHEAD
            out[2 * f] = (ol * limGain).coerceIn(-CEILING, CEILING)
            out[2 * f + 1] = (or * limGain).coerceIn(-CEILING, CEILING)
        }
        voices.removeAll { it.done }
    }

    private fun startFade(v: Voice) { if (v.fadeStep == 0f) v.fadeStep = 1f / (FADE_S * sampleRate) }

    private fun onset(c: Cmd) {
        val gl = c.gain * min(1f, 1f - c.pan)
        val gr = c.gain * min(1f, 1f + c.pan)
        val v = Voice(c.s, c.key, c.duckable, gl, gr, serial++)
        onsets.addLast(clock); while (onsets.size > ONSET_LOG) onsets.removeFirst()
        // polyphony
        val live = voices.filter { it.fadeStep == 0f }
        if (c.key.isNotEmpty()) {
            val same = live.filter { it.key == c.key }
            if (same.size >= 2) startFade(same.minByOrNull { it.serial }!!)
        }
        if (live.size >= MAX_VOICES) startFade(live.minByOrNull { it.serial }!!)
        if (c.duckable) lastDuck = duckFor(v)
        voices += v
    }

    /**
     * Picks the duck factor d (1 = untouched) for the older chime notes so the new note's attack window peak of the mix
     * stays within [ATTACK_TOL] of the new note alone. Applied to every older duckable note (multiplicative).
     */
    private fun duckFor(nv: Voice): Float {
        val n = min((ATTACK_S * sampleRate).toInt(), nv.s.pcm.size)
        if (n <= 0) return 1f
        val newL = FloatArray(n); val newR = FloatArray(n)
        var solo = 0f
        for (i in 0 until n) {
            val x = nv.s.pcm[i] / 32768f
            newL[i] = x * nv.gl; newR[i] = x * nv.gr
            solo = max(solo, max(abs(newL[i]), abs(newR[i])))
        }
        if (solo <= 0f) return 1f
        val oldL = FloatArray(n); val oldR = FloatArray(n)   // duckable older notes (scaled by d)
        val fixL = FloatArray(n); val fixR = FloatArray(n)   // effects only (never ducked, the reference)
        var any = false
        for (v in voices) {
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
        fun peakAt(d: Float): Float {
            var m = 0f
            for (i in 0 until n) m = max(m, max(abs(newL[i] + fixL[i] + d * oldL[i]), abs(newR[i] + fixR[i] + d * oldR[i])))
            return m
        }
        // judged against the new note plus whatever is not ducked (a gust whoosh), i.e. the mix at d = 0
        val base = peakAt(0f)
        val lo = base * (1f - ATTACK_TOL); val hi = base * (1f + ATTACK_TOL)
        var chosen = 0f
        for (d in DUCK_STEPS) { val p = peakAt(d); if (p in lo..hi) { chosen = d; break } }
        if (chosen < 1f) for (v in voices) if (v.duckable) v.duckTarget *= chosen
        // a fully ducked note is gone for good: fade it so it frees its voice
        if (chosen == 0f) for (v in voices) if (v.duckable && v.fadeStep == 0f) startFade(v)
        return chosen
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
