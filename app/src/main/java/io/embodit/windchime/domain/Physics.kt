package io.embodit.windchime.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One sounding hit (design physics.sound). [speed] = relative normal speed, m/s; [gain] = extra linear gain. */
data class Strike(val tube: Int, val speed: Float, val source: Source, val gain: Float = 1f, val t: Double = 0.0) {
    enum class Source { CLAPPER, TUBE, TAP, TOKEN, SWAP }

    /** Playback volume 0..1 before the master volume: clamp((v - 0.03) / 0.40, 0, 1)^0.7 x gain. */
    val volume: Float get() = (((speed - Phys.SOUND_V) / 0.40f).coerceIn(0f, 1f).toDouble().let { Math.pow(it, 0.7) }.toFloat()) * gain
}

/** Tuning constants (design physics). */
object Phys {
    const val G = 9.81f
    const val HZ = 360
    const val H = 1f / HZ
    const val MAX_SUBSTEPS = 8
    /** Free-swing damping: amplitude e^(-c t / 2) reaches 1/1000 in 8 s. */
    val DAMP = (2.0 * kotlin.math.ln(1000.0) / 8.0).toFloat()
    const val M_CLAPPER = 1.0f
    const val M_TUBE = 1.5f
    const val M_SAIL = 0.6f
    const val RESTITUTION = 0.6f
    const val SOUND_V = 0.03f
    const val TUBE_TUBE_GAIN = 0.355f // -9 dB
    const val SWAP_GAIN = 0.5f // -6 dB
    const val TUBE_GAP_MS = 120L
    const val MAX_PER_S = 10
    const val GRAB_W = 25f
    const val TAP_TUBE_V = 0.25f
    const val TAP_TOKEN_V = 0.12f
    const val TAP_SAIL_V = 0.30f
    const val TAP_SOUND_V = 0.35f

    /** Mean wind speed (m/s) per level W0..W3; tuned by WindRateTest to the design's collisions-per-second bands. */
    val WIND_MEAN = floatArrayOf(0f, 0.385f, 0.55f, 1.9f)
    /** Angular acceleration per (m/s)^2 of wind on the sail (speed^2 x area); tubes get 1/6 of it. */
    @JvmField var WIND_K = 12f
    /** The bamboo slat is heavier than the walnut leaf and the paper strip: it catches less wind (tuned). */
    @JvmField var GAIN = floatArrayOf(1f, 0.72f, 0.85f)
    fun windGain(k: ChimeKind): Float = GAIN[k.ordinal]

    /** Clapper + sail damping relative to [DAMP] (the free-swing T60 = 8 s is the tubes'; a cord-hung clapper rings on longer). */
    @JvmField var CLAPPER_DAMP = 0.3f

    /** Turbulence share of the mean speed at ~1 Hz (along and across the wind). */
    @JvmField var TURB = 4f
    const val GUST_S = 0.8f
    /** A user gust peaks at the gale mean x [GUST_BOOST] x strength. */
    @JvmField var GUST_BOOST = 2.0f
}

/** A user gust (physics.userGust): unit horizontal direction, strength 0.2..1, started at [t0]. */
data class Gust(val dx: Float, val dz: Float, val strength: Float, val t0: Double)

/**
 * The chime as seven little pendulums, integrated by hand (design physics; no physics engine). Each pendulum has two
 * small angles (ax tilts its far end toward +X, az toward +Z); the tube and clapper cords hang from the disc at
 * y = 0.25, the sail hangs from the clapper's bottom. Fixed 1/360 s substeps, semi-implicit Euler, seeded noise, so a
 * debug command replays the same gust.
 */
class ChimeSim(kind: ChimeKind = ChimeKind.BRASS, seed: Long = 7) {
    var kind: ChimeKind = kind
        private set

    // tubes
    val tAx = FloatArray(5); val tAz = FloatArray(5); val tWx = FloatArray(5); val tWz = FloatArray(5)
    // clapper
    var cAx = 0f; var cAz = 0f; var cWx = 0f; var cWz = 0f
    // sail (absolute angles from the vertical at its hanging point)
    var sAx = 0f; var sAz = 0f; var sWx = 0f; var sWz = 0f

    /** Simulated time, seconds. */
    var time = 0.0
        private set
    private var acc = 0.0
    private var noise = Noise(seed)
    var seed: Long = seed
        private set

    var windTarget = 0f
    var windMean = 0f
        private set
    private val gusts = ArrayList<Gust>()
    val gustActive: Boolean get() = gusts.any { time - it.t0 < Phys.GUST_S }

    /** Grab target for the sail centre (volume metres), or null. */
    var grabTarget: V3? = null
    /** Extra damping multiplier while a chime swap brings everything to rest. */
    var calm = 1f

    private val lastTubeStrike = DoubleArray(5) { -10.0 }
    private val recent = ArrayDeque<Double>()
    private val out = ArrayList<Strike>()
    /** Counters for tests and the log. */
    var collisions = 0; private set
    var dropped = 0; private set

    fun reseed(s: Long) { seed = s; noise = Noise(s) }

    fun setKind(k: ChimeKind) { kind = k }

    /** Everything at rest, hanging straight (also the swap's end). */
    fun rest() {
        for (i in 0 until 5) { tAx[i] = 0f; tAz[i] = 0f; tWx[i] = 0f; tWz[i] = 0f }
        cAx = 0f; cAz = 0f; cWx = 0f; cWz = 0f; sAx = 0f; sAz = 0f; sWx = 0f; sWz = 0f
    }

    // ------------------------------------------------------------------ geometry of the current pose

    fun tubeLeff(i: Int): Float = Geo.TUBE_CORD + 2f * kind.length(i) / 3f
    private val sailL: Float get() = kind.sailCenterL

    fun tubePivot(i: Int) = V3(Geo.TUBE_XZ[i].first, Geo.PIVOT_Y, Geo.TUBE_XZ[i].second)
    fun tubeDir(i: Int) = dir(tAx[i], tAz[i])
    fun tubeTopPt(i: Int) = tubePivot(i) + tubeDir(i) * Geo.TUBE_CORD
    fun tubeBottomPt(i: Int) = tubePivot(i) + tubeDir(i) * (Geo.TUBE_CORD + kind.length(i))
    val clapperPivot = V3(0f, Geo.PIVOT_Y, 0f)
    fun clapperDir() = dir(cAx, cAz)
    fun clapperCenter() = clapperPivot + clapperDir() * Geo.CLAPPER_CORD
    fun sailPivot() = clapperPivot + clapperDir() * (Geo.CLAPPER_CORD + Geo.SAIL_HANG)
    fun sailDir() = dir(sAx, sAz)
    fun sailPoint(s: Float) = sailPivot() + sailDir() * s
    fun sailCenter() = sailPoint(sailL)

    /** The sail's width axis (local +X rotated with the sail) and plate normal (local +Z rotated). */
    fun sailAxes(): Pair<V3, V3> {
        val d = sailDir()
        return Rot.fromDown(d).let { (ax, ang) -> Rot.rotate(V3(1f, 0f, 0f), ax, ang) to Rot.rotate(V3(0f, 0f, 1f), ax, ang) }
    }

    /** Sail outline corners: top-left, top-right, bottom-right, bottom-left. */
    fun sailCorners(): List<V3> {
        val (u, _) = sailAxes()
        val top = sailPoint(kind.sailCordL); val bot = sailPoint(kind.sailBottomL)
        val hw = kind.sailW / 2
        return listOf(top - u * hw, top + u * hw, bot + u * hw, bot - u * hw)
    }

    fun tiltDeg(ax: Float, az: Float): Float = Geo.deg(asin(sqrt(min(1f, sin(ax) * sin(ax) + sin(az) * sin(az)))))
    fun maxTubeDeg(): Float = (0 until 5).maxOf { tiltDeg(tAx[it], tAz[it]) }
    fun sailDeg(): Float = tiltDeg(sAx, sAz)
    fun clapperH(): Float = (clapperCenter() - clapperPivot).horiz

    /** Largest swing of anything (degrees): +SWING while > 1 deg. */
    fun swingDeg(): Float = max(max(maxTubeDeg(), sailDeg()), tiltDeg(cAx, cAz))

    /** Mechanical energy (J-like units) for the decay test. */
    fun energy(): Float {
        var e = 0f
        fun pend(m: Float, l: Float, ax: Float, az: Float, wx: Float, wz: Float) {
            e += 0.5f * m * l * l * (wx * wx + wz * wz) + m * Phys.G * l * (2f - cos(ax) - cos(az))
        }
        for (i in 0 until 5) pend(Phys.M_TUBE, tubeLeff(i), tAx[i], tAz[i], tWx[i], tWz[i])
        pend(Phys.M_CLAPPER, Geo.CLAPPER_CORD, cAx, cAz, cWx, cWz)
        pend(Phys.M_SAIL, sailL, sAx, sAz, sWx, sWz)
        val k = coupling()
        e += 0.5f * k * ((sAx - cAx) * (sAx - cAx) + (sAz - cAz) * (sAz - cAz))
        return e
    }

    private fun coupling(): Float = Phys.M_SAIL * Phys.G * Geo.CLAPPER_CORD

    // ------------------------------------------------------------------ wind

    /** Wind vector (m/s, horizontal x / z) at the current time: slow-turning direction, gusty speed, plus user gusts. */
    fun wind(): Pair<Float, Float> {
        var wx = 0f; var wz = 0f
        if (windMean > 0.001f) {
            val phi = 0.6f + Math.PI.toFloat() * noise.at(0.05 * time, 0)
            val speed = max(0f, windMean * (1f + 0.6f * noise.at(0.3 * time, 1)))
            // turbulence near the chime's own swing (~1 Hz): without it the wind only leans the chime over
            // (design physics.wind + tuning, deviation DV-wind-turbulence)
            val along = windMean * Phys.TURB * (0.7f * noise.at(1.1 * time, 2) + 0.5f * noise.at(2.3 * time, 4))
            val across = windMean * Phys.TURB * 0.7f * noise.at(0.9 * time, 3)
            val ux = sin(phi); val uz = -cos(phi)
            wx = (speed + along) * ux - across * uz; wz = (speed + along) * uz + across * ux
        }
        for (g in gusts) {
            val age = (time - g.t0).toFloat()
            if (age < 0f || age >= Phys.GUST_S) continue
            val env = if (age < 0.15f) age / 0.15f else (1f - (age - 0.15f) / (Phys.GUST_S - 0.15f)).let { it * it }
            // a gust is gusty too: its speed flutters at ~2 Hz so the chime knocks a run of notes, not one
            val v = Phys.WIND_MEAN[3] * Phys.GUST_BOOST * g.strength * env * (1f + 0.5f * noise.at(2.1 * time, 5))
            val side = Phys.WIND_MEAN[3] * Phys.GUST_BOOST * g.strength * env * 0.45f * noise.at(1.7 * time, 6)
            wx += v * g.dx - side * g.dz; wz += v * g.dz + side * g.dx
        }
        return wx to wz
    }

    fun gust(dx: Float, dz: Float, strength: Float) {
        val l = sqrt(dx * dx + dz * dz)
        val (ux, uz) = if (l < 1e-4f) 0f to -1f else (dx / l) to (dz / l)
        gusts.removeAll { time - it.t0 >= Phys.GUST_S }
        gusts += Gust(ux, uz, strength.coerceIn(0.2f, 1f), time)
    }

    /** Progress 0..1 of the newest user gust (wind lines), or null. */
    fun gustProgress(): Pair<Gust, Float>? = gusts.lastOrNull()?.let { g -> ((time - g.t0) / Phys.GUST_S).toFloat().takeIf { it in 0f..1f }?.let { g to it } }

    // ------------------------------------------------------------------ input pushes (same path for UI, 3D and debug)

    /** A tap on tube [i] (or its tile, [token]): it is knocked outward and rings at once (T1 / T2). */
    fun tapTube(i: Int, token: Boolean = false): Strike {
        val (x, z) = Geo.TUBE_XZ[i]
        val l = sqrt(x * x + z * z)
        val ux = x / l; val uz = z / l
        val v = if (token) Phys.TAP_TOKEN_V else Phys.TAP_TUBE_V
        val s = Geo.CLAPPER_CORD
        tWx[i] += v * ux / (s * cos(tAx[i])); tWz[i] += v * uz / (s * cos(tAz[i]))
        val st = Strike(i, Phys.TAP_SOUND_V, if (token) Strike.Source.TOKEN else Strike.Source.TAP, t = time)
        lastTubeStrike[i] = time; recent.addLast(time)
        return st
    }

    /** A tap on the sail: a little push away from the user. */
    fun tapSail() { sWz -= Phys.TAP_SAIL_V / sailL }

    // ------------------------------------------------------------------ stepping

    /** Advances by the real frame time [dt] (seconds) in fixed substeps; returns the strikes that sounded. */
    fun advance(dt: Float): List<Strike> {
        out.clear()
        acc += dt.coerceIn(0f, 0.25f)
        var n = floor(acc * Phys.HZ + 1e-6).toInt()
        acc -= n.toDouble() / Phys.HZ
        if (n > Phys.MAX_SUBSTEPS) { n = Phys.MAX_SUBSTEPS; acc = 0.0 }
        repeat(n) { step() }
        return ArrayList(out)
    }

    /** Runs [seconds] of simulation at [fps], collecting every strike (tests, loudness render). */
    fun run(seconds: Float, fps: Int = 90, each: ((Strike) -> Unit)? = null): Int {
        var count = 0
        val frames = (seconds * fps).toInt()
        repeat(frames) { advance(1f / fps).forEach { s -> count++; each?.invoke(s) } }
        return count
    }

    fun step() {
        val h = Phys.H
        time += h
        // wind level eases to its target over ~1 s (T8)
        val dm = Phys.WIND_MEAN.last() * h
        windMean = if (windMean < windTarget) min(windTarget, windMean + dm) else max(windTarget, windMean - dm)
        val (wx, wz) = wind()
        val wl = sqrt(wx * wx + wz * wz)
        val kw = Phys.WIND_K * Phys.windGain(kind)
        val fx = kw * wl * wx; val fz = kw * wl * wz
        val c = Phys.DAMP * calm

        // tubes
        for (i in 0 until 5) {
            val l = tubeLeff(i)
            val q = soft(tiltDeg(tAx[i], tAz[i]) / Geo.TUBE_MAX_DEG)
            // the force on a tube is 1/6 of the sail's; it is heavier and longer: angular accel scales by lever / inertia
            val tw = (1f / 6f) * (l / sailL) * (Phys.M_SAIL * sailL * sailL) / (Phys.M_TUBE * l * l)
            tWx[i] += h * (-(Phys.G / l) * sin(tAx[i]) - c * q * tWx[i] + fx * tw)
            tWz[i] += h * (-(Phys.G / l) * sin(tAz[i]) - c * q * tWz[i] + fz * tw)
            tAx[i] += h * tWx[i]; tAz[i] += h * tWz[i]
        }
        // clapper + sail, coupled through the sail cord
        val k = coupling()
        val ic = Phys.M_CLAPPER * Geo.CLAPPER_CORD * Geo.CLAPPER_CORD
        val sl = sailL
        val isl = Phys.M_SAIL * sl * sl
        // the clapper's soft stop is the tube ring itself (it meets a tube before 0.035 m); a damping ramp there would
        // brake it just before every knock
        val qc = Phys.CLAPPER_DAMP
        cWx += h * (-(Phys.G / Geo.CLAPPER_CORD) * sin(cAx) - c * qc * cWx + k * (sAx - cAx) / ic)
        cWz += h * (-(Phys.G / Geo.CLAPPER_CORD) * sin(cAz) - c * qc * cWz + k * (sAz - cAz) / ic)
        val qs = soft(sailDeg() / Geo.SAIL_MAX_DEG) * Phys.CLAPPER_DAMP
        var gx = 0f; var gz = 0f
        grabTarget?.let { tgt ->
            val (tx, tz) = targetAngles(tgt)
            gx = Phys.GRAB_W * Phys.GRAB_W * (tx - sAx) - 2 * Phys.GRAB_W * sWx
            gz = Phys.GRAB_W * Phys.GRAB_W * (tz - sAz) - 2 * Phys.GRAB_W * sWz
        }
        sWx += h * (-(Phys.G / sl) * sin(sAx) - c * qs * sWx - k * (sAx - cAx) / isl + fx + gx)
        sWz += h * (-(Phys.G / sl) * sin(sAz) - c * qs * sWz - k * (sAz - cAz) / isl + fz + gz)
        cAx += h * cWx; cAz += h * cWz
        sAx += h * sWx; sAz += h * sWz

        limits()
        tubeContacts()
        cordConstraint()
        clapperContacts()
        limits()
        clapperContacts()
        limits()
    }

    /** Soft limit: damping grows linearly to 10x between 80 % and 100 % of a limit. */
    private fun soft(q: Float): Float = if (q <= 0.8f) 1f else 1f + 9f * ((q - 0.8f) / 0.2f).coerceAtMost(1f)

    /** Sail angles that point its centre at [tgt], clamped to 35 deg. */
    fun targetAngles(tgt: V3): Pair<Float, Float> {
        val p = sailPivot()
        val d = tgt - p
        val l = max(d.length, 1e-4f)
        var sx = d.x / l; var sz = d.z / l
        val m = sqrt(sx * sx + sz * sz); val lim = sin(Geo.rad(Geo.SAIL_MAX_DEG))
        if (m > lim) { sx *= lim / m; sz *= lim / m }
        if (d.y > 0f) { sx *= 0.999f; sz *= 0.999f } // above the hanging point: keep it at the limit, never flip over
        return asin(sx) to asin(sz)
    }

    private fun limits() {
        for (i in 0 until 5) clampTilt(i)
        // sail <= 35 deg
        run {
            val sx = sin(sAx); val sz = sin(sAz); val m = sqrt(sx * sx + sz * sz); val lim = sin(Geo.rad(Geo.SAIL_MAX_DEG))
            if (m > lim) {
                val nx = sx / m; val nz = sz / m
                sAx = asin(nx * lim); sAz = asin(nz * lim)
                val out = sWx * nx + sWz * nz
                if (out > 0f) { sWx -= out * nx; sWz -= out * nz }
            }
        }
        // clapper horizontal <= 0.035
        run {
            val lim = Geo.CLAPPER_MAX_H / Geo.CLAPPER_CORD
            val sx = sin(cAx); val sz = sin(cAz); val m = sqrt(sx * sx + sz * sz)
            if (m > lim) {
                val nx = sx / m; val nz = sz / m
                cAx = asin(nx * lim); cAz = asin(nz * lim)
                val out = cWx * nx + cWz * nz
                if (out > 0f) { cWx -= out * nx; cWz -= out * nz }
            }
        }
    }

    private fun clampTilt(i: Int) {
        val sx = sin(tAx[i]); val sz = sin(tAz[i]); val m = sqrt(sx * sx + sz * sz); val lim = sin(Geo.rad(Geo.TUBE_MAX_DEG))
        if (m > lim) {
            val nx = sx / m; val nz = sz / m
            tAx[i] = asin(nx * lim); tAz[i] = asin(nz * lim)
            val out = tWx[i] * nx + tWz[i] * nz
            if (out > 0f) { tWx[i] -= out * nx; tWz[i] -= out * nz }
        }
    }

    /** Moves the point at lever [s] on a pendulum horizontally by (dx, dz) through its angles. */
    private fun nudge(ax: Float, az: Float, s: Float, dx: Float, dz: Float): Pair<Float, Float> {
        val l = max(s, 0.02f)
        return asin((sin(ax) + dx / l).coerceIn(-0.95f, 0.95f)) to asin((sin(az) + dz / l).coerceIn(-0.95f, 0.95f))
    }

    private fun emit(i: Int, speed: Float, source: Strike.Source, gain: Float) {
        collisions++
        if (speed <= Phys.SOUND_V) return
        while (recent.isNotEmpty() && time - recent.first() > 1.0) recent.removeFirst()
        if ((time - lastTubeStrike[i]) * 1000 < Phys.TUBE_GAP_MS || recent.size >= Phys.MAX_PER_S) { dropped++; return }
        lastTubeStrike[i] = time; recent.addLast(time)
        out += Strike(i, speed, source, gain, time)
    }

    private fun clapperContacts() {
        val cc = clapperCenter()
        val r = kind.tubeR
        for (i in 0 until 5) {
            val d = tubeDir(i)
            val s = (Geo.PIVOT_Y - cc.y) / max(-d.y, 0.3f)
            if (s < Geo.TUBE_CORD || s > Geo.TUBE_CORD + kind.length(i) + Geo.CLAPPER_T / 2) continue
            val tp = tubePivot(i) + d * s
            var nx = cc.x - tp.x; var nz = cc.z - tp.z
            val dist = sqrt(nx * nx + nz * nz)
            val minD = Geo.CLAPPER_R + r
            if (dist >= minD) continue
            if (dist < 1e-5f) { val (x, z) = Geo.TUBE_XZ[i]; val l = sqrt(x * x + z * z); nx = -x / l; nz = -z / l } else { nx /= dist; nz /= dist }
            val lc = Geo.CLAPPER_CORD
            val vcx = lc * cos(cAx) * cWx; val vcz = lc * cos(cAz) * cWz
            val vtx = s * cos(tAx[i]) * tWx[i]; val vtz = s * cos(tAz[i]) * tWz[i]
            val vn = (vcx - vtx) * nx + (vcz - vtz) * nz
            val mc = Phys.M_CLAPPER
            val mt = Phys.M_TUBE * tubeLeff(i) * tubeLeff(i) / (s * s)
            val wc = (1 / mc) / (1 / mc + 1 / mt); val wt = 1f - wc
            if (vn < 0f) {
                val j = -(1 + Phys.RESTITUTION) * vn / (1 / mc + 1 / mt)
                cWx += (j / mc) * nx / (lc * cos(cAx)); cWz += (j / mc) * nz / (lc * cos(cAz))
                tWx[i] -= (j / mt) * nx / (s * cos(tAx[i])); tWz[i] -= (j / mt) * nz / (s * cos(tAz[i]))
                emit(i, -vn, Strike.Source.CLAPPER, 1f)
            }
            val pen = minD - dist + 1e-4f
            nudge(cAx, cAz, lc, pen * wc * nx, pen * wc * nz).let { (a, b) -> cAx = a; cAz = b }
            nudge(tAx[i], tAz[i], s, -pen * wt * nx, -pen * wt * nz).let { (a, b) -> tAx[i] = a; tAz[i] = b }
        }
    }

    /** Neighbouring tubes around the ring also knock (9 dB quieter). */
    private fun tubeContacts() {
        val r = kind.tubeR
        for ((a, b) in RING_PAIRS) {
            val (pa, pb) = Solid.closest(tubeTopPt(a), tubeBottomPt(a), tubeTopPt(b), tubeBottomPt(b))
            var nx = pb.x - pa.x; var nz = pb.z - pa.z
            val dist = sqrt(nx * nx + nz * nz)
            val minD = 2 * r
            if (dist >= minD || dist < 1e-6f) continue
            nx /= dist; nz /= dist
            val sa = (pa - tubePivot(a)).length; val sb = (pb - tubePivot(b)).length
            val vax = sa * cos(tAx[a]) * tWx[a]; val vaz = sa * cos(tAz[a]) * tWz[a]
            val vbx = sb * cos(tAx[b]) * tWx[b]; val vbz = sb * cos(tAz[b]) * tWz[b]
            val vn = (vbx - vax) * nx + (vbz - vaz) * nz
            val ma = Phys.M_TUBE * tubeLeff(a) * tubeLeff(a) / (sa * sa)
            val mb = Phys.M_TUBE * tubeLeff(b) * tubeLeff(b) / (sb * sb)
            if (vn < 0f) {
                val j = -(1 + Phys.RESTITUTION) * vn / (1 / ma + 1 / mb)
                tWx[a] -= (j / ma) * nx / (sa * cos(tAx[a])); tWz[a] -= (j / ma) * nz / (sa * cos(tAz[a]))
                tWx[b] += (j / mb) * nx / (sb * cos(tAx[b])); tWz[b] += (j / mb) * nz / (sb * cos(tAz[b]))
                emit(if (sa > sb) a else b, -vn, Strike.Source.TUBE, Phys.TUBE_TUBE_GAIN)
            }
            val pen = (minD - dist) / 2 + 1e-4f
            nudge(tAx[a], tAz[a], sa, -pen * nx, -pen * nz).let { (x, z) -> tAx[a] = x; tAz[a] = z }
            nudge(tAx[b], tAz[b], sb, pen * nx, pen * nz).let { (x, z) -> tAx[b] = x; tAz[b] = z }
        }
    }

    /** Sail cord / sail outline segments (constraint 4) in the current pose. */
    fun sailSegments(): List<Pair<V3, V3>> {
        val c = sailCorners()
        return listOf(sailPivot() to sailPoint(kind.sailCordL), c[0] to c[1], c[0] to c[3], c[1] to c[2])
    }

    /** Shortest distance from the sail cord / outline to tube [i]'s axis, minus the tube radius (constraint 4 test). */
    fun cordClearance(i: Int): Float {
        val a = tubeTopPt(i); val b = tubeBottomPt(i)
        var m = Float.MAX_VALUE
        for ((p, q) in sailSegments()) m = min(m, Solid.segSegDist(p, q, a, b))
        return m - kind.tubeR
    }

    /**
     * Constraint 4: the sail cord (a 1 mm capsule) and the sail outline stay >= tube radius + 1 mm off every tube
     * axis. Contact pushes the tube aside (no sound); if the tube is pinned at its limit, the sail angle gives way.
     */
    private fun cordConstraint() {
        val minExtra = 0.0016f
        for (pass in 0 until 5) {
            var any = false
            for (i in 0 until 5) {
                val ta = tubeTopPt(i); val tb = tubeBottomPt(i)
                for ((p, q) in sailSegments()) {
                    val (ps, pt) = Solid.closest(p, q, ta, tb)
                    val dist = (pt - ps).length
                    val minD = kind.tubeR + minExtra
                    if (dist >= minD) continue
                    any = true
                    var nx = pt.x - ps.x; var nz = pt.z - ps.z
                    val hl = sqrt(nx * nx + nz * nz)
                    if (hl < 1e-6f) { val (x, z) = Geo.TUBE_XZ[i]; val l = sqrt(x * x + z * z); nx = x / l; nz = z / l } else { nx /= hl; nz /= hl }
                    val pen = minD - dist + 1e-4f
                    val tubeShare = if (pass < 3 && tiltDeg(tAx[i], tAz[i]) < Geo.TUBE_MAX_DEG - 0.5f) 0.8f else 0f
                    val st = (pt - tubePivot(i)).length
                    nudge(tAx[i], tAz[i], st, pen * tubeShare * nx, pen * tubeShare * nz).let { (x, z) -> tAx[i] = x; tAz[i] = z }
                    val ss = (ps - sailPivot()).length
                    nudge(sAx, sAz, ss, -pen * (1 - tubeShare) * nx, -pen * (1 - tubeShare) * nz).let { (x, z) -> sAx = x; sAz = z }
                    // inelastic: drop the approaching part of the relative speed
                    val vt = st * (cos(tAx[i]) * tWx[i] * nx + cos(tAz[i]) * tWz[i] * nz)
                    val vs = ss * (cos(sAx) * sWx * nx + cos(sAz) * sWz * nz)
                    val rel = vt - vs
                    if (rel < 0f) {
                        if (tubeShare > 0f) { tWx[i] -= 0.8f * rel * nx / max(st, 0.02f); tWz[i] -= 0.8f * rel * nz / max(st, 0.02f) }
                        val sh = 1f - tubeShare * 0.8f
                        sWx += sh * rel * nx / max(ss, 0.02f); sWz += sh * rel * nz / max(ss, 0.02f)
                    }
                    clampTilt(i)
                }
            }
            if (!any) break
        }
    }

    /** Bodies at rest after [seconds] of extra damping (swap S04). */
    fun settle(seconds: Float) { calm = 20f; run(seconds, 90); calm = 1f }

    companion object {
        /** Neighbours around the ring (indices): 1-2, 2-4, 4-5, 5-3, 3-1. */
        val RING_PAIRS = listOf(0 to 1, 1 to 3, 3 to 4, 4 to 2, 2 to 0)

        /** Hanging direction for two tilt angles (far end displaced by sin ax along X, sin az along Z). */
        fun dir(ax: Float, az: Float): V3 {
            val sx = sin(ax); val sz = sin(az)
            return V3(sx, -sqrt(max(0f, 1f - sx * sx - sz * sz)), sz)
        }
    }
}

/** Rotations without a math library: shortest arc from straight down to [d]; Rodrigues for vectors. */
object Rot {
    fun fromDown(d: V3): Pair<V3, Float> {
        // axis = (0,-1,0) x d = (-d.z, 0, d.x)
        val ax = V3(-d.z, 0f, d.x)
        val l = ax.length
        if (l < 1e-7f) return V3(1f, 0f, 0f) to 0f
        val ang = kotlin.math.acos((-d.y / d.length).coerceIn(-1f, 1f))
        return ax * (1f / l) to ang
    }

    fun rotate(v: V3, k: V3, a: Float): V3 {
        val c = cos(a); val s = sin(a)
        val kxv = V3(k.y * v.z - k.z * v.y, k.z * v.x - k.x * v.z, k.x * v.y - k.y * v.x)
        return v * c + kxv * s + k * (k.dot(v) * (1 - c))
    }
}

/** Seeded 1D gradient noise in about [-1, 1] (Perlin-style); [channel] picks an independent stream. */
class Noise(seed: Long) {
    private val perm = IntArray(512)

    init {
        val r = java.util.Random(seed)
        val p = IntArray(256) { it }
        for (i in 255 downTo 1) { val j = r.nextInt(i + 1); val t = p[i]; p[i] = p[j]; p[j] = t }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    private fun grad(i: Int, ch: Int): Float = (perm[(perm[i and 255] + ch * 37) and 511] / 127.5f) - 1f

    fun at(t: Double, ch: Int): Float {
        val i = floor(t).toInt()
        val f = (t - i).toFloat()
        val g0 = grad(i, ch) * f
        val g1 = grad(i + 1, ch) * (f - 1f)
        val u = f * f * f * (f * (f * 6 - 15) + 10)
        return ((g0 + (g1 - g0) * u) * 2f).coerceIn(-1f, 1f)
    }
}

@Suppress("unused")
private fun absMax(a: Float, b: Float) = if (abs(a) > abs(b)) a else b
