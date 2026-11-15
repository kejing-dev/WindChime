package io.embodit.windchime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sound rate limits, tap / drag split (M14), the volume slider (B20), the screen strips and the ray fallback (T5). */
class RulesTest {
    @Test fun eachTubeRingsAtMostOncePer120msAndTenPerSecond() {
        val s = ChimeSim(ChimeKind.BRASS, 7)
        s.windTarget = Phys.WIND_MEAN[3]
        val all = ArrayList<Strike>()
        s.run(120f) { all += it }
        for (i in 0 until 5) {
            val t = all.filter { it.tube == i }.map { it.t }
            for (j in 1 until t.size) assertTrue("tube $i gap ${(t[j] - t[j - 1]) * 1000} ms", (t[j] - t[j - 1]) * 1000 >= 119.9)
        }
        for (a in all) assertTrue(all.count { it.t > a.t - 1.0 && it.t <= a.t } <= Phys.MAX_PER_S)
    }

    @Test fun aTapAlwaysRingsAtOnceAndKnocksTheTubeOutward() {
        for (i in 0 until 5) {
            val s = ChimeSim(ChimeKind.GLASS)
            val st = s.tapTube(i)
            assertEquals(i, st.tube)
            assertTrue(st.volume > 0.5f)
            s.run(0.15f)
            val (x, z) = Geo.TUBE_XZ[i]
            val b = s.tubeBottomPt(i)
            assertTrue("tube $i moved outward", (b.x - x) * x + (b.z - z) * z > 0f)
        }
    }

    @Test fun strikeVolumeFollowsTheDesignCurve() {
        assertEquals(0f, Strike(0, 0.03f, Strike.Source.CLAPPER).volume, 1e-6f)
        assertEquals(1f, Strike(0, 0.43f, Strike.Source.CLAPPER).volume, 1e-6f)
        assertEquals(Math.pow(0.5, 0.7).toFloat(), Strike(0, 0.23f, Strike.Source.CLAPPER).volume, 1e-4f)
        assertEquals(0.355f, Strike(0, 1f, Strike.Source.TUBE, Phys.TUBE_TUBE_GAIN).volume, 1e-4f)
    }

    @Test fun shortPressIsATapLongerTravelADragAndAStillHoldIsNeither() {
        assertEquals(TapRule.Kind.TAP, TapRule.classify(0.019f, 340))
        assertEquals(TapRule.Kind.TAP, TapRule.classify(0.0f, 50))
        assertEquals(TapRule.Kind.DRAG, TapRule.classify(0.02f, 100))
        assertEquals(TapRule.Kind.DRAG, TapRule.classify(0.05f, 2000))
        assertEquals(TapRule.Kind.NONE, TapRule.classify(0.01f, 400))
    }

    @Test fun volumeSliderTapsAndSmallDragsAllChangeIt() {
        // taps at 0 / 50 / 100 % of the track
        assertEquals(0f, VolumeSnap.snap(0f), 1e-6f)
        assertEquals(0.5f, VolumeSnap.snap(0.5f), 1e-6f)
        assertEquals(1f, VolumeSnap.snap(1f), 1e-6f)
        // releases near a stop snap to it
        assertEquals(0.35f, VolumeSnap.snap(0.40f), 1e-6f)
        assertEquals(0.65f, VolumeSnap.snap(0.60f), 1e-6f)
        assertEquals(1.0f, VolumeSnap.snap(0.95f), 1e-6f)
        // a small drag from high stays changed (not swallowed back to the stop)
        assertEquals(0.85f, VolumeSnap.snap(0.85f), 1e-6f)
        assertEquals(35, VolumeSnap.percent(0.35f)); assertEquals(100, VolumeSnap.percent(1f))
    }

    @Test fun stripsDoNotOverlapAndEachSitsBehindItsTube() {
        for (k in ChimeKind.entries) {
            val st = Geo.strips(k)
            for (i in 0 until 5) {
                val (lo, hi) = st[i]
                assertTrue(lo < hi)
                assertTrue("tube ${i + 1} x inside its strip", Geo.TUBE_XZ[i].first in lo..hi)
                if (i > 0) assertEquals(st[i - 1].second, lo, 1e-6f)
                assertEquals(i, Geo.stripAt(k, (lo + hi) / 2))
            }
            assertEquals(Geo.TUBE_XZ[0].first - k.tubeR - 0.008f, st[0].first, 1e-6f)
            assertEquals(Geo.TUBE_XZ[4].first + k.tubeR + 0.008f, st[4].second, 1e-6f)
            assertNull(Geo.stripAt(k, 0.2f))
        }
    }

    @Test fun screenTapProjectsBackToTheNearestTubeOrPuffs() {
        val eye = Geo.EYES[0].second
        for (k in ChimeKind.entries) for (i in 0 until 5) {
            val (x, z) = Geo.TUBE_XZ[i]
            val mid = V3(x, Geo.PIVOT_Y - Geo.TUBE_CORD - k.length(i) / 2, z)
            // the point on the screen behind the tube along the eye ray
            val d = mid - eye
            val t = (Geo.SCREEN_C.z + Geo.SCREEN_T / 2 - eye.z) / d.z
            val onScreen = eye + d * t
            assertEquals("${k.id} tube ${i + 1}", i, Geo.tubeNearRay(k, eye, onScreen))
        }
        // far from every tube: nothing (the paper puffs)
        assertNull(Geo.tubeNearRay(ChimeKind.BRASS, eye, V3(0.19f, -0.25f, -0.278f)))
    }
}
