package io.embodit.windchime.ui.scene

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.windchime.domain.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Plain vertex / index lists, then one MeshModel. Triangles are wound to face their vertex normals. */
internal class MeshData(cap: Int = 64) {
    val p = ArrayList<Float>(cap * 3)
    val n = ArrayList<Float>(cap * 3)
    val uv = ArrayList<Float>(cap * 2)
    val idx = ArrayList<Int>(cap * 3)
    val count: Int get() = p.size / 3
    val triangles: Int get() = idx.size / 3

    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float = 0f, w: Float = 0f): Int {
        p += x; p += y; p += z; n += nx; n += ny; n += nz; uv += u; uv += w
        return count - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        val ax = p[a * 3]; val ay = p[a * 3 + 1]; val az = p[a * 3 + 2]
        val ux = p[b * 3] - ax; val uy = p[b * 3 + 1] - ay; val uz = p[b * 3 + 2] - az
        val vx = p[c * 3] - ax; val vy = p[c * 3 + 1] - ay; val vz = p[c * 3 + 2] - az
        val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
        val mx = n[a * 3] + n[b * 3] + n[c * 3]; val my = n[a * 3 + 1] + n[b * 3 + 1] + n[c * 3 + 1]; val mz = n[a * 3 + 2] + n[b * 3 + 2] + n[c * 3 + 2]
        if (fx * mx + fy * my + fz * mz >= 0f) { idx += a; idx += b; idx += c } else { idx += a; idx += c; idx += b }
    }

    fun add(o: MeshData): MeshData {
        val base = count
        p.addAll(o.p); n.addAll(o.n); uv.addAll(o.uv); o.idx.forEach { idx += it + base }
        return this
    }

    fun bounds(): Pair<FloatArray, FloatArray> {
        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until count) for (k in 0..2) { lo[k] = min(lo[k], p[i * 3 + k]); hi[k] = max(hi[k], p[i * 3 + k]) }
        return lo to hi
    }

    fun model(): Pair<MeshModel, BoundingBox> {
        val pos = ArrayList<Vector3>(count); val nor = ArrayList<Vector3>(count); val tex = ArrayList<Vector2>(count)
        for (i in 0 until count) {
            pos += Vector3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
            nor += Vector3(n[i * 3], n[i * 3 + 1], n[i * 3 + 2])
            tex += Vector2(uv[i * 2], uv[i * 2 + 1])
        }
        val (lo, hi) = bounds()
        val bounds = BoundingBox(
            center = Vector3((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2),
            halfExtent = Vector3(max((hi[0] - lo[0]) / 2, 1e-3f), max((hi[1] - lo[1]) / 2, 1e-3f), max((hi[2] - lo[2]) / 2, 1e-3f)),
        )
        return MeshModel(positions = pos, triangleIndices = ArrayList(idx), normals = nor, uv0 = tex) to bounds
    }

    fun create(name: String): MeshResource { val (m, b) = model(); return MeshResource.createWithMeshModel(model = m, bounds = b, name = name) }
}

/**
 * Every visible shape is generated here (design referenceRepo.assets: true round meshes, 24 sides, smooth normals; no
 * SDK primitives for visible parts, B12). Units: metres.
 */
internal object Meshes {
    /**
     * A round rod from [a] to [b], radius [r], [seg] sides, smooth side normals, flat caps. [slant] tilts the end at
     * [a] (bamboo's 30-degree cut): that end's ring is offset along the axis by x * tan(slant).
     */
    fun rod(a: V3, b: V3, r: Float, seg: Int = 24, caps: Boolean = true, slantDeg: Float = 0f, vRepeat: Float = 1f): MeshData {
        val m = MeshData((seg + 1) * 4 + 2)
        val ax = b - a; val len = ax.length; val d = ax * (1f / len)
        // basis perpendicular to the axis
        val ref = if (abs(d.y) < 0.9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)
        var u = V3(d.y * ref.z - d.z * ref.y, d.z * ref.x - d.x * ref.z, d.x * ref.y - d.y * ref.x)
        u = u * (1f / u.length)
        val w = V3(d.y * u.z - d.z * u.y, d.z * u.x - d.x * u.z, d.x * u.y - d.y * u.x)
        val t = tan(Math.toRadians(slantDeg.toDouble())).toFloat()
        val ringA = ArrayList<Int>(); val ringB = ArrayList<Int>()
        for (i in 0..seg) {
            val ang = (2 * PI * i / seg).toFloat()
            val c = cos(ang); val s = sin(ang)
            val nrm = u * c + w * s
            val off = nrm * r
            val pa = a + off + d * (r * c * t)
            val pb = b + off
            ringA += m.v(pa.x, pa.y, pa.z, nrm.x, nrm.y, nrm.z, i.toFloat() / seg, 0f)
            ringB += m.v(pb.x, pb.y, pb.z, nrm.x, nrm.y, nrm.z, i.toFloat() / seg, vRepeat * len / 0.3f)
        }
        for (i in 0 until seg) { m.tri(ringA[i], ringB[i], ringA[i + 1]); m.tri(ringA[i + 1], ringB[i], ringB[i + 1]) }
        if (caps) {
            for ((end, sign, slanted) in listOf(Triple(a, -1f, true), Triple(b, 1f, false))) {
                val cn = d * sign
                val centre = m.v(end.x, end.y, end.z, cn.x, cn.y, cn.z, 0.5f, 0.5f)
                val ring = (0..seg).map { i ->
                    val ang = (2 * PI * i / seg).toFloat(); val c = cos(ang); val s = sin(ang)
                    val pp = end + (u * c + w * s) * r + (if (slanted) d * (r * c * t) else V3.ZERO)
                    m.v(pp.x, pp.y, pp.z, cn.x, cn.y, cn.z, 0.5f + 0.5f * c, 0.5f + 0.5f * s)
                }
                for (i in 0 until seg) m.tri(centre, ring[i], ring[i + 1])
            }
        }
        return m
    }

    /** A rounded box ("倒角盒"), [w] x [h] x [d], edge radius [r], centred on the origin; UVs planar per face. */
    fun roundedBox(w: Float, h: Float, d: Float, r: Float, grid: Int = 6, uvScale: Float = 1f): MeshData {
        val m = MeshData(6 * (grid + 1) * (grid + 1))
        val hx = w / 2; val hy = h / 2; val hz = d / 2
        val rr = min(r, min(hx, min(hy, hz)) * 0.99f)
        // faces: normal axis, sign
        val faces = listOf(0 to 1f, 0 to -1f, 1 to 1f, 1 to -1f, 2 to 1f, 2 to -1f)
        for ((axis, sign) in faces) {
            val base = m.count
            for (j in 0..grid) for (i in 0..grid) {
                val s = -1f + 2f * i / grid; val t = -1f + 2f * j / grid
                val q = FloatArray(3)
                when (axis) {
                    0 -> { q[0] = sign * hx; q[1] = t * hy; q[2] = -sign * s * hz }
                    1 -> { q[0] = s * hx; q[1] = sign * hy; q[2] = -sign * t * hz }
                    else -> { q[0] = sign * s * hx; q[1] = t * hy; q[2] = sign * hz }
                }
                val inner = floatArrayOf(q[0].coerceIn(-hx + rr, hx - rr), q[1].coerceIn(-hy + rr, hy - rr), q[2].coerceIn(-hz + rr, hz - rr))
                var nx = q[0] - inner[0]; var ny = q[1] - inner[1]; var nz = q[2] - inner[2]
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l < 1e-7f) { nx = 0f; ny = 0f; nz = 0f; when (axis) { 0 -> nx = sign; 1 -> ny = sign; else -> nz = sign } } else { nx /= l; ny /= l; nz /= l }
                val px = inner[0] + nx * rr; val py = inner[1] + ny * rr; val pz = inner[2] + nz * rr
                val (uu, vv) = when (axis) { 0 -> (pz / 0.3f) to (py / 0.3f); 1 -> (px / 0.3f) to (pz / 0.3f); else -> (px / 0.3f) to (py / 0.3f) }
                m.v(px, py, pz, nx, ny, nz, uu * uvScale + 0.5f, vv * uvScale + 0.5f)
            }
            for (j in 0 until grid) for (i in 0 until grid) {
                val a = base + j * (grid + 1) + i; val b = a + 1; val c = a + grid + 1; val e = c + 1
                m.tri(a, b, e); m.tri(a, e, c)
            }
        }
        return m
    }

    /**
     * A flat plate in the XY plane extruded [t] along Z (sails): [outline] counter-clockwise (x, y) points, centred.
     * Front / back faces get UVs from the outline's bounds (v = 1 at the top, so a texture reads upright from +Z).
     */
    fun plate(outline: List<Pair<Float, Float>>, t: Float): MeshData {
        val m = MeshData(outline.size * 6)
        val xs = outline.map { it.first }; val ys = outline.map { it.second }
        val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
        fun u(x: Float) = (x - x0) / (x1 - x0)
        fun vv(y: Float) = (y - y0) / (y1 - y0)
        for (sign in listOf(1f, -1f)) {
            val z = sign * t / 2
            val c = m.v((x0 + x1) / 2, (y0 + y1) / 2, z, 0f, 0f, sign, 0.5f, 0.5f)
            val ring = outline.map { (x, y) -> m.v(x, y, z, 0f, 0f, sign, if (sign > 0) u(x) else 1f - u(x), vv(y)) }
            for (i in ring.indices) m.tri(c, ring[i], ring[(i + 1) % ring.size])
        }
        for (i in outline.indices) {
            val (ax, ay) = outline[i]; val (bx, by) = outline[(i + 1) % outline.size]
            var nx = by - ay; var ny = -(bx - ax); val l = sqrt(nx * nx + ny * ny); nx /= l; ny /= l
            val a1 = m.v(ax, ay, t / 2, nx, ny, 0f, u(ax), vv(ay)); val a2 = m.v(ax, ay, -t / 2, nx, ny, 0f, u(ax), vv(ay))
            val b1 = m.v(bx, by, t / 2, nx, ny, 0f, u(bx), vv(by)); val b2 = m.v(bx, by, -t / 2, nx, ny, 0f, u(bx), vv(by))
            m.tri(a1, b1, a2); m.tri(a2, b1, b2)
        }
        return m
    }

    /** Leaf outline (the walnut sail): pointed at the bottom, round shoulders; top edge at y = 0, hanging down to -h. */
    fun leaf(w: Float, h: Float, n: Int = 40): List<Pair<Float, Float>> = (0 until n).map { i ->
        val a = 2 * PI * i / n
        val x = (w / 2 * sin(a)).toFloat()
        val yy = cos(a).toFloat()
        // pointier at the bottom: squeeze the lower half
        val y = -h / 2 + h / 2 * yy
        val x2 = if (yy < 0) x * (1f + 0.35f * yy) else x
        x2 to y
    }.reversed().let { pts -> if (area(pts) < 0) pts.reversed() else pts }

    /** Rounded rectangle outline, top edge at y = 0. */
    fun roundRect(w: Float, h: Float, r: Float, seg: Int = 6): List<Pair<Float, Float>> {
        val pts = ArrayList<Pair<Float, Float>>()
        val cs = listOf(Triple(w / 2 - r, -h + r, -PI / 2), Triple(w / 2 - r, -r, 0.0), Triple(-w / 2 + r, -r, PI / 2), Triple(-w / 2 + r, -h + r, PI))
        for ((cx, cy, a0) in cs) for (i in 0..seg) { val a = a0 + PI / 2 * i / seg; pts += (cx + r * cos(a)).toFloat() to (cy + r * sin(a)).toFloat() }
        return pts
    }

    private fun area(pts: List<Pair<Float, Float>>): Float {
        var s = 0f
        for (i in pts.indices) { val (ax, ay) = pts[i]; val (bx, by) = pts[(i + 1) % pts.size]; s += ax * by - bx * ay }
        return s / 2
    }

    /** A flat rectangle facing [normalUp] = +Y (true) or +Z (false), centred; image top toward -Z / +Y. */
    fun quad(w: Float, h: Float, up: Boolean): MeshData {
        val m = MeshData(4)
        if (up) {
            val a = m.v(-w / 2, 0f, h / 2, 0f, 1f, 0f, 0f, 0f); val b = m.v(w / 2, 0f, h / 2, 0f, 1f, 0f, 1f, 0f)
            val c = m.v(w / 2, 0f, -h / 2, 0f, 1f, 0f, 1f, 1f); val d = m.v(-w / 2, 0f, -h / 2, 0f, 1f, 0f, 0f, 1f)
            m.tri(a, b, c); m.tri(a, c, d)
        } else {
            val a = m.v(-w / 2, -h / 2, 0f, 0f, 0f, 1f, 0f, 0f); val b = m.v(w / 2, -h / 2, 0f, 0f, 0f, 1f, 1f, 0f)
            val c = m.v(w / 2, h / 2, 0f, 0f, 0f, 1f, 1f, 1f); val d = m.v(-w / 2, h / 2, 0f, 0f, 0f, 1f, 0f, 1f)
            m.tri(a, b, c); m.tri(a, c, d)
        }
        return m
    }

    /** A thin curved ribbon along +X (a wind line): length [len], width [w], one gentle S-bend, facing +Y and +Z (two strips). */
    fun ribbon(len: Float, w: Float, bend: Float, n: Int = 24): MeshData {
        val m = MeshData((n + 1) * 4)
        for (face in 0..1) {
            val ring = ArrayList<Pair<Int, Int>>()
            for (i in 0..n) {
                val t = i.toFloat() / n
                val x = -len / 2 + len * t
                val y = bend * sin(2 * PI * t).toFloat() * 0.5f
                val z = bend * sin(PI * t).toFloat()
                if (face == 0) ring += m.v(x, y - w / 2, z, 0f, 0f, 1f, t, 0f) to m.v(x, y + w / 2, z, 0f, 0f, 1f, t, 1f)
                else ring += m.v(x, y, z - w / 2, 0f, 1f, 0f, t, 0f) to m.v(x, y, z + w / 2, 0f, 1f, 0f, t, 1f)
            }
            for (i in 0 until n) { val (a, b) = ring[i]; val (c, d) = ring[i + 1]; m.tri(a, c, b); m.tri(b, c, d) }
        }
        return m
    }

    /** A torus in the XZ plane (the hook, the bamboo node). */
    fun torus(R: Float, r: Float, seg: Int = 24, sides: Int = 12, sweep: Float = (2 * PI).toFloat()): MeshData {
        val m = MeshData((seg + 1) * (sides + 1))
        for (i in 0..seg) {
            val a = sweep * i / seg
            val ca = cos(a); val sa = sin(a)
            for (j in 0..sides) {
                val b = (2 * PI * j / sides).toFloat()
                val cb = cos(b); val sb = sin(b)
                val rr = R + r * cb
                m.v(rr * sa, r * sb, rr * ca, cb * sa, sb, cb * ca, i.toFloat() / seg, j.toFloat() / sides)
            }
        }
        val k = sides + 1
        for (i in 0 until seg) for (j in 0 until sides) { val a = i * k + j; val b = a + k; m.tri(a, b, a + 1); m.tri(a + 1, b, b + 1) }
        return m
    }

    /** The hook under the arm: a torus arc in the XY plane (rotated from XZ). */
    fun hook(R: Float, r: Float): MeshData {
        val t = torus(R, r, 16, 10, (1.4 * PI).toFloat())
        // rotate 90 deg about X: (x, y, z) -> (x, -z, y)
        val m = MeshData(t.count)
        for (i in 0 until t.count) {
            m.v(t.p[i * 3], -t.p[i * 3 + 2], t.p[i * 3 + 1], t.n[i * 3], -t.n[i * 3 + 2], t.n[i * 3 + 1], t.uv[i * 2], t.uv[i * 2 + 1])
        }
        m.idx.addAll(t.idx)
        return m
    }
}
