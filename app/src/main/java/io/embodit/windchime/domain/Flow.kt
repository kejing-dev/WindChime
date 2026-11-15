package io.embodit.windchime.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/** What the control window shows (recomposes only on change, never per frame). */
data class FlowUi(
    val phase: Phase = Phase.LOADING,
    val holding: Boolean = false,
    val swapping: Boolean = false,
    /** Just swapped: the first line says the new chime's tagline until the next touch (S04 → S02). */
    val tagline: ChimeKind? = null,
)

enum class Phase { LOADING, WELCOME, PLAY }

/** Window pages (design panels.control): play / how-to / settings, switched by the native TabBar. */
enum class Page { PLAY, HOWTO, SETTINGS }

/** What a 3D press landed on (holder names in the volume, resolved by the board-input template). */
sealed class Hit {
    data class Tube(val i: Int) : Hit()
    data class Token(val i: Int) : Hit()
    data class Strip(val i: Int) : Hit()
    object Sail : Hit()
    object Screen : Hit()
    object PanelTile : Hit()
    object None : Hit()

    companion object {
        fun of(name: String?): Hit {
            if (name == null) return None
            val p = name.split(":")
            return when (p[0]) {
                "tube" -> p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..5 }?.let { Tube(it - 1) } ?: None
                "token" -> p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..5 }?.let { Token(it - 1) } ?: None
                "strip" -> p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..5 }?.let { Strip(it - 1) } ?: None
                "sail" -> Sail
                "screen" -> Screen
                "panel" -> PanelTile
                else -> None
            }
        }

        /** Every holder name in the volume (the hit-replay test walks them). */
        val NAMES: List<String> = (1..5).map { "tube:$it" } + (1..5).map { "token:$it" } + (1..5).map { "strip:$it" } + listOf("sail", "screen", "panel")
    }
}

/**
 * The single app flow (design states S00..S08 + STRIKE / SWING / GUST): every input path (3D taps and drags, window
 * buttons, the debug broadcast) calls these same methods (D29). Time comes from [tick] (the ECS frame), so the
 * simulation never runs in Compose.
 */
class ChimeFlow(
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit,
    private val onStrike: (Strike, ChimeKind) -> Unit,
    private val onSound: (Fx) -> Unit,
    private val log: (String) -> Unit,
) {
    /** Non-tube sounds. */
    enum class Fx { GUST, RUSTLE }

    val sim = ChimeSim(settings().chime)
    private val _ui = MutableStateFlow(FlowUi())
    val ui: StateFlow<FlowUi> = _ui.asStateFlow()

    /** Flow clock, seconds (advanced by [tick]). */
    var now = 0.0; private set
    /** Frozen for QA stills (debug `hold`). */
    @Volatile var held = false
    var heldName: String? = null; private set

    /** Last strike time per tube (glow) and per tile (lit edge). */
    val tubeStruck = DoubleArray(5) { -10.0 }
    val tokenLit = DoubleArray(5) { -10.0 }
    var screenPuff = -10.0; private set
    var panelTileGlow = -10.0; private set
    var sailHintUntil = -10.0; private set
    var lastGust: Gust? = null; private set
    var lastGustAt = -10.0; private set
    var strikesTotal = 0; private set

    // swap (S04)
    private var swapT = -1.0
    private var swapTo: ChimeKind? = null
    private var swapNotesPlayed = 0
    /** 0..1 of the visible swap (shrink old 0..0.5, grow new 0.5..1), or null. */
    var swapProgress: Float? = null; private set
    var swapFrom: ChimeKind? = null; private set

    // drags
    private enum class DragKind { SAIL, GUST }
    private var drag: DragKind? = null
    private var dragT0 = 0.0
    private var dragStartTotal = V3.ZERO
    private var dragLast = V3.ZERO
    private var gustFired = false
    private var grabFrom = V3.ZERO

    fun start() {
        sim.setKind(settings().chime)
        sim.windTarget = Phys.WIND_MEAN[settings().wind.index]
        log("FLOW start chime=${settings().chime.id} wind=${settings().wind} firstRun=${!settings().firstRunDone}")
    }

    /** The volume's meshes are in: S00 ends (S01 the first time, otherwise S02). */
    fun sceneReady() {
        if (_ui.value.phase != Phase.LOADING) return
        val first = !settings().firstRunDone
        _ui.value = _ui.value.copy(phase = if (first) Phase.WELCOME else Phase.PLAY)
        if (first) sailHintUntil = now + 2.0
        log("STATE ${if (first) "S01" else "S02"}")
    }

    /** S01 [开始] (or any touch in the volume): the welcome is over for good. */
    fun welcomeDone() {
        if (_ui.value.phase == Phase.WELCOME) {
            _ui.value = _ui.value.copy(phase = Phase.PLAY)
            onSettings(settings().copy(firstRunDone = true))
            log("STATE S02 (welcome done)")
        }
    }

    /** Debug `goto S01`: show the welcome again. */
    fun showWelcome() { _ui.value = _ui.value.copy(phase = Phase.WELCOME); sailHintUntil = now + 2.0 }

    /** Debug `goto S00`. */
    fun showLoading(on: Boolean) { _ui.value = _ui.value.copy(phase = if (on) Phase.LOADING else Phase.PLAY) }

    fun tick(dt: Float) {
        if (held) return
        now += dt
        advanceSwap(dt)
        val strikes = sim.advance(dt)
        for (s in strikes) strike(s)
        // a gust drag fires after 0.3 s even while still held (physics.userGust)
        if (drag == DragKind.GUST && !gustFired && now - dragT0 >= 0.3) fireGust()
        if (_ui.value.tagline != null && now - lastTouch > 6.0) _ui.value = _ui.value.copy(tagline = null)
    }

    private var lastTouch = 0.0

    private fun touched() {
        lastTouch = now
        welcomeDone()
        if (_ui.value.tagline != null && swapProgress == null) _ui.value = _ui.value.copy(tagline = null)
    }

    private fun strike(s: Strike) {
        strikesTotal++
        tubeStruck[s.tube] = now
        tokenLit[s.tube] = now
        log("strike=${sim.kind.id},${s.tube + 1},${"%.2f".format(s.speed)} src=${s.source}")
        onStrike(s, sim.kind)
    }

    // ------------------------------------------------------------------ taps

    fun tapTube(i: Int) { touched(); if (swapProgress != null) return; strike(sim.tapTube(i)) }

    fun tapToken(i: Int) { touched(); if (swapProgress != null) return; strike(sim.tapTube(i, token = true)) }

    fun tapSail() { touched(); sim.tapSail(); log("TAP sail push") }

    /**
     * A tap that landed on the paper screen (T5): a strip index (the invisible strips behind the ring) or the ray
     * fallback's nearest tube rings that tube; otherwise the paper puffs with a faint rustle.
     */
    fun tapScreen(tube: Int?) {
        touched()
        if (tube != null && swapProgress == null) { log("SCREEN tap -> tube ${tube + 1}"); strike(sim.tapTube(tube)); return }
        screenPuff = now
        log("SCREEN tap -> puff")
        onSound(Fx.RUSTLE)
    }

    fun tapPanelTile(panelOpen: Boolean, reopen: () -> Unit, flash: () -> Unit) {
        touched()
        panelTileGlow = now
        if (panelOpen) flash() else reopen()
    }

    // ------------------------------------------------------------------ drags

    /** A drag began on [hit] (travel >= 2 cm). Sail = grab (S03); screen / strip / tube = a gust gesture. */
    fun dragStart(hit: Hit, total: V3): Boolean {
        touched()
        return when (hit) {
            Hit.Sail -> {
                if (swapProgress != null) return false
                drag = DragKind.SAIL
                grabFrom = sim.sailCenter() - total
                sim.grabTarget = grabFrom + total
                _ui.value = _ui.value.copy(holding = true)
                log("STATE S03 grab sail")
                true
            }
            Hit.Screen, is Hit.Strip, is Hit.Tube -> {
                drag = DragKind.GUST; dragT0 = now; dragStartTotal = total; dragLast = total; gustFired = false
                log("GUST drag start on $hit")
                true
            }
            else -> false
        }
    }

    fun dragMove(total: V3) {
        dragLast = total
        if (drag == DragKind.SAIL) sim.grabTarget = grabFrom + total
    }

    fun dragEnd() {
        when (drag) {
            DragKind.SAIL -> release()
            DragKind.GUST -> if (!gustFired) fireGust()
            null -> Unit
        }
        drag = null
    }

    fun dragCancel() = dragEnd()

    private fun release() {
        sim.grabTarget = null
        _ui.value = _ui.value.copy(holding = false)
        log("STATE S02+SWING release sail=${"%.1f".format(sim.sailDeg())}deg")
    }

    private fun fireGust() {
        gustFired = true
        val d = dragLast
        val elapsed = maxOf(0.12, now - dragT0 + 0.1).toFloat()
        val speed = d.length / elapsed
        val strength = (speed / 1.0f).coerceIn(0.2f, 1.0f)
        // only a vertical swipe: blow away from the user
        val (dx, dz) = if (sqrt(d.x * d.x + d.z * d.z) < 0.01f) 0f to -1f else d.x to d.z
        gust(dx, dz, strength, user = true)
    }

    /** [来一阵风] (T6): a random direction, strength 0.8. Debug `gust` passes its own. */
    fun gustButton(rng: java.util.Random = java.util.Random()) {
        touched()
        val a = rng.nextDouble() * Math.PI * 2
        gust(kotlin.math.sin(a).toFloat(), kotlin.math.cos(a).toFloat(), 0.8f, user = true)
    }

    fun gust(dx: Float, dz: Float, strength: Float, user: Boolean) {
        sim.gust(dx, dz, strength)
        lastGust = sim.gustProgress()?.first
        lastGustAt = now
        screenPuff = now
        log("GUST dir=(${"%.2f".format(dx)}, ${"%.2f".format(dz)}) strength=${"%.2f".format(strength)}")
        if (user && settings().gustSound) onSound(Fx.GUST)
    }

    // ------------------------------------------------------------------ settings-driven

    /** T7: swap chimes (disabled while the sail is held). */
    fun setChime(k: ChimeKind): Boolean {
        if (_ui.value.holding) { log("SWAP refused: holding"); return false }
        if (k == sim.kind && swapProgress == null) { onSettings(settings().copy(chime = k)); return true }
        touched()
        swapFrom = sim.kind
        swapTo = k
        swapT = 0.0
        swapNotesPlayed = 0
        swapProgress = 0f
        sim.calm = 20f
        onSettings(settings().copy(chime = k))
        _ui.value = _ui.value.copy(swapping = true, tagline = k)
        log("STATE S04 swap ${swapFrom?.id} -> ${k.id}")
        return true
    }

    /** Swap timeline, driven from [tick] through [advanceSwap]. */
    fun advanceSwap(dt: Float) {
        val to = swapTo ?: return
        swapT += dt
        val t = swapT
        if (t >= 0.3 && sim.kind != to) {
            sim.rest(); sim.calm = 1f; sim.setKind(to)
            log("SWAP kind=${to.id}")
        }
        // the new chime's tubes 1 / 3 / 5 ring softly as it settles (assets: swap)
        val notes = intArrayOf(0, 2, 4)
        while (swapNotesPlayed < 3 && t >= 0.45 + swapNotesPlayed * 0.12) {
            val i = notes[swapNotesPlayed++]
            tubeStruck[i] = now; tokenLit[i] = now
            onStrike(Strike(i, Phys.TAP_SOUND_V, Strike.Source.SWAP, Phys.SWAP_GAIN), to)
        }
        swapProgress = (t / 0.6).toFloat().coerceAtMost(1f)
        if (t >= 0.85) {
            swapTo = null; swapProgress = null; swapFrom = null
            _ui.value = _ui.value.copy(swapping = false)
            log("STATE S02 swap done")
        }
    }

    fun setWind(w: WindLevel) {
        sim.windTarget = Phys.WIND_MEAN[w.index]
        onSettings(settings().copy(wind = w))
        log("WIND W${w.index}")
    }

    /** Leaving the foreground: everything stops and comes back still (design lifecycle). */
    fun onBackground() {
        if (drag == DragKind.SAIL) release()
        drag = null
        sim.grabTarget = null
        sim.rest()
        log("LIFECYCLE background: sim at rest")
    }

    // ------------------------------------------------------------------ debug holds

    fun hold(name: String?) {
        held = name != null
        heldName = name
        log("HOLD ${name ?: "off"}")
    }

    /** Debug `hold S04`: freeze a swap half way. */
    fun freezeSwapAt(p: Float) {
        swapProgress = p; held = true; heldName = "S04"
    }

    // ------------------------------------------------------------------ derived

    fun swinging(): Boolean = sim.swingDeg() > 1f
    fun gustOn(): Boolean = now - lastGustAt < Phys.GUST_S
    fun strikeOn(): Boolean = tubeStruck.any { now - it < 0.8 }

    /** Named state for logs / the debug `state` dump (design stateModel: main state + overlays). */
    fun stateId(page: Page, exitConfirm: Boolean, panelOpen: Boolean): String {
        val u = _ui.value
        val main = when {
            u.phase == Phase.LOADING -> "S00"
            u.phase == Phase.WELCOME -> "S01"
            !panelOpen -> "S07"
            u.swapping -> "S04"
            u.holding -> "S03"
            page == Page.SETTINGS && exitConfirm -> "S06"
            page == Page.SETTINGS -> "S05"
            page == Page.HOWTO -> "S08"
            else -> "S02"
        }
        val s = settings()
        val mods = buildList {
            if (strikeOn()) add("STRIKE")
            if (swinging()) add("SWING")
            if (gustOn()) add("GUST")
            add("W${s.wind.index}")
            if (s.muted) add("MUTE")
            if (s.reducedMotion) add("RM")
        }
        return (listOf(main) + mods).joinToString("+")
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
}
