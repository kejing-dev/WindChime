package io.embodit.windchime.ui.scene

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.windchime.R
import io.embodit.windchime.domain.ChimeKind
import io.embodit.windchime.domain.Geo
import io.embodit.windchime.domain.Rot
import io.embodit.windchime.domain.V3
import io.embodit.windchime.platform.Graph
import io.embodit.windchime.platform.TAG
import io.embodit.windchime.ui.theme.isZh
import io.embodit.windchime.ui.theme.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!
internal fun Entity.at(x: Float, y: Float, z: Float) = transform().setPosition(Vector3(x, y, z))
internal fun Entity.at(p: V3) = transform().setPosition(Vector3(p.x, p.y, p.z))
internal val BLACK = Color4(0f, 0f, 0f, 1f)
internal fun glow(r: Float, g: Float, b: Float, k: Float) = Color4(r * k, g * k, b * k, 1f)
internal fun rgb(hex: Long, k: Float = 1f) = Color4(((hex shr 16) and 255) / 255f * k, ((hex shr 8) and 255) / 255f * k, (hex and 255) / 255f * k, 1f)

/** Rotation that turns straight down onto [d] (pendulum nodes hang along -Y). */
internal fun hangQuat(d: V3): Quat { val (ax, a) = Rot.fromDown(d); return Quat(Vector3(ax.x, ax.y, ax.z), a) }

/**
 * The volume's entities (design spatialLayout.hierarchy), origin = volume centre:
 * ```
 * root ─ stand (base, column, arm, hook) ─ screen (ash frame, "screen" paper) ─ chime (cord, disc,
 *        tubeNode:i → cord + "tube:i" per chime kind + glow bands, clapperNode → cord + clapper)
 *      ─ sailNode (moved to the sail's hanging point each frame) → cord + "sail" per kind
 *      ─ "token:i" tiles + label, "panel" tile + label, "strip:<kind>:i" invisible strips, windLines, keyLight
 * ```
 * Compose never drives motion: [onFrame] runs from the ECS system, ticks the flow and copies the pose here.
 */
internal class ChimeScene(private val context: Context, private val graph: Graph, private val scope: CoroutineScope) {
    private val flow = graph.flow
    private val sim = flow.sim
    val root: Entity = Entity().apply { setName("root") }
    private val all = ArrayList<Resource>()
    private fun <T : Resource> T.keep(): T { toGlobal(); synchronized(all) { all += this }; return this }

    @Volatile var built = false; private set
    @Volatile var testF = false

    private val tubeNodes = ArrayList<Entity>()
    /** [kind.ordinal][tube] */
    private val tubes = Array(3) { ArrayList<Entity>() }
    private val bands = Array(3) { ArrayList<Entity>() }
    private val bandMats = ArrayList<UnlitMaterial>()
    private var clapperNode: Entity? = null
    private var sailNode: Entity? = null
    private val sails = ArrayList<Entity>()
    private val sailMats = HashMap<ChimeKind, PhysicallyBasedMaterial>()
    private val strips = Array(3) { ArrayList<Entity>() }
    private val tokens = ArrayList<Entity>()
    private val tokenMats = ArrayList<PhysicallyBasedMaterial>()
    private var panelEnt: Entity? = null
    private var panelMat: PhysicallyBasedMaterial? = null
    private var paperEnt: Entity? = null
    private var discWalnut: Entity? = null
    private var discAsh: Entity? = null
    private val windLines = ArrayList<Entity>()
    private val windMat = ArrayList<UnlitMaterial>()

    private var shownKind: ChimeKind? = null
    private var shownLabels: String? = null
    private var lastPanelOpen = true
    private var panelGlowFrom = -10.0
    private var frames = 0
    private var fpsT0 = SystemClock.elapsedRealtime()
    @Volatile var fps = 0f; private set

    // ------------------------------------------------------------------ build

    suspend fun build() {
        val t0 = SystemClock.elapsedRealtime()
        val io = Dispatchers.IO
        coroutineScope {
            val tex = listOf("walnut", "ash", "tile", "brass", "bamboo", "glass", "paper").associateWith { n ->
                async(io) { runCatching { context.assets.open("textures/$n.png").use { BitmapFactory.decodeStream(it) }.let { TextureResource.create(it).keep() } }.onFailure { Log.w(TAG, "TEX $n: $it") }.getOrNull() }
            }
            val meshes = async(io) { buildMeshes() }
            val physics = async(io) { PhysicsMaterialResource().keep() }
            val m = meshes.await()
            val t = tex.mapValues { it.value.await() }
            val ph = physics.await()
            withMaterials(t) { mats -> assemble(m, mats, ph) }
        }
        buildLight()
        built = true
        applyKind(graph.settings.value.chime)
        refreshLabels()
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private class Mats(
        val walnut: PhysicallyBasedMaterial, val ash: PhysicallyBasedMaterial, val cord: PhysicallyBasedMaterial,
        val tube: Map<ChimeKind, PhysicallyBasedMaterial>, val paper: PhysicallyBasedMaterial, val paperBack: PhysicallyBasedMaterial,
    )

    private fun pbr(color: Long, rough: Float, metal: Float, tex: TextureResource?): PhysicallyBasedMaterial =
        PhysicallyBasedMaterial.create().apply {
            setBaseColor(if (tex != null) Color4(1f, 1f, 1f, 1f) else rgb(color))
            tex?.let { setBaseColorTexture(it) }
            setRoughness(rough); setMetallic(metal)
        }.keep()

    private inline fun withMaterials(t: Map<String, TextureResource?>, block: (Mats) -> Unit) {
        val walnut = pbr(0x6A4630, 0.62f, 0f, t["walnut"])
        val ash = pbr(0xE2D2B6, 0.6f, 0f, t["ash"])
        val cord = pbr(0x2B2420, 0.8f, 0f, null)
        val tube = mapOf(
            // brushed brass: metallic 1.0, roughness 0.35, base #C9A15A (design chimes.list)
            ChimeKind.BRASS to pbr(0xC9A15A, 0.35f, 1f, t["brass"]),
            ChimeKind.BAMBOO to pbr(0xC8B26A, 0.6f, 0f, t["bamboo"]),
            // pale celadon glass, opaque, the screen's refraction baked into the base colour (gate B q5 (3))
            ChimeKind.GLASS to pbr(0xCFE8E4, 0.08f, 0f, t["glass"]),
        )
        val paper = pbr(0xF3EBDD, 0.9f, 0f, t["paper"])
        val paperBack = pbr(0xEDE3D0, 0.9f, 0f, null)
        tileTex = t["tile"]
        walnutTex = t["walnut"]
        block(Mats(walnut, ash, cord, tube, paper, paperBack))
    }

    private var tileTex: TextureResource? = null
    private var walnutTex: TextureResource? = null

    private class MeshSet<M>(
        val base: M, val column: M, val arm: M, val hook: M, val hang: M,
        val disc: M, val tubeCord: M, val tubes: List<List<M>>, val bands: List<M>, val nodes: List<M>,
        val clapperCord: M, val clapper: M, val sailCord: M, val sails: Map<ChimeKind, M>,
        val frameV: M, val frameH: M, val paper: M, val paperBack: M,
        val token: M, val tokenFace: M, val panel: M, val panelFace: M, val wind: List<M>,
    ) {
        fun <R> map(f: (M) -> R): MeshSet<R> = MeshSet(
            f(base), f(column), f(arm), f(hook), f(hang), f(disc), f(tubeCord),
            tubes.map { it.map(f) }, bands.map(f), nodes.map(f),
            f(clapperCord), f(clapper), f(sailCord), sails.mapValues { f(it.value) },
            f(frameV), f(frameH), f(paper), f(paperBack), f(token), f(tokenFace), f(panel), f(panelFace), wind.map(f),
        )
    }

    /** Mesh data is cheap; creating the resources waits on the renderer, so they are all created concurrently. */
    private suspend fun buildMeshes(): MeshSet<MeshResource> = coroutineScope {
        val jobs = LinkedHashMap<String, kotlinx.coroutines.Deferred<MeshResource>>()
        fun MeshData.res(n: String): String { val d = this; jobs[n] = async(Dispatchers.IO) { d.create(n).keep() }; return n }
        val made = HashMap<String, MeshResource>()
        fun g(n: String): MeshResource = made.getValue(n)
        val y = Geo.PIVOT_Y
        val tubeMeshes = Array(3) { k ->
            val kind = ChimeKind.entries[k]
            (0 until 5).map { i ->
                val l = kind.length(i)
                // the tube hangs from its node: top at -0.03, bottom at -0.03 - L (bamboo: bottom cut at 30 deg)
                Meshes.rod(V3(0f, -Geo.TUBE_CORD - l, 0f), V3(0f, -Geo.TUBE_CORD, 0f), kind.tubeR, 24, slantDeg = if (kind == ChimeKind.BAMBOO) 30f else 0f).res("tube_${kind.id}_$i")
            }
        }
        val bands = Array<String>(3) { k -> val kind = ChimeKind.entries[k]; Meshes.rod(V3(0f, -0.02f, 0f), V3(0f, 0.02f, 0f), kind.tubeR + 0.0007f, 24, caps = false).res("band_${kind.id}") }
        // bamboo nodes at 1/3 of each tube
        val nodes = (0 until 5).map { i -> val l = ChimeKind.BAMBOO.length(i); Meshes.torus(ChimeKind.BAMBOO.tubeR, 0.0018f, 24, 10).also { m -> for (j in 0 until m.count) m.p[j * 3 + 1] += -Geo.TUBE_CORD - l / 3 }.res("node_$i") }
        val sails = ChimeKind.entries.associateWith { k ->
            when (k) {
                ChimeKind.BRASS -> Meshes.plate(Meshes.leaf(k.sailW, k.sailH), k.sailT)
                ChimeKind.BAMBOO -> Meshes.plate(Meshes.roundRect(k.sailW, k.sailH, 0.008f), k.sailT)
                ChimeKind.GLASS -> Meshes.plate(Meshes.roundRect(k.sailW, k.sailH, 0.002f), k.sailT)
            }.res("sail_${k.id}")
        }
        val fw = Geo.SCREEN_FRAME
        val names = MeshSet(
            base = Meshes.roundedBox(Geo.BASE_X1 - Geo.BASE_X0, Geo.BASE_Y1 - Geo.BASE_Y0, Geo.BASE_Z1 - Geo.BASE_Z0, 0.004f).res("base"),
            column = Meshes.rod(V3(0f, Geo.BASE_Y1, Geo.COLUMN_Z), V3(0f, Geo.COLUMN_TOP + Geo.ARM_R, Geo.COLUMN_Z), Geo.COLUMN_R).res("column"),
            arm = Meshes.rod(V3(0f, Geo.ARM_Y, Geo.COLUMN_Z), V3(0f, Geo.ARM_Y, 0.008f), Geo.ARM_R).res("arm"),
            hook = Meshes.hook(0.007f, 0.0016f).res("hook"),
            hang = Meshes.rod(V3(0f, Geo.DISC_Y1, 0f), V3(0f, Geo.HOOK_Y, 0f), 0.0012f, 12).res("hang"),
            disc = Meshes.rod(V3(0f, Geo.DISC_Y0, 0f), V3(0f, Geo.DISC_Y1, 0f), Geo.DISC_R, 48).res("disc"),
            tubeCord = Meshes.rod(V3(0f, -Geo.TUBE_CORD, 0f), V3(0f, 0f, 0f), Geo.CORD_R, 8).res("tubeCord"),
            tubes = tubeMeshes.toList(), bands = bands.toList(), nodes = nodes,
            clapperCord = Meshes.rod(V3(0f, -Geo.CLAPPER_CORD + Geo.CLAPPER_T / 2, 0f), V3(0f, 0f, 0f), Geo.CORD_R, 8).res("clapperCord"),
            clapper = Meshes.rod(V3(0f, -Geo.CLAPPER_CORD - Geo.CLAPPER_T / 2, 0f), V3(0f, -Geo.CLAPPER_CORD + Geo.CLAPPER_T / 2, 0f), Geo.CLAPPER_R, 40).res("clapper"),
            sailCord = Meshes.rod(V3(0f, -ChimeKind.BRASS.sailCordL, 0f), V3(0f, 0f, 0f), Geo.CORD_R, 8).res("sailCord"),
            sails = sails,
            frameV = Meshes.roundedBox(fw, Geo.SCREEN_H, Geo.SCREEN_T, 0.003f).res("frameV"),
            frameH = Meshes.roundedBox(Geo.SCREEN_W - 2 * fw, fw, Geo.SCREEN_T, 0.003f).res("frameH"),
            paper = Meshes.quad(Geo.SCREEN_W - 2 * fw, Geo.SCREEN_H - 2 * fw, up = false).res("paper"),
            paperBack = Meshes.roundedBox(Geo.SCREEN_W - 2 * fw, Geo.SCREEN_H - 2 * fw, 0.004f, 0.001f, 2).res("paperBack"),
            token = Meshes.roundedBox(Geo.TOKEN_W, Geo.TOKEN_H, Geo.TOKEN_D, 0.003f).res("token"),
            tokenFace = Meshes.quad(Geo.TOKEN_W - 0.004f, Geo.TOKEN_D - 0.004f, up = true).res("tokenFace"),
            panel = Meshes.roundedBox(Geo.PANEL_W, Geo.PANEL_H, Geo.PANEL_T, 0.003f).res("panel"),
            panelFace = Meshes.quad(Geo.PANEL_W - 0.004f, Geo.PANEL_H - 0.004f, up = false).res("panelFace"),
            wind = (0 until 6).map { i -> Meshes.ribbon(0.12f + 0.016f * i, 0.0025f, 0.012f + 0.003f * (i % 3)).res("wind$i") },
        )
        for ((n, d) in jobs) made[n] = d.await()
        Log.i(TAG, "MESHES built: ${made.size} resources, ${tubeMeshes.size * 5} tubes")
        names.map(::g)
    }

    private fun model(mesh: MeshResource, mat: com.pico.spatial.core.ecs.resource.Material, name: String, parent: Entity): ModelEntity =
        ModelEntity(mesh, mat).apply { setName(name); parent.addChild(this) }

    private fun pickable(e: Entity, shape: ShapeResource, physics: PhysicsMaterialResource, hover: Boolean = true) {
        e.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = physics))
        e.components.set(InteractableComponent())
        if (hover) e.components.set(HoverEffectComponent())
    }

    private fun colliderBox(x: Float, y: Float, z: Float, off: V3 = V3.ZERO): ShapeResource =
        ShapeResource.createBox(Vector3(x, y, z)).keep().let { if (off == V3.ZERO) it else it.offsetByTranslation(Vector3(off.x, off.y, off.z)).keep() }

    private fun assemble(m: MeshSet<MeshResource>, mats: Mats, ph: PhysicsMaterialResource) {
        // stand
        val stand = Entity().apply { setName("stand"); root.addChild(this) }
        model(m.base, mats.walnut, "base", stand).at((Geo.BASE_X0 + Geo.BASE_X1) / 2, (Geo.BASE_Y0 + Geo.BASE_Y1) / 2, (Geo.BASE_Z0 + Geo.BASE_Z1) / 2)
        model(m.column, mats.walnut, "column", stand)
        model(m.arm, mats.walnut, "arm", stand)
        model(m.hook, mats.cord, "hook", stand).at(0f, Geo.ARM_Y - Geo.ARM_R - 0.006f, 0f)
        // screen
        val screen = Entity().apply { setName("screenRoot"); root.addChild(this); at(Geo.SCREEN_C) }
        val fw = Geo.SCREEN_FRAME
        model(m.frameV, mats.ash, "frameL", screen).at(-Geo.SCREEN_W / 2 + fw / 2, 0f, 0f)
        model(m.frameV, mats.ash, "frameR", screen).at(Geo.SCREEN_W / 2 - fw / 2, 0f, 0f)
        model(m.frameH, mats.ash, "frameT", screen).at(0f, Geo.SCREEN_H / 2 - fw / 2, 0f)
        model(m.frameH, mats.ash, "frameB", screen).at(0f, -Geo.SCREEN_H / 2 + fw / 2, 0f)
        model(m.paperBack, mats.paperBack, "paperBack", screen)
        paperEnt = model(m.paper, mats.paper, "screen", screen).also {
            it.at(0f, 0f, 0.0025f)
            pickable(it, colliderBox(Geo.SCREEN_W, Geo.SCREEN_H, Geo.SCREEN_T, V3(0f, 0f, -0.0025f)), ph)
        }
        // chime
        val chime = Entity().apply { setName("chime"); root.addChild(this) }
        model(m.hang, mats.cord, "hang", chime)
        discWalnut = model(m.disc, mats.walnut, "disc:walnut", chime)
        discAsh = model(m.disc, mats.ash, "disc:ash", chime)
        val bandColor = Color4(1f, 0.93f, 0.78f, 1f)
        for (i in 0 until 5) {
            val (x, z) = Geo.TUBE_XZ[i]
            val node = Entity().apply { setName("tubeNode:$i"); chime.addChild(this); at(x, Geo.PIVOT_Y, z) }
            tubeNodes += node
            model(m.tubeCord, mats.cord, "tubeCord:$i", node)
            val bm = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply { setBaseColor(bandColor); setOpacity(0f); setCullingMode(MaterialCullingMode.NONE); setApplyToneMapping(false) }.keep()
            bandMats += bm
            for (k in ChimeKind.entries) {
                val l = k.length(i)
                val e = model(m.tubes[k.ordinal][i], mats.tube.getValue(k), "tube:${i + 1}", node)
                // back row (1, 3, 5) collider = radius + 3 mm; front row (2, 4) = the visible size (design input.colliders)
                val rr = if (i == 1 || i == 3) k.tubeR else k.tubeR + 0.003f
                pickable(e, colliderBox(2 * rr, l, 2 * rr, V3(0f, -Geo.TUBE_CORD - l / 2, 0f)), ph)
                if (k == ChimeKind.BAMBOO) model(m.nodes[i], mats.tube.getValue(k), "bambooNode:$i", e)
                tubes[k.ordinal] += e
                bands[k.ordinal] += model(m.bands[k.ordinal], bm, "glow:${k.id}:$i", node).apply { enabled = false }
            }
        }
        clapperNode = Entity().apply { setName("clapperNode"); chime.addChild(this); at(0f, Geo.PIVOT_Y, 0f) }.also { n ->
            model(m.clapperCord, mats.cord, "clapperCord", n)
            model(m.clapper, mats.walnut, "clapper", n)
        }
        sailNode = Entity().apply { setName("sailNode"); root.addChild(this) }.also { n ->
            model(m.sailCord, mats.cord, "sailCord", n)
            for (k in ChimeKind.entries) {
                val mat = when (k) {
                    ChimeKind.BRASS -> pbr(0x6A4630, 0.6f, 0f, walnutTex)
                    ChimeKind.BAMBOO -> pbr(0xC8B26A, 0.6f, 0f, null)
                    ChimeKind.GLASS -> pbr(0xF4EEE2, 0.9f, 0f, null)
                }
                sailMats[k] = mat
                val e = model(m.sails.getValue(k), mat, "sail", n)
                e.at(0f, -k.sailCordL, 0f)
                pickable(e, colliderBox(k.sailW * 1.3f, k.sailH * 1.3f, 0.02f, V3(0f, -k.sailH / 2, 0f)), ph)
                sails += e
            }
        }
        // base tiles: top face tilted 35 deg up toward the user
        for (i in 0 until 5) {
            val mat = pbr(0xE3C9A0, 0.7f, 0f, tileTex)
            tokenMats += mat
            val t = model(m.token, pbr(0xE3C9A0, 0.7f, 0f, tileTex), "token:${i + 1}", root)
            t.at(Geo.TOKEN_X[i], Geo.TOKEN_Y, Geo.TOKEN_Z)
            t.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), Geo.rad(Geo.TOKEN_TILT_DEG)))
            pickable(t, colliderBox(Geo.TOKEN_W, Geo.TOKEN_H, Geo.TOKEN_D), ph)
            model(m.tokenFace, mat, "tokenFace:$i", t).at(0f, Geo.TOKEN_H / 2 + 0.0004f, 0f)
            tokens += t
        }
        // the panel tile at the screen's lower right
        panelMat = pbr(0xE3C9A0, 0.7f, 0f, tileTex)
        panelEnt = model(m.panel, pbr(0xE3C9A0, 0.7f, 0f, tileTex), "panel", root).also { p ->
            p.at(Geo.PANEL_C)
            pickable(p, colliderBox(Geo.PANEL_HIT_W, Geo.PANEL_HIT_H, 0.02f), ph)
            model(m.panelFace, panelMat!!, "panelFace", p).at(0f, 0f, Geo.PANEL_T / 2 + 0.0004f)
        }
        // invisible strips behind the ring (v1.3): catch taps that missed a tube; one set per chime (widths differ)
        for (k in ChimeKind.entries) {
            Geo.strips(k).forEachIndexed { i, (lo, hi) ->
                val l = k.length(i).coerceAtLeast(k.length(2))
                // gate:allow geometry invisible strip collider only (no mesh): taps behind the ring ring the tube in front
                val s = Entity().apply { setName("strip:${i + 1}"); root.addChild(this); at((lo + hi) / 2, Geo.PIVOT_Y - Geo.TUBE_CORD - l / 2, Geo.STRIP_Z); enabled = false }
                pickable(s, colliderBox(hi - lo, l, Geo.STRIP_T), ph, hover = false)
                strips[k.ordinal] += s
            }
        }
        // wind lines (abstract indicator, unlit white 30 %)
        for (i in 0 until 6) {
            val wm = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setOpacity(0f); setCullingMode(MaterialCullingMode.NONE); setApplyToneMapping(false) }.keep()
            windMat += wm
            windLines += model(m.wind[i], wm, "windLine:$i", root).apply { enabled = false }
        }
    }

    /** Key light (design lighting): warm, from the upper left front 45 deg. Intensity is lux-scale (build.md 2026-11-12). */
    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            light.components.set(DirectionalLightComponent(Color4(1f, 0.93f, 0.84f, 1f), LIGHT_LUX, true))
            val q = Quat(Vector3(0f, 1f, 0f), (-135 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-45 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
            Log.i(TAG, "PROBE light: DirectionalLightComponent($LIGHT_LUX, shadow=true) added")
        }.onFailure { Log.w(TAG, "PROBE light unavailable: $it") }
    }

    // ------------------------------------------------------------------ per frame

    fun onFrame(dt: Float) {
        frames++
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - fpsT0 >= 5000) { fps = frames * 1000f / (nowMs - fpsT0); frames = 0; fpsT0 = nowMs; Log.i(TAG, "FRAME fps=${"%.1f".format(fps)} strikes=${flow.strikesTotal} sail=${"%.1f".format(sim.sailDeg())}") }
        flow.tick(dt.coerceIn(0f, 0.1f))
        if (!built) return
        val s = graph.settings.value
        val kind = sim.kind
        if (kind != shownKind) applyKind(kind)
        refreshLabels()
        val now = flow.now
        val rm = s.reducedMotion
        // swap: old tubes shrink (0..0.5), new ones grow (0.5..1) (S04)
        val sp = flow.swapProgress
        val scaleY = when { sp == null -> 1f; sp < 0.5f -> max(0.05f, 1f - 2 * sp); else -> max(0.05f, 2 * sp - 1f) }
        for (i in 0 until 5) {
            val node = tubeNodes[i]
            node.transform().setQuaternion(hangQuat(sim.tubeDir(i)))
            node.transform().setScaleVector(Vector3(1f, if (rm) 1f else scaleY, 1f))
            // glow: a band runs down the tube in 0.4 s and fades with the note (RM: the whole tube glows softly)
            val age = (now - flow.tubeStruck[i]).toFloat()
            val band = bands[kind.ordinal][i]
            if (age in 0f..0.8f) {
                band.enabled = true
                val l = kind.length(i)
                if (rm) {
                    band.at(0f, -Geo.TUBE_CORD - l / 2, 0f); band.transform().setScaleVector(Vector3(1f, l / 0.04f, 1f))
                    bandMats[i].setOpacity(0.35f * (1f - age / 0.8f))
                } else {
                    val f = min(1f, age / 0.4f)
                    band.at(0f, -Geo.TUBE_CORD - 0.02f - (l - 0.04f) * f, 0f); band.transform().setScaleVector(Vector3(1f, 1f, 1f))
                    bandMats[i].setOpacity(0.75f * (1f - age / 0.8f))
                }
            } else if (band.enabled) band.enabled = false
        }
        clapperNode?.transform()?.setQuaternion(hangQuat(sim.clapperDir()))
        sailNode?.let { n -> n.at(sim.sailPivot()); n.transform().setQuaternion(hangQuat(sim.sailDir())) }
        // S01: the sail glows softly for 2 s ("you can pull this")
        val hint = (flow.sailHintUntil - now).toFloat()
        sailMats[kind]?.setEmissiveColor(if (hint > 0f) glow(0.35f, 0.28f, 0.12f, 0.5f + 0.5f * sin(now.toFloat() * 6f)) else BLACK)
        // tiles light with their tube (0.8 s)
        for (i in 0 until 5) {
            val a = (now - flow.tokenLit[i]).toFloat()
            tokenMats[i].setEmissiveColor(if (a in 0f..0.8f) glow(0.55f, 0.45f, 0.30f, 1f - a / 0.8f) else BLACK)
        }
        // panel tile: glows 2 s when the window closes (S07) or when tapped
        val open = graph.window.panelOpen
        if (lastPanelOpen && !open) panelGlowFrom = now
        lastPanelOpen = open
        val pa = (now - max(panelGlowFrom, flow.panelTileGlow)).toFloat()
        panelMat?.setEmissiveColor(if (pa in 0f..2f) glow(0.5f, 0.42f, 0.25f, 0.5f + 0.5f * cos(pa * 7f)) else BLACK)
        // the paper puffs (gust, tap on the screen)
        val puff = (now - flow.screenPuff).toFloat()
        paperEnt?.at(0f, 0f, 0.0025f + if (puff in 0f..0.4f && !rm) 0.006f * sin(PI.toFloat() * puff / 0.4f) else 0f)
        windLinesFrame(rm)
    }

    /** +GUST: six thin lines blow through the volume from the upwind side in 0.8 s (none with reduced motion). */
    private fun windLinesFrame(rm: Boolean) {
        val gp = sim.gustProgress()
        if (gp == null || rm) { windLines.forEach { if (it.enabled) it.enabled = false }; return }
        val (g, p) = gp
        val dirx = g.dx; val dirz = g.dz
        val yaw = kotlin.math.atan2(-dirz, dirx)
        for (i in 0 until 6) {
            val e = windLines[i]
            e.enabled = true
            val lane = (i - 2.5f) * 0.035f
            val h = -0.10f + (i % 3) * 0.12f
            // from 0.16 m upwind to 0.16 m downwind, staggered
            val t = (p * 1.3f - i * 0.05f).coerceIn(0f, 1f)
            val along = -0.16f + 0.32f * t
            val x = dirx * along - dirz * lane
            val z = dirz * along + dirx * lane
            e.at(x, h, z)
            e.transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), yaw))
            windMat[i].setOpacity(0.3f * sin(PI.toFloat() * t))
        }
    }

    private fun applyKind(k: ChimeKind) {
        shownKind = k
        for (kk in ChimeKind.entries) {
            val on = kk == k
            tubes[kk.ordinal].forEach { it.enabled = on }
            strips[kk.ordinal].forEach { it.enabled = on }
            if (!on) bands[kk.ordinal].forEach { it.enabled = false }
        }
        ChimeKind.entries.forEachIndexed { i, kk -> sails.getOrNull(i)?.enabled = kk == k }
        discWalnut?.enabled = k != ChimeKind.GLASS
        discAsh?.enabled = k == ChimeKind.GLASS
        Log.i(TAG, "SCENE kind=${k.id}")
    }

    /** Tile / panel / paper-sail labels for the current chime and language (made off the main thread, cached). */
    private fun refreshLabels() {
        val s = graph.settings.value
        val zh = isZh(s.language)
        val key = "${shownKind?.id}|$zh|$testF"
        if (key == shownLabels) return
        shownLabels = key
        val kind = shownKind ?: return
        val f = testF
        scope.launch(Dispatchers.IO) {
            val loc = context.localized(s.language)
            runCatching {
                for (i in 0 until 5) tokenMats[i].setBaseColorTexture(TextureResource.create(Labels.tile(kind, i, zh, f)).keep())
                panelMat?.setBaseColorTexture(TextureResource.create(Labels.panel(zh, loc.getString(R.string.panel_tile), f)).keep())
                sailMats[ChimeKind.GLASS]?.apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(TextureResource.create(Labels.sailPaper(loc.getString(R.string.sail_word), zh, f)).keep()) }
                Log.i(TAG, "LABELS ${kind.id} zh=$zh testF=$f")
            }.onFailure { Log.w(TAG, "LABELS failed: $it") }
        }
    }

    /** Debug dump: world boxes of every visible part (ui-audit instance list). */
    fun dump(): String = buildString {
        append("kind=${sim.kind.id} sail=${"%.1f".format(sim.sailDeg())} clapperH=${"%.3f".format(sim.clapperH())} maxTube=${"%.1f".format(sim.maxTubeDeg())} fps=${"%.1f".format(fps)}")
    }

    fun dispose() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    companion object {
        /** Lux-scale key light (zen-orb 1500 was right for a desk volume; checked on the emulator, ui-audit). */
        const val LIGHT_LUX = 1500f
    }
}
