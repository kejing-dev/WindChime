package io.embodit.windchime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Design verification: pendulum T60, energy, hard limits (incl. constraint 4), no interpenetration, 72 / 90 fps. */
class PhysicsTest {
    private fun invariants(s: ChimeSim, where: String) {
        val k = s.kind
        assertTrue("$where clapper h ${s.clapperH()}", s.clapperH() <= Geo.CLAPPER_MAX_H + 1e-4f)
        assertTrue("$where sail ${s.sailDeg()}", s.sailDeg() <= Geo.SAIL_MAX_DEG + 0.05f)
        assertTrue("$where tube ${s.maxTubeDeg()}", s.maxTubeDeg() <= Geo.TUBE_MAX_DEG + 0.05f)
        for (i in 0 until 5) {
            // constraint 4: sail cord / outline >= tube radius + 1 mm from every tube axis
            assertTrue("$where cord clearance tube ${i + 1} = ${s.cordClearance(i)}", s.cordClearance(i) >= 0.001f - 2e-4f)
        }
        // clapper vs tubes: the disc is horizontal, so it meets a tube where the tube's axis crosses the disc's plane;
        // the horizontal gap there must not close by more than 2 mm (contacts are resolved within the step)
        val cc = s.clapperCenter()
        for (i in 0 until 5) {
            val d = s.tubeDir(i)
            val t = (Geo.PIVOT_Y - cc.y) / -d.y
            if (t > Geo.TUBE_CORD + k.length(i)) continue
            val p = s.tubePivot(i) + d * t
            val h = V3(cc.x - p.x, 0f, cc.z - p.z).length
            assertTrue("$where clapper in tube ${i + 1}: $h", h >= Geo.CLAPPER_R + k.tubeR - 0.002f)
        }
        for ((a, b) in ChimeSim.RING_PAIRS) {
            val d = Solid.segSegDist(s.tubeTopPt(a), s.tubeBottomPt(a), s.tubeTopPt(b), s.tubeBottomPt(b))
            assertTrue("$where tubes ${a + 1}-${b + 1}: $d", d >= 2 * k.tubeR - 0.002f)
        }
    }

    @Test fun freeSwingT60IsAboutEightSeconds() {
        for (k in ChimeKind.entries) {
            val s = ChimeSim(k, 7)
            s.tAz[2] = -Geo.rad(3f) // tube 3 pushed outward: it swings without reaching the clapper
            var last = 0.0
            repeat(90 * 14) { s.advance(1f / 90); if (s.tiltDeg(s.tAx[2], s.tAz[2]) > 3f / 1000f) last = s.time }
            println("T60 ${k.id} = ${"%.2f".format(last)} s")
            assertTrue("T60 ${k.id} $last", last in 6.4..9.6)
        }
    }

    @Test fun energyOnlyGoesDownWithoutWind() {
        for (k in ChimeKind.entries) {
            val s = ChimeSim(k, 7)
            s.cWx = 1.2f; s.sWz = -2f; s.tWz[0] = 0.5f
            var e = s.energy()
            repeat(20) {
                s.run(0.5f)
                val e2 = s.energy()
                assertTrue("${k.id} energy rose ${e} -> $e2", e2 <= e * 1.001f + 1e-6f)
                e = e2
            }
        }
    }

    @Test fun limitsHoldInAGaleAndWhenTheSailIsPulledEveryWay() {
        for (k in ChimeKind.entries) {
            for (fps in listOf(72, 90)) {
                val s = ChimeSim(k, 3)
                s.windTarget = Phys.WIND_MEAN[3]
                repeat(fps * 60) { s.advance(1f / fps); invariants(s, "${k.id} gale @$fps t=${"%.2f".format(s.time)}") }
                s.windTarget = 0f
                // pull the sail 0.2 m toward 16 directions, hold, release
                for (d in 0 until 16) {
                    val a = 2 * Math.PI * d / 16
                    val c0 = s.sailCenter()
                    for (i in 1..30) { s.grabTarget = c0 + V3((0.2 * sin(a)).toFloat() * i / 30, 0.05f * i / 30, (0.2 * cos(a)).toFloat() * i / 30); s.advance(1f / fps); invariants(s, "${k.id} pull $d") }
                    repeat(fps / 2) { s.advance(1f / fps); invariants(s, "${k.id} hold $d") }
                    s.grabTarget = null
                    repeat(fps) { s.advance(1f / fps); invariants(s, "${k.id} release $d") }
                }
            }
        }
    }

    @Test fun sameResultAt72And90Fps() {
        val a = ChimeSim(ChimeKind.BRASS, 7).apply { windTarget = Phys.WIND_MEAN[2] }
        val b = ChimeSim(ChimeKind.BRASS, 7).apply { windTarget = Phys.WIND_MEAN[2] }
        val na = a.run(10f, 72); val nb = b.run(10f, 90)
        assertEquals(a.time, b.time, 1e-6)
        assertEquals(na, nb)
        assertEquals(a.cAx, b.cAx, 1e-5f); assertEquals(a.sAz, b.sAz, 1e-5f)
    }

    @Test fun seededWindIsReproducible() {
        val a = ChimeSim(ChimeKind.GLASS, 11).apply { windTarget = Phys.WIND_MEAN[3] }
        val b = ChimeSim(ChimeKind.GLASS, 11).apply { windTarget = Phys.WIND_MEAN[3] }
        val la = ArrayList<Strike>(); val lb = ArrayList<Strike>()
        a.run(20f) { la += it }; b.run(20f) { lb += it }
        assertEquals(la, lb)
    }

    @Test fun pullingTheSailAndLettingGoRingsAtLeastThreeNotes() {
        for (k in ChimeKind.entries) {
            val s = ChimeSim(k, 7)
            val c0 = s.sailCenter()
            var n = 0
            for (i in 1..30) { s.grabTarget = c0 + V3(0.12f * i / 30, 0f, 0f); n += s.advance(1f / 90).size }
            n += s.run(1f)
            s.grabTarget = null
            val after = s.run(5f)
            println("M3 ${k.id}: while pulling $n, after release $after")
            assertTrue("${k.id} release strikes $after", after >= 3)
        }
    }

    @Test fun aUserGustRingsARun() {
        for (k in ChimeKind.entries) {
            val s = ChimeSim(k, 7)
            s.gust(1f, 0f, 0.8f)
            val n = s.run(4f)
            println("gust ${k.id}: $n strikes")
            assertTrue("${k.id} gust $n", n >= 2)
        }
    }

    @Test fun stillWindStaysSilentFor30Seconds() {
        for (k in ChimeKind.entries) {
            val s = ChimeSim(k, 7)
            assertEquals(0, s.run(30f))
        }
    }

    @Test fun tubeLengthsMatchTheTable() {
        val table = mapOf(
            ChimeKind.BRASS to floatArrayOf(0.300f, 0.283f, 0.267f, 0.245f, 0.231f),
            ChimeKind.BAMBOO to floatArrayOf(0.300f, 0.283f, 0.260f, 0.245f, 0.231f),
            ChimeKind.GLASS to floatArrayOf(0.300f, 0.275f, 0.260f, 0.238f, 0.225f),
        )
        for ((k, l) in table) for (i in 0 until 5) {
            assertEquals(l[i], k.length(i), 0.001f)
            val s = ChimeSim(k)
            assertEquals(l[i], (s.tubeTopPt(i) - s.tubeBottomPt(i)).length, 0.001f)
        }
        // swapping changes the collision geometry with it
        val s = ChimeSim(ChimeKind.BRASS); s.setKind(ChimeKind.GLASS)
        assertEquals(0.225f, (s.tubeTopPt(4) - s.tubeBottomPt(4)).length, 0.001f)
        // pitch: f ~ 1/L^2 at one diameter within each chime (design math), within 3 %
        for (k in ChimeKind.entries) for (i in 1 until 5) {
            val expect = (k.length(0) / k.length(i)).let { it * it }
            assertEquals(expect, k.notes[i].hz / k.notes[0].hz, 0.03f * expect)
        }
    }

    @Test fun restingGapsMatchTheDesign() {
        // clapper to tube surface at rest: brass 0.022, bamboo 0.016, glass 0.021
        val expect = mapOf(ChimeKind.BRASS to 0.022f, ChimeKind.BAMBOO to 0.016f, ChimeKind.GLASS to 0.021f)
        for ((k, g) in expect) {
            val s = ChimeSim(k)
            val gap = (0 until 5).minOf { i -> V3(Geo.TUBE_XZ[i].first, 0f, Geo.TUBE_XZ[i].second).horiz } - Geo.CLAPPER_R - k.tubeR
            assertEquals(g, gap, 0.0015f)
            assertTrue(abs(s.clapperCenter().y - 0.01f) < 1e-4f)
        }
    }
}
