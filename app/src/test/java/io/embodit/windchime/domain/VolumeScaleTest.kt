package io.embodit.windchime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA #1 q1-2: the user resizes the volume uniformly (0.43x .. 3.6x). At 0.5x / 1x / 3x the measured size gives the root
 * scale, a hand travel becomes content metres / scale (sail grab, gust swipe), and a hit point on the paper still finds
 * the tube behind it.
 */
class VolumeScaleTest {
    private val scales = listOf(0.5f, 1f, 3f)

    private fun rig(): ChimeFlow {
        var st = Settings(firstRunDone = true)
        return ChimeFlow({ st }, { st = it }, { _, _, _ -> }, { }, {}).apply { start(); sceneReady() }
    }

    @Test fun measuredSizeGivesTheRootScale() {
        for (k in scales) {
            val vs = VolumeScale.measured(-Geo.VOL_W / 2 * k, Geo.VOL_H / 2 * k)
            assertEquals("$k x", k, vs.root, 1e-4f)
            assertEquals(1f / k, vs.contentPerPhysical, 1e-4f)
        }
        // system-scaled space instead (view px stay, entity metres per px grow): the view ratio carries it
        val sys = VolumeScale.measured(Geo.VOL_W / 2, Geo.VOL_H / 2, entityPerPx = 0.0008f, physicalPerPx = 0.0004f)
        assertEquals(1f, sys.root, 1e-4f); assertEquals(2f, sys.contentPerPhysical, 1e-4f)
        // nonsense -> 1x, never a negative or zero scale
        assertEquals(1f, VolumeScale.measured(0f, 0f).root, 0f)
        assertEquals(VolumeScale.MAX, VolumeScale.measured(100f, 100f).root, 0f)
    }

    @Test fun sailGrabFollowsTheHandAtEveryScale() {
        for (k in scales) {
            val vs = VolumeScale.measured(Geo.VOL_W / 2 * k, Geo.VOL_H / 2 * k)
            val f = rig()
            val c0 = f.sim.sailCenter()
            assertTrue(f.dragStart(Hit.Sail, V3.ZERO))
            val hand = V3(0.06f * k, 0f, 0f) // the hand moves 6 cm x k: the sail should move 6 cm of the drawn chime
            f.dragMove(vs.toContent(hand))
            val g = f.sim.grabTarget!!
            assertEquals("$k x content", 0.06f, g.x - c0.x, 1e-4f)
            // on screen (root-scaled) the grab point moved exactly as far as the hand
            assertEquals("$k x world", hand.x, (g.x - c0.x) * vs.root, 1e-4f)
            f.dragEnd()
        }
    }

    @Test fun gustSwipeStrengthIsTheSameForTheSameVisibleSwipe() {
        val got = scales.map { k ->
            val vs = VolumeScale.measured(Geo.VOL_W / 2 * k, Geo.VOL_H / 2 * k)
            val f = rig()
            assertTrue(f.dragStart(Hit.Screen, V3.ZERO))
            for (i in 1..8) { f.dragMove(vs.toContent(V3(0.20f * k * i / 8, 0f, 0f))); repeat(3) { f.tick(1f / 90) } }
            f.dragEnd()
            f.lastGust!!
        }
        for (g in got) assertEquals(got[1].toString(), g.toString())
    }

    @Test fun paperTapFindsTheTubeBehindItAtEveryScale() {
        for (k in scales) {
            val vs = VolumeScale.measured(Geo.VOL_W / 2 * k, Geo.VOL_H / 2 * k)
            val eyeWorld = Geo.EYES[0].second * k  // the user sits k x as far, proportionally (the volume grew around the chime)
            for (i in 0 until 5) {
                val kind = ChimeKind.BRASS
                val (x, z) = Geo.TUBE_XZ[i]
                val mid = V3(x, Geo.PIVOT_Y - Geo.TUBE_CORD - kind.length(i) / 2, z)
                val eyeC = vs.eyeToContent(eyeWorld)
                val d = mid - eyeC
                val t = (Geo.SCREEN_C.z + Geo.SCREEN_T / 2 - eyeC.z) / d.z
                val hitLocal = (eyeC + d * t) * k // where the ray meets the (scaled) paper in the view's local space
                assertEquals("$k x tube ${i + 1}", i, Geo.tubeNearRay(kind, vs.eyeToContent(eyeWorld), vs.pointToContent(hitLocal)))
            }
        }
    }
}
