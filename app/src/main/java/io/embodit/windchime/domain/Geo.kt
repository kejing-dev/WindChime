package io.embodit.windchime.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** A small immutable vector (metres, volume axes: +X right, +Y up, +Z toward the user). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    val length: Float get() = sqrt(x * x + y * y + z * z)
    val horiz: Float get() = sqrt(x * x + z * z)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    companion object { val ZERO = V3(0f, 0f, 0f) }
}

/**
 * Every fixed measure of the volume (design container.volume.math, spatialLayout, occlusionCheck), in one place.
 * Origin = volume centre.
 */
object Geo {
    // volume 0.60 x 0.70 x 0.60
    const val VOL_W = 0.60f
    const val VOL_H = 0.70f
    const val VOL_D = 0.60f

    // stand: base, column, arm
    const val BASE_X0 = -0.15f; const val BASE_X1 = 0.15f
    const val BASE_Y0 = -0.345f; const val BASE_Y1 = -0.325f
    const val BASE_Z0 = -0.29f; const val BASE_Z1 = 0.12f
    const val COLUMN_R = 0.013f
    const val COLUMN_Z = -0.24f
    const val COLUMN_TOP = 0.31f
    const val ARM_R = 0.010f
    const val ARM_Y = 0.31f

    // screen (paper + ash frame), its lower edge in the slot at the back of the base
    const val SCREEN_W = 0.44f
    const val SCREEN_H = 0.62f
    const val SCREEN_T = 0.012f
    val SCREEN_C = V3(0f, -0.03f, -0.284f)
    const val SCREEN_FRAME = 0.016f

    // the chime: hanging cord (0.30 -> 0.27), top disc 0.27..0.25, five tube cords from y 0.25
    const val HOOK_Y = 0.30f
    const val DISC_R = 0.08f
    const val DISC_Y0 = 0.25f
    const val DISC_Y1 = 0.27f
    const val PIVOT_Y = 0.25f
    const val TUBE_CORD = 0.03f
    const val RING_R = 0.065f
    const val CORD_R = 0.00075f

    /** Tube hanging points (x, z): vertex of the pentagon at the back (design chimes.layout); index 0 = tube 1 (lowest). */
    val TUBE_XZ = listOf(-0.062f to -0.020f, -0.038f to 0.053f, 0f to -0.065f, 0.038f to 0.053f, 0.062f to -0.020f)

    // clapper: hangs from the disc centre on a 0.24 m cord; disc 0.07 x 0.012
    const val CLAPPER_CORD = 0.24f
    const val CLAPPER_R = 0.035f
    const val CLAPPER_T = 0.012f
    /** Clapper centre at rest (y = 0.25 - 0.24). */
    const val CLAPPER_Y = PIVOT_Y - CLAPPER_CORD
    /** The sail hangs from the clapper's bottom face (0.006 below its centre). */
    const val SAIL_HANG = CLAPPER_T / 2

    // limits (physics.limits)
    const val CLAPPER_MAX_H = 0.035f
    const val SAIL_MAX_DEG = 35f
    const val TUBE_MAX_DEG = 20f

    // base tiles (5) + the panel tile
    const val TOKEN_W = 0.045f
    const val TOKEN_D = 0.034f
    const val TOKEN_H = 0.014f
    const val TOKEN_TILT_DEG = 35f
    val TOKEN_X = floatArrayOf(-0.116f, -0.058f, 0f, 0.058f, 0.116f)
    const val TOKEN_Z = 0.085f
    const val TOKEN_Y = BASE_Y1 + 0.007f
    const val PANEL_W = 0.06f
    const val PANEL_H = 0.035f
    const val PANEL_T = 0.008f
    val PANEL_C = V3(0.17f, -0.27f, -0.27f)
    const val PANEL_HIT_W = 0.07f
    const val PANEL_HIT_H = 0.05f

    // strips behind the tube ring (v1.3): catch taps that missed a tube
    const val STRIP_Z = -0.085f
    const val STRIP_T = 0.004f

    /** Eye positions relative to the volume centre (occlusionCheck.eyePositions). */
    val EYES = listOf(
        "sit" to V3(0f, 0.08f, 0.82f), "stand" to V3(0f, 0.53f, 0.82f),
        "sit-L30" to V3(-0.41f, 0.08f, 0.71f), "sit-R30" to V3(0.41f, 0.08f, 0.71f),
        "stand-L30" to V3(-0.41f, 0.53f, 0.71f), "stand-R30" to V3(0.41f, 0.53f, 0.71f),
    )

    /** Control window left edge assumed at x = +0.35, z = 0 (design controlWindow; ui-audit has the emulator value). */
    const val WINDOW_LEFT_X = 0.35f
    const val WINDOW_Z = 0f

    fun tubeTop(kind: ChimeKind, i: Int): Float = PIVOT_Y - TUBE_CORD
    fun tubeBottom(kind: ChimeKind, i: Int): Float = PIVOT_Y - TUBE_CORD - kind.length(i)

    /** The 5 strips: x ranges split at the midpoints of neighbouring tubes (sorted by x), outer ones to tube edge + 8 mm. */
    fun strips(kind: ChimeKind): List<Pair<Float, Float>> {
        val xs = TUBE_XZ.map { it.first }
        val r = kind.tubeR
        return xs.indices.map { i ->
            val lo = if (i == 0) xs[0] - r - 0.008f else (xs[i - 1] + xs[i]) / 2
            val hi = if (i == xs.lastIndex) xs[i] + r + 0.008f else (xs[i] + xs[i + 1]) / 2
            lo to hi
        }
    }

    /** Strip index under a hit at volume x (tap on a strip collider); null outside. */
    fun stripAt(kind: ChimeKind, x: Float): Int? = strips(kind).indexOfFirst { (lo, hi) -> x >= lo && x < hi }.takeIf { it >= 0 }

    /**
     * The enhanced screen-tap fallback (physics.userGust): project the ray hit back onto the tube plane (z ~ 0) and take
     * the nearest tube within 3 cm horizontally. [hit] is the point on the screen; [from] a point on the ray (the eye).
     */
    fun tubeNearRay(kind: ChimeKind, from: V3, hit: V3, maxM: Float = 0.03f): Int? {
        val d = hit - from
        if (abs(d.z) < 1e-5f) return null
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for ((i, xz) in TUBE_XZ.withIndex()) {
            val t = (xz.second - from.z) / d.z
            val p = from + d * t
            val y0 = tubeBottom(kind, i); val y1 = tubeTop(kind, i)
            if (p.y < y0 - 0.01f || p.y > y1 + 0.01f) continue
            val dx = abs(p.x - xz.first)
            if (dx <= maxM + kind.tubeR && dx < bestD) { bestD = dx; best = i }
        }
        return best
    }

    fun inVolume(p: V3, margin: Float = 0f): Boolean =
        abs(p.x) <= VOL_W / 2 - margin && abs(p.y) <= VOL_H / 2 - margin && abs(p.z) <= VOL_D / 2 - margin

    fun deg(rad: Float): Float = Math.toDegrees(rad.toDouble()).toFloat()
    fun rad(deg: Float): Float = Math.toRadians(deg.toDouble()).toFloat()

    /** Horizontal bearing (degrees, + to the right) of [p] seen from [eye]. */
    fun bearing(eye: V3, p: V3): Float = deg(atan2(p.x - eye.x, eye.z - p.z))
}

/** Static obstacle shapes for the occlusion unit tests (D35 / D20): boxes and capsules. */
sealed class Solid(val name: String) {
    class Box(name: String, val lo: V3, val hi: V3) : Solid(name)
    class Capsule(name: String, val a: V3, val b: V3, val r: Float) : Solid(name)

    /** Does the segment [p] -> [q] pass through this solid (small inset so touching edges don't count)? */
    fun hitBy(p: V3, q: V3, inset: Float = 0.0005f): Boolean = when (this) {
        is Box -> segBox(p, q, lo + V3(inset, inset, inset), hi - V3(inset, inset, inset))
        is Capsule -> segSegDist(p, q, a, b) < r - inset
    }

    companion object {
        fun segBox(p: V3, q: V3, lo: V3, hi: V3): Boolean {
            var t0 = 0f; var t1 = 1f
            val d = q - p
            val ps = floatArrayOf(p.x, p.y, p.z); val ds = floatArrayOf(d.x, d.y, d.z)
            val los = floatArrayOf(lo.x, lo.y, lo.z); val his = floatArrayOf(hi.x, hi.y, hi.z)
            for (k in 0..2) {
                if (abs(ds[k]) < 1e-9f) { if (ps[k] < los[k] || ps[k] > his[k]) return false; continue }
                var a = (los[k] - ps[k]) / ds[k]; var b = (his[k] - ps[k]) / ds[k]
                if (a > b) { val t = a; a = b; b = t }
                t0 = maxOf(t0, a); t1 = minOf(t1, b)
                if (t0 > t1) return false
            }
            return true
        }

        /** Shortest distance between segments p1-q1 and p2-q2 (Ericson, Real-Time Collision Detection 5.1.9). */
        fun segSegDist(p1: V3, q1: V3, p2: V3, q2: V3): Float = closest(p1, q1, p2, q2).let { (a, b) -> (a - b).length }

        fun closest(p1: V3, q1: V3, p2: V3, q2: V3): Pair<V3, V3> {
            val d1 = q1 - p1; val d2 = q2 - p2; val r = p1 - p2
            val a = d1.dot(d1); val e = d2.dot(d2); val f = d2.dot(r)
            var s: Float; var t: Float
            if (a <= 1e-12f && e <= 1e-12f) return p1 to p2
            if (a <= 1e-12f) { s = 0f; t = (f / e).coerceIn(0f, 1f) } else {
                val c = d1.dot(r)
                if (e <= 1e-12f) { t = 0f; s = (-c / a).coerceIn(0f, 1f) } else {
                    val b = d1.dot(d2); val den = a * e - b * b
                    s = if (den > 1e-12f) ((b * f - c * e) / den).coerceIn(0f, 1f) else 0f
                    t = (b * s + f) / e
                    if (t < 0f) { t = 0f; s = (-c / a).coerceIn(0f, 1f) } else if (t > 1f) { t = 1f; s = ((b - c) / a).coerceIn(0f, 1f) }
                }
            }
            return (p1 + d1 * s) to (p2 + d2 * t)
        }
    }
}
