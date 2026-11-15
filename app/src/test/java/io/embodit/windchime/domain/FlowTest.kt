package io.embodit.windchime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flow (states S00..S08 + overlays): every path the window, the volume and the debug broadcast share. */
class FlowTest {
    private class Rig(first: Boolean = true) {
        var settings = Settings(firstRunDone = !first)
        val strikes = ArrayList<Pair<Strike, ChimeKind>>()
        val fx = ArrayList<ChimeFlow.Fx>()
        val flow = ChimeFlow({ settings }, { settings = it }, { s, k -> strikes += s to k }, { fx += it }, {})
        fun run(sec: Float) { repeat((sec * 90).toInt()) { flow.tick(1f / 90) } }
    }

    @Test fun firstRunShowsTheWelcomeAndATouchEndsIt() {
        val r = Rig()
        r.flow.start(); assertEquals(Phase.LOADING, r.flow.ui.value.phase)
        r.flow.sceneReady(); assertEquals(Phase.WELCOME, r.flow.ui.value.phase)
        assertEquals("S01", r.flow.stateId(Page.HOWTO, false, true).substringBefore("+"))
        r.flow.tapTube(2) // touching the chime ends the welcome (S01 exits) and rings at once
        assertEquals(Phase.PLAY, r.flow.ui.value.phase)
        assertTrue(r.settings.firstRunDone)
        assertEquals(1, r.strikes.size)
        // next start goes straight to S02
        val r2 = Rig(first = false); r2.flow.start(); r2.flow.sceneReady(); assertEquals(Phase.PLAY, r2.flow.ui.value.phase)
    }

    @Test fun everyTubeAndTileRingsWithATapAlone() {
        val r = Rig(false); r.flow.sceneReady()
        for (i in 0 until 5) { r.flow.tapTube(i); r.run(0.2f) }
        for (i in 0 until 5) { r.flow.tapToken(i); r.run(0.2f) }
        val tubes = r.strikes.filter { it.first.source == Strike.Source.TAP }.map { it.first.tube }.toSet()
        val tiles = r.strikes.filter { it.first.source == Strike.Source.TOKEN }.map { it.first.tube }.toSet()
        assertEquals(setOf(0, 1, 2, 3, 4), tubes); assertEquals(setOf(0, 1, 2, 3, 4), tiles)
        assertTrue(r.flow.strikeOn())
    }

    @Test fun holdingTheSailLocksTheChimeSwitchAndReleaseSwings() {
        val r = Rig(false); r.flow.sceneReady()
        assertTrue(r.flow.dragStart(Hit.Sail, V3.ZERO))
        assertTrue(r.flow.ui.value.holding)
        assertEquals("S03", r.flow.stateId(Page.PLAY, false, true).substringBefore("+"))
        for (i in 1..20) { r.flow.dragMove(V3(0.006f * i, 0f, 0f)); r.flow.tick(1f / 60) }
        assertFalse(r.flow.setChime(ChimeKind.GLASS)) // greyed: 「先松手」
        assertEquals(ChimeKind.BRASS, r.flow.sim.kind)
        r.run(0.5f)
        r.flow.dragEnd()
        assertFalse(r.flow.ui.value.holding)
        r.run(0.3f)
        assertTrue("swinging after release", r.flow.swinging())
        assertTrue(r.flow.stateId(Page.PLAY, false, true).contains("SWING"))
    }

    @Test fun swappingSettlesThenChangesTubesAndRingsOneThreeFive() {
        val r = Rig(false); r.flow.sceneReady()
        r.flow.setWind(WindLevel.GALE); r.run(2f)
        r.strikes.clear()
        r.flow.setWind(WindLevel.STILL)
        assertTrue(r.flow.setChime(ChimeKind.BAMBOO))
        assertTrue(r.flow.ui.value.swapping)
        assertEquals(ChimeKind.BAMBOO, r.flow.ui.value.tagline)
        r.run(0.25f); assertEquals(ChimeKind.BRASS, r.flow.sim.kind)
        r.run(0.8f)
        assertEquals(ChimeKind.BAMBOO, r.flow.sim.kind)
        assertFalse(r.flow.ui.value.swapping)
        assertNull(r.flow.swapProgress)
        val swap = r.strikes.filter { it.first.source == Strike.Source.SWAP }
        assertEquals(listOf(0, 2, 4), swap.map { it.first.tube })
        assertTrue(swap.all { it.second == ChimeKind.BAMBOO && it.first.gain == Phys.SWAP_GAIN })
        assertEquals(ChimeKind.BAMBOO, r.settings.chime)
        // switching back and forth three times always ends with sound
        for (k in listOf(ChimeKind.GLASS, ChimeKind.BRASS, ChimeKind.BAMBOO)) { r.strikes.clear(); r.flow.setChime(k); r.run(1f); assertEquals(3, r.strikes.count { it.first.source == Strike.Source.SWAP }); assertEquals(k, r.flow.sim.kind) }
    }

    @Test fun aSwipeOnTheScreenBlowsTheWayTheHandMoved() {
        val r = Rig(false); r.flow.sceneReady()
        assertTrue(r.flow.dragStart(Hit.Screen, V3.ZERO))
        for (i in 1..10) { r.flow.dragMove(V3(-0.03f * i, 0.0f, 0f)); r.flow.tick(1f / 90) }
        r.flow.dragEnd()
        val g = r.flow.lastGust!!
        assertTrue("blows to -x", g.dx < -0.9f)
        assertTrue(r.flow.gustOn())
        assertEquals(listOf(ChimeFlow.Fx.GUST), r.fx)
        // a vertical-only swipe blows away from the user
        val r2 = Rig(false); r2.flow.sceneReady()
        r2.flow.dragStart(Hit.Strip(2), V3.ZERO); r2.flow.dragMove(V3(0f, 0.1f, 0f)); r2.flow.dragEnd()
        assertTrue(r2.flow.lastGust!!.dz < -0.9f)
        // gust sound off in settings: silent gust
        val r3 = Rig(false); r3.settings = r3.settings.copy(gustSound = false); r3.flow.gustButton(); assertTrue(r3.fx.isEmpty()); assertTrue(r3.flow.gustOn())
    }

    @Test fun aTapOnTheScreenRingsTheStripsTubeOrPuffs() {
        val r = Rig(false); r.flow.sceneReady()
        r.flow.tapScreen(3); assertEquals(3, r.strikes.last().first.tube)
        r.flow.tapScreen(null); assertEquals(listOf(ChimeFlow.Fx.RUSTLE), r.fx); assertEquals(r.flow.now, r.flow.screenPuff, 1e-9)
    }

    @Test fun panelTileReopensOrFlashes() {
        val r = Rig(false); r.flow.sceneReady()
        var reopened = 0; var flashed = 0
        r.flow.tapPanelTile(false, { reopened++ }, { flashed++ })
        r.flow.tapPanelTile(true, { reopened++ }, { flashed++ })
        assertEquals(1, reopened); assertEquals(1, flashed)
        assertEquals("S07", r.flow.stateId(Page.PLAY, false, false).substringBefore("+"))
    }

    @Test fun windLevelAndSettingsArePersistedAndStillIsSilent() {
        val r = Rig(false); r.flow.sceneReady()
        r.flow.setWind(WindLevel.STEADY); assertEquals(WindLevel.STEADY, r.settings.wind)
        r.flow.setWind(WindLevel.STILL)
        r.run(2f); r.strikes.clear(); r.run(30f)
        assertTrue(r.strikes.isEmpty())
    }

    @Test fun stateIdsNameEveryMainState() {
        val r = Rig(false); r.flow.sceneReady()
        assertEquals("S02", r.flow.stateId(Page.PLAY, false, true).substringBefore("+"))
        assertEquals("S05", r.flow.stateId(Page.SETTINGS, false, true).substringBefore("+"))
        assertEquals("S06", r.flow.stateId(Page.SETTINGS, true, true).substringBefore("+"))
        assertEquals("S08", r.flow.stateId(Page.HOWTO, false, true).substringBefore("+"))
        r.settings = r.settings.copy(muted = true, reducedMotion = true)
        assertTrue(r.flow.stateId(Page.PLAY, false, true).endsWith("W0+MUTE+RM"))
    }

    @Test fun backgroundStopsEverything() {
        val r = Rig(false); r.flow.sceneReady()
        r.flow.dragStart(Hit.Sail, V3.ZERO); r.flow.dragMove(V3(0.1f, 0f, 0f)); r.run(0.5f)
        r.flow.onBackground()
        assertFalse(r.flow.ui.value.holding)
        assertEquals(0f, r.flow.sim.swingDeg(), 1e-6f)
        assertNull(r.flow.sim.grabTarget)
    }

    @Test fun tileLinesMatchTheTableForEveryChimeAndLanguage() {
        val zh = mapOf(ChimeKind.BRASS to listOf("宫" to "C", "商" to "D", "角" to "E", "徵" to "G", "羽" to "A"),
            ChimeKind.BAMBOO to listOf("徵" to "G", "羽" to "A", "宫" to "C", "商" to "D", "角" to "E"),
            ChimeKind.GLASS to listOf("角" to "E", "徵" to "G", "羽" to "A", "宫" to "C", "商" to "D"))
        val sol = mapOf("宫" to "do", "商" to "re", "角" to "mi", "徵" to "sol", "羽" to "la")
        for ((k, lines) in zh) for (i in 0 until 5) {
            assertEquals(lines[i], k.notes[i].tileLines(true))
            assertEquals(lines[i].second to sol.getValue(lines[i].first), k.notes[i].tileLines(false))
        }
    }
}
