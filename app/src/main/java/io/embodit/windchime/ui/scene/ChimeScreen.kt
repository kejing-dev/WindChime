package io.embodit.windchime.ui.scene

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.windchime.domain.ChimeFlow
import io.embodit.windchime.domain.Geo
import io.embodit.windchime.domain.Hit
import io.embodit.windchime.domain.V3
import io.embodit.windchime.platform.Graph
import io.embodit.windchime.platform.TAG
import io.embodit.windchime.ui.scene.input.BoardInputConfig
import io.embodit.windchime.ui.scene.input.BoardInputListener
import io.embodit.windchime.ui.scene.input.BoardInputLog
import io.embodit.windchime.ui.scene.input.InputSource
import io.embodit.windchime.ui.scene.input.Offset3M
import io.embodit.windchime.ui.scene.input.TargetResolver
import io.embodit.windchime.ui.scene.input.boardInput
import io.embodit.windchime.domain.TapRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs the chime's per-frame simulation inside the ECS (never in Compose). */
class ChimeFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
    }
}

internal object SceneHolder {
    @Volatile var scene: ChimeScene? = null
    /** The SpatialView content, for turning a tap position (view px) into volume metres. */
    @Volatile var convert: ((Offset3D) -> V3)? = null
}

/**
 * The chime volume (default container). Opens the control window beside it, shows the chime, and routes every
 * pinch / trigger / drag on a holder (tube, tile, strip, sail, screen, panel tile) to [ChimeFlow] through the factory
 * board-input template (D50: hand ray, controller ray and eye-hand alike).
 */
@Composable
fun ChimeScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { ChimeScene(context.applicationContext, graph, scope) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.openAtStart()
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground(); graph.sounds.stopAll() }
                Lifecycle.Event.ON_START -> Log.i(TAG, "LIFECYCLE foreground")
                // the launcher icon tapped again brings the volume back; the panel comes with it
                Lifecycle.Event.ON_RESUME -> if (graph.window.panelState == "ON_DESTROY") graph.window.open("resume")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        registerSystem<ChimeFrameSystem>()
        onDispose {
            unregisterSystem<ChimeFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .boardInput(
                remember { ChimeTargets() }, remember(graph) { ChimeListener(graph) },
                BoardInputConfig(dragStartM = TapRule.DRAG_M, logTag = TAG, tapMaxMs = TapRule.TAP_MS),
            ),
        initial = { content, _ ->
            content.addEntity(scene.root)
            SceneHolder.convert = { p ->
                val v = content.convertPosition(Vector3(p.x, p.y, p.z), ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                V3(v.x, v.y, v.z)
            }
            withContext(NonCancellable + Dispatchers.Default) { scene.build() }
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            graph.flow.sceneReady()
        },
        update = { _, _ -> },
    )
}

/** Hit entity (or an ancestor) -> its holder name (tube:n, token:n, strip:n, sail, screen, panel). */
internal class ChimeTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { Hit.of(it) != Hit.None }

        /** Hit replay (build.md 命中回放): [path] = the hit node's name first, then its parents'. */
        fun resolvePath(path: List<String>): String? = io.embodit.windchime.ui.scene.input.resolvePath(path, ::targetOf)
    }
}

/** Every resolved tap / drag goes through [route] / [ChimeFlow] (the same calls the window and the debug broadcast make). */
internal class ChimeListener(private val graph: Graph) : BoardInputListener<String> {
    private var dragging = false

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        val hit = Hit.of(target)
        val p = position?.let { pos -> runCatching { SceneHolder.convert?.invoke(pos) }.getOrNull() }
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> $hit at=$p")
        route(graph.flow, hit, p, graph.window.panelOpen, reopen = { graph.window.open("panel-tile") }, flash = { graph.window.flash() })
    }

    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target")
        // gate:allow shared-drag gate B kept Shared Space (design container, user passed gate B 2026-11-15); the sail pull and the screen swipe are the design's two continuous 3D actions, every note is also one tap away
        dragging = graph.flow.dragStart(Hit.of(target), V3.ZERO)
        return dragging
    }

    override fun onDragMove(totalM: Offset3M) { if (dragging) graph.flow.dragMove(V3(totalM.x, totalM.y, totalM.z)) }

    override fun onDragEnd() { if (dragging) graph.flow.dragEnd(); dragging = false }

    override fun onDragCancel() { if (dragging) graph.flow.dragCancel(); dragging = false }

    /** D50 hover: the system HoverEffect draws it; the app only logs (hint only, never a state change). */
    override fun onHover(target: String?, source: InputSource) = Unit
}

/** Holder -> the one flow call (shared by real input, the debug broadcast and the hit-replay test). */
internal fun route(flow: ChimeFlow, hit: Hit, at: V3?, panelOpen: Boolean, reopen: () -> Unit = {}, flash: () -> Unit = {}) {
    when (hit) {
        is Hit.Tube -> flow.tapTube(hit.i)
        is Hit.Token -> flow.tapToken(hit.i)
        is Hit.Strip -> flow.tapScreen(hit.i)
        Hit.Sail -> flow.tapSail()
        // enhanced fallback: project the hit back toward the sitting eye onto the tube plane (<= 3 cm), else the paper puffs
        Hit.Screen -> flow.tapScreen(at?.let { Geo.tubeNearRay(flow.sim.kind, Geo.EYES[0].second, it) })
        Hit.PanelTile -> flow.tapPanelTile(panelOpen, reopen, flash)
        Hit.None -> Unit
    }
}
