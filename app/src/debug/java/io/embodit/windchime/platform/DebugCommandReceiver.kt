package io.embodit.windchime.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.windchime.domain.ChimeKind
import io.embodit.windchime.domain.Hit
import io.embodit.windchime.domain.Language
import io.embodit.windchime.domain.Page
import io.embodit.windchime.domain.Settings
import io.embodit.windchime.domain.V3
import io.embodit.windchime.domain.VolumeScale
import io.embodit.windchime.domain.WindLevel
import io.embodit.windchime.ui.Ui
import io.embodit.windchime.ui.scene.SceneHolder
import io.embodit.windchime.ui.scene.route
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug builds only. Every command goes through the same ChimeFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.windchime.DEBUG -p io.embodit.windchime --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S08 · chime brass|bamboo|glass · wind 0..3 · strike 1..5 · token 1..5 · tap <holder> (tube:3, sail, screen, panel …)
 *   tapscreen "<x> <y>" (volume metres on the paper) · grab · drag "<dx> <dy> <dz>" (metres, a real drag path) · release
 *   pull "<dx> <dy> <dz> <ms>" (grab, move there over <ms>, hold); drag / pull / swipe are hand travel in physical
 *   metres, converted at the live volume scale like real input · vscale <k>|off (pretend a k x resize) · lightfloor on|off · envlight <0..1> · gust "<0.2-1> <+x|-x|+z|-z|deg>" · gustbutton · seed N
 *   hold STRIKE|SWING|GUST|S03|S04|off · mute on|off · vol 0..1 · lang zh|en|system · reduced on|off · gustsound on|off
 *   page play|howto|settings · exitask · closepanel · reopen · s00hold on|off · testf on|off · fresh · state · dump
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun tap(name: String) = route(flow, Hit.of(name), null, graph.window.panelOpen, reopen = { graph.window.open("panel-tile") }, flash = { graph.window.flash() })
        fun play() { flow.hold(null); Ui.exitConfirm = false; Ui.page = Page.PLAY; flow.welcomeDone() }
        fun dirOf(s: String?): Pair<Float, Float> = when (s) {
            "+x", null -> 1f to 0f; "-x" -> -1f to 0f; "+z" -> 0f to 1f; "-z" -> 0f to -1f
            else -> s.toFloatOrNull()?.let { d -> val r = Math.toRadians(d.toDouble()); sin(r).toFloat() to -cos(r).toFloat() } ?: (1f to 0f)
        }
        when (cmd) {
            "goto" -> when (a.uppercase()) {
                "S00" -> flow.showLoading(true)
                "S01" -> { flow.hold(null); flow.showLoading(false); flow.showWelcome() }
                "S02" -> { play(); flow.restSim() }
                "S03" -> { play(); flow.restSim(); pull(graph, 0.12f, 0f, 0f, 600) { flow.hold("S03") } }
                "S04" -> { play(); val k = ChimeKind.entries[(flow.sim.kind.ordinal + 1) % 3]; flow.setChime(k); later(300) { flow.freezeSwapAt(0.45f) } }
                "S05" -> { play(); Ui.page = Page.SETTINGS }
                "S06" -> { play(); Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
                "S07" -> { play(); graph.window.closeControlOnly() }
                "S08" -> { play(); Ui.page = Page.HOWTO }
            }
            "chime" -> flow.setChime(ChimeKind.of(a))
            "wind" -> flow.setWind(WindLevel.of(a.toIntOrNull() ?: 0))
            "strike" -> flow.tapTube(((a.toIntOrNull() ?: 1) - 1).coerceIn(0, 4))
            "token" -> flow.tapToken(((a.toIntOrNull() ?: 1) - 1).coerceIn(0, 4))
            "tap" -> tap(a)
            "tapscreen" -> route(flow, Hit.Screen, V3(f.getOrNull(0)?.toFloatOrNull() ?: 0f, f.getOrNull(1)?.toFloatOrNull() ?: 0f, -0.278f), graph.window.panelOpen)
            "grab" -> flow.dragStart(Hit.Sail, V3.ZERO)
            "drag" -> flow.dragMove(VolumeScale.current.toContent(V3(f.getOrNull(0)?.toFloatOrNull() ?: 0f, f.getOrNull(1)?.toFloatOrNull() ?: 0f, f.getOrNull(2)?.toFloatOrNull() ?: 0f)))
            "release" -> { flow.hold(null); flow.dragEnd() }
            "pull" -> pull(graph, f.getOrNull(0)?.toFloatOrNull() ?: 0.12f, f.getOrNull(1)?.toFloatOrNull() ?: 0f, f.getOrNull(2)?.toFloatOrNull() ?: 0f, f.getOrNull(3)?.toLongOrNull() ?: 600) {}
            "swipe" -> {
                // a real gust drag on the screen: start, move (dx, dz) over 0.25 s, end
                val dx = f.getOrNull(0)?.toFloatOrNull() ?: 0.2f; val dz = f.getOrNull(1)?.toFloatOrNull() ?: 0f
                flow.dragStart(Hit.Screen, V3.ZERO)
                graph.scope.launch { for (i in 1..8) { flow.dragMove(VolumeScale.current.toContent(V3(dx * i / 8, 0f, dz * i / 8))); delay(30) }; flow.dragEnd() }
            }
            "gust" -> { val (dx, dz) = dirOf(f.getOrNull(1)); flow.gust(dx, dz, f.getOrNull(0)?.toFloatOrNull() ?: 1f, user = true) }
            "gustbutton" -> flow.gustButton()
            "seed" -> flow.reseed(a.toLongOrNull() ?: 7)
            // v0.1.3 (D55): run the volume at a low frame rate (sleep per frame), e.g. `throttle 95` ~ 10 fps; `throttle 0` off
            "throttle" -> { SceneHolder.scene?.throttleMs = (a.toLongOrNull() ?: 0L).coerceIn(0L, 500L); Log.i(TAG, "THROTTLE ${SceneHolder.scene?.throttleMs} ms/frame") }
            "hold" -> if (a == "off") flow.hold(null) else flow.hold(a.uppercase())
            "mute" -> settings { it.copy(muted = on) }
            "vol" -> settings { it.copy(volume = (a.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)) }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "gustsound" -> settings { it.copy(gustSound = on) }
            "page" -> Ui.page = when (a) { "settings" -> Page.SETTINGS; "howto" -> Page.HOWTO; else -> Page.PLAY }
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "closepanel" -> graph.window.closeControlOnly()
            "reopen" -> graph.window.open("debug")
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).commit()
            "testf" -> SceneHolder.scene?.testF = on
            "lightfloor" -> SceneHolder.scene?.setLightFloor(on)
            "envlight" -> SceneHolder.scene?.setEnvLight((a.toFloatOrNull() ?: 1f).coerceIn(0f, 1f))
            "vscale" -> SceneHolder.scene?.scaleOverride = a.toFloatOrNull() // "off" -> measured size again
            "fresh" -> graph.settings.clear()
            "state", "dump" -> Log.i(TAG, "STATE ${flow.stateId(Ui.page, Ui.exitConfirm, graph.window.panelOpen)} ${SceneHolder.scene?.dump().orEmpty()} panel=${graph.window.panelState} settings=${graph.settings.value}")
            else -> Log.w(TAG, "CMD unknown: $cmd")
        }
    }

    /** Grab the sail and move it by (dx, dy, dz) over [ms] through the real drag path, then [then]. */
    private fun pull(graph: Graph, dx: Float, dy: Float, dz: Float, ms: Long, then: () -> Unit) {
        val flow = graph.flow
        flow.dragStart(Hit.Sail, V3.ZERO)
        graph.scope.launch {
            val n = (ms / 30).coerceAtLeast(1)
            for (i in 1..n) { flow.dragMove(VolumeScale.current.toContent(V3(dx * i / n, dy * i / n, dz * i / n))); delay(30) }
            delay(400)
            then()
        }
    }
}
