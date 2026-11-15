package io.embodit.windchime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/**
 * container.volume.math + occlusionCheck: every part inside the volume (rest + 16 directions at the limits), wind
 * lines inside, D35 tiles vs every part from 6 eyes, D41 control window vs chime, D20, tile size / angle (D17, D13).
 */
class LayoutTest {
    /** Every fixed part as solids (stand, screen, disc). */
    private fun fixedParts(): List<Solid> {
        val s = ArrayList<Solid>()
        s += Solid.Box("base", V3(Geo.BASE_X0, Geo.BASE_Y0, Geo.BASE_Z0), V3(Geo.BASE_X1, Geo.BASE_Y1, Geo.BASE_Z1))
        s += Solid.Capsule("column", V3(0f, Geo.BASE_Y1, Geo.COLUMN_Z), V3(0f, Geo.COLUMN_TOP, Geo.COLUMN_Z), Geo.COLUMN_R)
        s += Solid.Capsule("arm", V3(0f, Geo.ARM_Y, Geo.COLUMN_Z), V3(0f, Geo.ARM_Y, 0f), Geo.ARM_R)
        s += Solid.Box("disc", V3(-Geo.DISC_R, Geo.DISC_Y0, -Geo.DISC_R), V3(Geo.DISC_R, Geo.DISC_Y1, Geo.DISC_R))
        val c = Geo.SCREEN_C
        s += Solid.Box("screen", V3(-Geo.SCREEN_W / 2, c.y - Geo.SCREEN_H / 2, c.z - Geo.SCREEN_T / 2), V3(Geo.SCREEN_W / 2, c.y + Geo.SCREEN_H / 2, c.z + Geo.SCREEN_T / 2))
        return s
    }

    /** The chime's moving parts in [sim]'s pose. */
    private fun movingParts(sim: ChimeSim): List<Solid> {
        val k = sim.kind
        val s = ArrayList<Solid>()
        for (i in 0 until 5) s += Solid.Capsule("tube${i + 1}", sim.tubeTopPt(i), sim.tubeBottomPt(i), k.tubeR)
        val cc = sim.clapperCenter()
        s += Solid.Capsule("clapper", cc - V3(Geo.CLAPPER_R - 0.006f, 0f, 0f), cc + V3(Geo.CLAPPER_R - 0.006f, 0f, 0f), 0.006f)
        s += Solid.Capsule("cord", sim.clapperPivot, cc, 0.001f)
        s += Solid.Capsule("sailCord", sim.sailPivot(), sim.sailPoint(k.sailCordL), 0.001f)
        val c = sim.sailCorners()
        s += Solid.Capsule("sailTop", c[0], c[1], k.sailT / 2); s += Solid.Capsule("sailBottom", c[3], c[2], k.sailT / 2)
        s += Solid.Capsule("sailMid", sim.sailPoint(k.sailCordL), sim.sailPoint(k.sailBottomL), k.sailW / 2)
        return s
    }

    private fun points(sim: ChimeSim): List<V3> {
        val k = sim.kind
        val p = ArrayList<V3>()
        for (i in 0 until 5) { val r = k.tubeR; for (q in listOf(sim.tubeTopPt(i), sim.tubeBottomPt(i))) for (d in listOf(V3(r, 0f, 0f), V3(-r, 0f, 0f), V3(0f, 0f, r), V3(0f, 0f, -r))) p += q + d }
        val cc = sim.clapperCenter()
        for (d in listOf(V3(Geo.CLAPPER_R, 0f, 0f), V3(-Geo.CLAPPER_R, 0f, 0f), V3(0f, 0f, Geo.CLAPPER_R), V3(0f, 0f, -Geo.CLAPPER_R))) p += cc + d
        val (_, nrm) = sim.sailAxes()
        for (c in sim.sailCorners()) { p += c + nrm * (k.sailT / 2); p += c - nrm * (k.sailT / 2) }
        return p
    }

    @Test fun everyPartStaysInsideTheVolumeAtRestAndAtTheLimits() {
        val fixed = listOf(V3(Geo.BASE_X0, Geo.BASE_Y0, Geo.BASE_Z0), V3(Geo.BASE_X1, Geo.BASE_Y1, Geo.BASE_Z1),
            V3(-Geo.SCREEN_W / 2, Geo.SCREEN_C.y - Geo.SCREEN_H / 2, Geo.SCREEN_C.z - Geo.SCREEN_T / 2), V3(Geo.SCREEN_W / 2, Geo.SCREEN_C.y + Geo.SCREEN_H / 2, Geo.SCREEN_C.z),
            V3(0f, Geo.COLUMN_TOP + Geo.ARM_R, Geo.COLUMN_Z - Geo.COLUMN_R), Geo.PANEL_C + V3(Geo.PANEL_W / 2, -Geo.PANEL_H / 2, 0f))
        fixed.forEach { assertTrue("fixed $it", Geo.inVolume(it, 0.004f)) }
        for (k in ChimeKind.entries) {
            val rest = ChimeSim(k)
            points(rest).forEach { assertTrue("${k.id} rest $it", Geo.inVolume(it)) }
            var far = 0f
            for (d in 0 until 16) {
                val a = 2 * Math.PI * d / 16
                val s = ChimeSim(k)
                val lt = Geo.rad(Geo.TUBE_MAX_DEG); val ls = Geo.rad(Geo.SAIL_MAX_DEG)
                val lc = kotlin.math.asin(Geo.CLAPPER_MAX_H / Geo.CLAPPER_CORD)
                for (i in 0 until 5) { s.tAx[i] = (lt * sin(a)).toFloat(); s.tAz[i] = (lt * cos(a)).toFloat() }
                s.cAx = (lc * sin(a)).toFloat(); s.cAz = (lc * cos(a)).toFloat()
                s.sAx = (ls * sin(a)).toFloat(); s.sAz = (ls * cos(a)).toFloat()
                for (p in points(s)) { assertTrue("${k.id} dir $d $p", Geo.inVolume(p)); far = maxOf(far, p.horiz) }
                // the sail swung back never reaches the column (front face z = -0.227)
                s.sailCorners().forEach { c -> assertTrue("${k.id} sail vs column", c.z > Geo.COLUMN_Z + Geo.COLUMN_R || abs(c.x) > Geo.COLUMN_R) }
            }
            println("${k.id}: farthest horizontal reach at the limits ${"%.3f".format(far)} m (volume half 0.30)")
        }
    }

    @Test fun windLinesStayInsideTheVolume() {
        for (dir in 0 until 12) for (i in 0 until 6) for (t in listOf(0f, 0.5f, 1f)) {
            val a = 2 * Math.PI * dir / 12
            val dx = sin(a).toFloat(); val dz = cos(a).toFloat()
            val lane = (i - 2.5f) * 0.035f; val h = -0.10f + (i % 3) * 0.12f
            val along = -0.16f + 0.32f * t
            val c = V3(dx * along - dz * lane, h, dz * along + dx * lane)
            val half = (0.12f + 0.016f * i) / 2
            for (e in listOf(c + V3(dx * half, 0f, dz * half), c - V3(dx * half, 0f, dz * half))) assertTrue("wind $dir $i $t $e", Geo.inVolume(e))
        }
    }

    private fun tileCorners(i: Int): List<V3> {
        val t = Geo.rad(Geo.TOKEN_TILT_DEG)
        val c = V3(Geo.TOKEN_X[i], Geo.TOKEN_Y, Geo.TOKEN_Z)
        val up = Geo.TOKEN_H / 2
        return listOf(-1f, 1f).flatMap { sx -> listOf(-1f, 1f).map { sz ->
            val lx = sx * Geo.TOKEN_W / 2; val lz = sz * Geo.TOKEN_D / 2
            // rotate (lx, up, lz) about X by +35 deg
            c + V3(lx, up * cos(t) - lz * sin(t), up * sin(t) + lz * cos(t))
        } }
    }

    @Test fun d35TilesAreNotHiddenFromAnyEyeAtRest() {
        for (k in ChimeKind.entries) {
            val sim = ChimeSim(k)
            val parts = fixedParts() + movingParts(sim)
            for ((eyeName, eye) in Geo.EYES) for (i in 0 until 5) for (c in tileCorners(i)) for (p in parts) {
                assertTrue("${k.id} $eyeName tile ${i + 1} corner $c hidden by ${p.name}", !p.hitBy(eye, c))
            }
            // the panel tile from the two centre eyes
            for ((eyeName, eye) in Geo.EYES.take(2)) for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
                val c = Geo.PANEL_C + V3(sx * Geo.PANEL_W / 2, sy * Geo.PANEL_H / 2, Geo.PANEL_T / 2)
                for (p in parts.filter { it.name != "screen" }) assertTrue("${k.id} $eyeName panel tile hidden by ${p.name}", !p.hitBy(eye, c))
            }
        }
    }

    @Test fun d41ControlWindowNeverOverlapsTheChimeAndClearsTenDegreesFromTheCentreEyes() {
        // chime's rightmost static extents seen from each eye vs the window's left edge (x = 0.35, z = 0)
        val right = listOf(V3(Geo.BASE_X1, Geo.BASE_Y1, Geo.BASE_Z1), V3(Geo.SCREEN_W / 2, Geo.SCREEN_C.y, Geo.SCREEN_C.z + Geo.SCREEN_T / 2), V3(Geo.BASE_X1, Geo.BASE_Y0, Geo.BASE_Z0))
        val edge = V3(Geo.WINDOW_LEFT_X, 0f, Geo.WINDOW_Z)
        for ((name, eye) in Geo.EYES) {
            val win = Geo.bearing(eye, edge)
            val chime = right.maxOf { Geo.bearing(eye, it) }
            val gap = win - chime
            println("D41 $name: window ${"%.1f".format(win)} deg, chime ${"%.1f".format(chime)} deg, gap ${"%.1f".format(gap)} deg")
            assertTrue("$name overlaps", gap > 0f)
            if (name == "sit" || name == "stand") assertTrue("$name gap $gap < 10", gap >= 10f)
        }
    }

    @Test fun d20NothingSwingsIntoTheLineToTheWindow() {
        // the line from the sitting eye to the window edge stays farther from the chime axis than the sail can reach
        val eye = Geo.EYES[0].second
        val edge = V3(Geo.WINDOW_LEFT_X, 0f, Geo.WINDOW_Z)
        val d = Solid.segSegDist(eye, edge, V3(0f, -0.25f, 0f), V3(0f, 0.3f, 0f))
        assertTrue("eye-window line $d m from the axis", d > 0.19f)
    }

    @Test fun tilesAreBigEnoughToHitAndToRead() {
        val eye = Geo.EYES[0].second
        for (i in 0 until 5) {
            val c = V3(Geo.TOKEN_X[i], Geo.TOKEN_Y, Geo.TOKEN_Z)
            val dist = (c - eye).length
            val deg = Geo.deg(2 * atan(Geo.TOKEN_W / 2 / dist))
            assertTrue("tile ${i + 1} ${"%.1f".format(deg)} deg", deg >= 3f)
            // the carved letter is ~48 % of the 3.4 cm face (Labels: 92 px of 192): >= 0.5 deg (D13 3D words)
            val letter = 0.034f * 0.48f
            assertTrue(Geo.deg(atan(letter / dist)) >= 0.5f)
        }
        // neighbouring tile colliders never overlap
        for (i in 1 until 5) assertTrue(Geo.TOKEN_X[i] - Geo.TOKEN_X[i - 1] >= Geo.TOKEN_W + 0.012f)
    }

    @Test fun d14NothingInTheVolumeComesTowardTheFace() {
        // the most forward thing (sail at its limit, 0.188 m) stays inside the volume front (+0.30)
        assertTrue(0.035f + 0.224f * sin(Geo.rad(35f)) + 0.03f * cos(Geo.rad(35f)) < 0.30f)
        assertEquals(0.60f, Geo.VOL_W, 1e-6f)
    }
}
