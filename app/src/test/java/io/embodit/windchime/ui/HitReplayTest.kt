package io.embodit.windchime.ui

import io.embodit.windchime.domain.ChimeFlow
import io.embodit.windchime.domain.ChimeKind
import io.embodit.windchime.domain.Hit
import io.embodit.windchime.domain.Palette
import io.embodit.windchime.domain.Settings
import io.embodit.windchime.domain.Strike
import io.embodit.windchime.ui.scene.ChimeTargets
import io.embodit.windchime.ui.scene.input.parseLoggedPath
import io.embodit.windchime.ui.scene.route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * build.md 命中回放: real hit chains (mesh child first, then its parents, as the input log prints them) resolve to a
 * holder, and every holder reaches the right flow call through [route] (the same function real input uses).
 */
class HitReplayTest {
    @Test fun everyHitChainResolvesToItsHolder() {
        val cases = mapOf(
            "tube:3 < tubeNode:2 < chime < root" to "tube:3",
            "bambooNode:1 < tube:2 < tubeNode:1 < chime < root" to "tube:2",
            "glow:brass:4 < tubeNode:4 < chime < root" to null,
            "tokenFace:0 < token:1 < root" to "token:1",
            "sail < sailNode < root" to "sail",
            "screen < screenRoot < root" to "screen",
            "panelFace < panel < root" to "panel",
            "strip:5 < root" to "strip:5",
            "column < stand < root" to null,
            "clapper < clapperNode < chime < root" to null,
        )
        for ((path, want) in cases) assertEquals(path, want, ChimeTargets.resolvePath(parseLoggedPath(path)))
        for (n in Hit.NAMES) assertEquals(n, ChimeTargets.resolvePath(listOf(n)))
    }

    @Test fun everyHolderReachesItsFlowCall() {
        var s = Settings(firstRunDone = true)
        val strikes = ArrayList<Strike>(); val fx = ArrayList<ChimeFlow.Fx>()
        val flow = ChimeFlow({ s }, { s = it }, { st, _, _ -> strikes += st }, { fx += it }, {})
        flow.sceneReady()
        for (i in 1..5) { route(flow, Hit.of("tube:$i"), null, true); assertEquals(i - 1, strikes.last().tube); repeat(20) { flow.tick(1f / 90) } }
        for (i in 1..5) { route(flow, Hit.of("token:$i"), null, true); assertEquals(Strike.Source.TOKEN, strikes.last().source); assertEquals(i - 1, strikes.last().tube); repeat(20) { flow.tick(1f / 90) } }
        for (i in 1..5) { route(flow, Hit.of("strip:$i"), null, true); assertEquals(i - 1, strikes.last().tube); repeat(20) { flow.tick(1f / 90) } }
        val before = flow.sim.sWz
        route(flow, Hit.of("sail"), null, true); assertTrue(flow.sim.sWz < before)
        route(flow, Hit.of("screen"), null, true); assertEquals(ChimeFlow.Fx.RUSTLE, fx.last())
        var reopened = false
        route(flow, Hit.of("panel"), null, false, reopen = { reopened = true }); assertTrue(reopened)
        assertEquals(Hit.None, Hit.of("tube:9")); assertNull(ChimeTargets.resolvePath(listOf("tube:0")))
        assertEquals(ChimeKind.BRASS, flow.sim.kind)
    }

    @Test fun textContrastMeetsD13() {
        val pairs = listOf(
            "ink on darkest glass" to (Palette.INK to Palette.GLASS_DARK),
            "ink2 on darkest glass" to (Palette.INK_SECONDARY to Palette.GLASS_DARK),
            "ink on sentence card" to (Palette.INK to Palette.CARD),
            "ink2 on sentence card" to (Palette.INK_SECONDARY to Palette.CARD),
            "ink on unselected segment" to (Palette.INK to Palette.FILL_SECONDARY),
            "white on primary (button, selected segment, mute on)" to (Palette.ON_PRIMARY to Palette.PRIMARY),
            "white on danger (quit)" to (Palette.ON_ERROR to Palette.ERROR),
            "primary icon on its chip" to (Palette.PRIMARY to Palette.PRIMARY_CONTAINER),
            "tile ink on tile wood" to (Palette.TILE_INK to Palette.TILE_WOOD),
        )
        for ((name, p) in pairs) {
            val c = Palette.contrast(p.first, p.second)
            println("CONTRAST %-52s %.2f:1".format(name, c))
            assertTrue("$name ${"%.2f".format(c)}", c >= 4.5)
        }
    }
}
