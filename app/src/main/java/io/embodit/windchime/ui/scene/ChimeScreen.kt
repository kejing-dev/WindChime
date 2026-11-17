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
import io.embodit.windchime.domain.VolumeScale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.LengthUnit
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

/** Copies the chime's latest pose into the scene each render frame (v0.1.3: the physics runs on [io.embodit.windchime.domain.SimLoop]). */
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
                Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.simLoop.stop(); graph.flow.onBackground(); graph.sounds.stopAll() }
                Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.simLoop.start() }
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
        graph.simLoop.start()
        onDispose {
            graph.simLoop.stop()
            unregisterSystem<ChimeFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    // QA #1 q1-2: the volume's real size (uniform resize) -> content root scale + input conversion
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val converter = LocalPhysicalLengthConverter.current
    val physicalPerPx = remember(converter) { with(converter) { converter.dpToLength(1f.toDp(), LengthUnit.Meters) } }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
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
            withContext(NonCancellable) { scene.build() } // entities on the main thread; meshes and textures are made on IO inside
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            graph.flow.sceneReady()
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) runCatching {
                val p = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                val q = content.convertPosition(Vector3(1000f, 0f, 0f), ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                val perPx = kotlin.math.hypot((q.x - p.x).toDouble(), kotlin.math.hypot((q.y - p.y).toDouble(), (q.z - p.z).toDouble())).toFloat() / 1000f
                scene.onVolumeMeasured(VolumeScale.measured(p.x, p.y, perPx, physicalPerPx), "view=${viewSize.width}x${viewSize.height} corner=(${"%.3f".format(p.x)}, ${"%.3f".format(p.y)}, ${"%.3f".format(p.z)})")
            }.onFailure { Log.w(TAG, "SCALE measure failed: $it") }
        },
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
        val vs = VolumeScale.current
        val p = position?.let { pos -> runCatching { SceneHolder.convert?.invoke(pos) }.getOrNull() }?.let(vs::pointToContent)
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> $hit at=$p scale=${"%.2f".format(vs.root)}")
        route(graph.flow, hit, p, graph.window.panelOpen, reopen = { graph.window.open("panel-tile") }, flash = { graph.window.flash() })
    }

    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target")
        // gate:allow shared-drag gate B kept Shared Space (design container, user passed gate B 2026-11-15); the sail pull and the screen swipe are the design's two continuous 3D actions, every note is also one tap away
        dragging = graph.flow.dragStart(Hit.of(target), V3.ZERO)
        return dragging
    }

    /** Hand travel (physical metres) -> content metres at the volume's current scale (design input.dragSpace). */
    override fun onDragMove(totalM: Offset3M) { if (dragging) graph.flow.dragMove(VolumeScale.current.toContent(V3(totalM.x, totalM.y, totalM.z))) }

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
        Hit.Screen -> flow.tapScreen(at?.let { Geo.tubeNearRay(flow.sim.kind, VolumeScale.current.eyeToContent(Geo.EYES[0].second), it) })
        Hit.PanelTile -> flow.tapPanelTile(panelOpen, reopen, flash)
        Hit.None -> Unit
    }
}
