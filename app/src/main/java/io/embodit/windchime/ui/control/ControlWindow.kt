package io.embodit.windchime.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.windchime.BuildConfig
import io.embodit.windchime.R
import io.embodit.windchime.domain.ChimeKind
import io.embodit.windchime.domain.FlowUi
import io.embodit.windchime.domain.Language
import io.embodit.windchime.domain.Page
import io.embodit.windchime.domain.Phase
import io.embodit.windchime.domain.Settings
import io.embodit.windchime.domain.WindLevel
import io.embodit.windchime.platform.Graph
import io.embodit.windchime.platform.TAG
import io.embodit.windchime.ui.Ui
import io.embodit.windchime.ui.theme.ChimeColors
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the volume, 560 x 640 dp): all 2D UI, three pages switched by the
 * native SpatialUI TabBar (D8): Play / How to play / Settings. No page scrolls; short pages are centred vertically
 * (D44), and every page logs `LAYOUT <page> blank=<%>` (content outside the window padding, top + bottom).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val ui by graph.flow.ui.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the how-to page with [开始]; when the welcome ends (button or a touch in the volume) the play page shows
    val lastPhase = remember { arrayOf<Phase?>(null) }
    LaunchedEffect(ui.phase) {
        if (ui.phase == Phase.WELCOME) Ui.page = Page.HOWTO
        if (ui.phase == Phase.PLAY && lastPhase[0] == Phase.WELCOME) Ui.page = Page.PLAY
        if (ui.phase == Phase.PLAY && lastPhase[0] == Phase.LOADING && Ui.page == Page.HOWTO) Ui.page = Page.PLAY
        lastPhase[0] = ui.phase
    }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
    }

    var windowH by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().onSizeChanged { windowH = it.height }) {
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            when (Ui.page) {
                Page.PLAY -> PlayPage(ui, settings, graph, windowH)
                Page.HOWTO -> HowToPage(ui, graph, windowH)
                Page.SETTINGS -> SettingsPage(settings, graph, windowH)
            }
        }
        Flash(graph.window.flashAt)
    }

    // The native TabBar is its own system window: resolve the labels here, in the app-language context, and pass
    // plain strings in (ui_gates tabbar-lang; an English cold start shows three English tabs).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.HOWTO, R.drawable.ic_tab_howto, stringResource(R.string.tab_howto)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

/** The panel tile tapped while the window is open: its edge glows for 0.6 s (design input.targets.panelToken). */
@Composable
private fun Flash(at: Long) {
    var alpha by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(at) {
        if (at == 0L) return@LaunchedEffect
        while (true) {
            val t = (SystemClock.uptimeMillis() - at) / 600f
            if (t >= 1f) { alpha = 0f; break }
            alpha = (1f - t)
            delay(16)
        }
    }
    if (alpha > 0f) Box(Modifier.fillMaxSize().border(6.dp, ChimeColors.Primary.copy(alpha = alpha), RoundedCornerShape(32.dp)))
}

/** The page body: a centred column whose measured height is logged against the window height (D44 blank table). */
@Composable
private fun PageBody(tag: String, windowH: Int, spacing: Int, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    Column(
        Modifier.fillMaxWidth().onSizeChanged { s ->
            if (windowH > 0) {
                val blank = 100f * (windowH - s.height) / windowH
                Log.i(TAG, "LAYOUT $tag content=${with(density) { s.height.toDp().value.toInt() }}dp window=${with(density) { windowH.toDp().value.toInt() }}dp blank=${"%.1f".format(blank)}%")
            }
        },
        verticalArrangement = Arrangement.spacedBy(spacing.dp),
        content = content,
    )
}

@Composable
private fun chimeNames(): List<String> = stringArrayResource(R.array.chimes).toList()

// ------------------------------------------------------------------ Play (S00 / S02 / S03 / S04)

@Composable
private fun PlayPage(ui: FlowUi, settings: Settings, graph: Graph, windowH: Int) {
    val flow = graph.flow
    val loading = ui.phase == Phase.LOADING
    val swap = stringArrayResource(R.array.swap_line)
    val modes = stringArrayResource(R.array.mode_small)
    val line = when {
        loading -> stringResource(R.string.loading)
        ui.holding -> stringResource(R.string.hold_line)
        ui.tagline != null -> swap[ui.tagline.ordinal]
        else -> stringResource(R.string.play_line)
    }
    val small = if (ui.tagline != null && !ui.holding) modes[ui.tagline.ordinal] else stringResource(R.string.play_small)
    val tag = when { loading -> "S00"; ui.holding -> "S03"; ui.swapping -> "S04"; else -> "S02" }
    PageBody("play/$tag", windowH, spacing = 28) {
        // the sentence (design content.tone), on a card
        Row(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp)).background(ChimeColors.Card).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) { CircularProgressIndicator(); Spacer(Modifier.width(16.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(line, "play/line", style = PicoTheme.typography.titleMedium, color = ChimeColors.Ink, maxLines = 2)
                if (!loading) Txt(small, "play/small", style = PicoTheme.typography.bodyMedium, color = ChimeColors.InkSecondary, maxLines = 2)
            }
        }
        val names = chimeNames()
        LabeledSegments(
            stringResource(R.string.label_chime), "play/chime", ChimeKind.entries.map { it to names[it.ordinal] }, settings.chime,
            onSelect = { k -> flow.setChime(k) }, enabled = !loading && !ui.holding,
            note = if (ui.holding) stringResource(R.string.hold_locked) else modes[settings.chime.ordinal],
        )
        val winds = stringArrayResource(R.array.winds)
        LabeledSegments(
            stringResource(R.string.label_wind), "play/wind", WindLevel.entries.map { it to winds[it.index] }, settings.wind,
            onSelect = { w -> flow.setWind(w) }, enabled = !loading,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PrimaryButton(stringResource(R.string.btn_gust), "play/gust", onClick = { flow.gustButton() }, enabled = !loading, icon = R.drawable.ic_air, modifier = Modifier.weight(1.3f))
            // D15: mute is always one glance away; the label says what a press does
            ToggleButton(
                stringResource(if (settings.muted) R.string.btn_unmute else R.string.btn_mute), "play/mute", on = settings.muted,
                onClick = { graph.settings.update { it.copy(muted = !it.muted) } },
                icon = if (settings.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up, modifier = Modifier.weight(1f),
            )
        }
    }
}

// ------------------------------------------------------------------ How to play (S01 / S08)

@Composable
private fun HowToPage(ui: FlowUi, graph: Graph, windowH: Int) {
    val welcome = ui.phase == Phase.WELCOME || ui.phase == Phase.LOADING
    PageBody(if (welcome) "howto/S01" else "howto/S08", windowH, spacing = 16) {
        HowToFigure()
        HowRow(R.drawable.ic_touch, stringResource(R.string.how_to_tap), stringResource(R.string.how_to_tap_ways), "howto/tap")
        HowRow(R.drawable.ic_pan, stringResource(R.string.how_to_sail), stringResource(R.string.how_to_sail_ways), "howto/sail")
        HowRow(R.drawable.ic_air, stringResource(R.string.how_to_wind), stringResource(R.string.how_to_wind_ways), "howto/wind")
        Txt(stringResource(R.string.how_to_small), "howto/small", style = PicoTheme.typography.bodySmall, color = ChimeColors.InkSecondary, maxLines = 2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt(stringResource(R.string.how_to_next), "howto/next", style = PicoTheme.typography.bodyMedium, color = ChimeColors.InkSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            PrimaryButton(stringResource(if (welcome) R.string.btn_start else R.string.btn_ok), "howto/start", onClick = {
                graph.flow.welcomeDone()
                Ui.page = Page.PLAY
            }, enabled = ui.phase != Phase.LOADING)
        }
    }
}

@Composable
private fun HowRow(icon: Int, main: String, ways: String, tag: String) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(ChimeColors.PrimaryContainer), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = ChimeColors.Primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(main, "$tag/main", style = PicoTheme.typography.titleSmall, color = ChimeColors.Ink, maxLines = 1)
            Txt(ways, "$tag/ways", style = PicoTheme.typography.bodySmall, color = ChimeColors.InkSecondary, maxLines = 1)
        }
    }
}

/** The how-to picture (design panels.howto): the chime, the sail pulled aside (dashed arc), a hand's swipe on the screen. */
@Composable
private fun HowToFigure() {
    Canvas(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)).background(ChimeColors.Card)) {
        val w = size.width; val h = size.height
        val cx = w * 0.42f
        // paper screen
        drawRoundRect(Color(0xFFEDE3D0), Offset(w * 0.18f, h * 0.06f), androidx.compose.ui.geometry.Size(w * 0.48f, h * 0.88f), androidx.compose.ui.geometry.CornerRadius(10f))
        // the swipe arc on the screen
        drawArc(ChimeColors.Primary, 200f, 80f, false, Offset(w * 0.22f, h * 0.10f), androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.55f), style = Stroke(width = 6f, cap = StrokeCap.Round))
        // disc + tubes
        val top = h * 0.16f
        drawRoundRect(ChimeColors.Walnut, Offset(cx - 46f, top), androidx.compose.ui.geometry.Size(92f, 12f), androidx.compose.ui.geometry.CornerRadius(6f))
        val lens = floatArrayOf(0.62f, 0.58f, 0.55f, 0.50f, 0.47f)
        for (i in 0 until 5) {
            val x = cx - 40f + i * 20f
            drawLine(Color(0xFF555555), Offset(x, top + 12f), Offset(x, top + 22f), 1.5f)
            drawRoundRect(ChimeColors.Brass, Offset(x - 5f, top + 22f), androidx.compose.ui.geometry.Size(10f, h * lens[i] * 0.8f), androidx.compose.ui.geometry.CornerRadius(4f))
        }
        // clapper, sail pulled aside, the path it swings back on
        val clap = Offset(cx, top + h * 0.40f)
        drawLine(Color(0xFF555555), Offset(cx, top + 12f), clap, 1.5f)
        val sail = Offset(cx + w * 0.16f, top + h * 0.62f)
        drawLine(Color(0xFF555555), clap, sail, 1.5f)
        drawRoundRect(ChimeColors.Walnut, Offset(sail.x - 14f, sail.y), androidx.compose.ui.geometry.Size(28f, 36f), androidx.compose.ui.geometry.CornerRadius(8f))
        drawArc(ChimeColors.Primary, 20f, 140f, false, Offset(cx - w * 0.17f, top + h * 0.30f), androidx.compose.ui.geometry.Size(w * 0.34f, h * 0.45f),
            style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        // base with the five tiles
        drawRoundRect(ChimeColors.Walnut, Offset(w * 0.20f, h * 0.88f), androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.08f), androidx.compose.ui.geometry.CornerRadius(6f))
        for (i in 0 until 5) drawRoundRect(Color(0xFFE3C9A0), Offset(w * 0.24f + i * w * 0.075f, h * 0.895f), androidx.compose.ui.geometry.Size(w * 0.05f, h * 0.05f), androidx.compose.ui.geometry.CornerRadius(3f))
    }
}

// ------------------------------------------------------------------ Settings (S05 / S06)

@Composable
private fun SettingsPage(settings: Settings, graph: Graph, windowH: Int) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    PageBody(if (Ui.exitConfirm) "settings/S06" else "settings/S05", windowH, spacing = 12) {
        OptionRow(stringResource(R.string.settings_language), "settings/lang") {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)),
                settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume") {
            VolumeSlider(settings.volume, onLive = { v -> graph.sounds.volume = v }, onDone = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.settings_mute), "settings/mute", settings.muted, { m -> upd { it.copy(muted = m) } }, Modifier.fillMaxWidth())
        OptionRow(stringResource(R.string.settings_gust), "settings/gust") {
            Segments(onOff, settings.gustSound, "settings/gust", { v -> upd { it.copy(gustSound = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced") {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        Txt(stringResource(R.string.panel_hint), "settings/hint", style = PicoTheme.typography.bodyMedium, color = ChimeColors.InkSecondary, maxLines = 2)
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.settings_about_label), "settings/about-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(132.dp))
            Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = ChimeColors.InkSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        // D26: quit is the last row; its confirmation replaces the row in place (same 64 dp, no extra row)
        if (!Ui.exitConfirm) {
            SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "STATE S06 exit confirm") },
                icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
        } else {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(stringResource(R.string.exit_line), "settings/exit-line", style = PicoTheme.typography.bodyMedium, color = ChimeColors.Ink, maxLines = 2, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_cancel), "settings/cancel", onClick = { Ui.exitConfirm = false })
                SecondaryButton(stringResource(R.string.btn_exit), "settings/quit", onClick = { exitApp(context, graph) }, danger = true, icon = R.drawable.ic_exit)
            }
        }
    }
}

/** D26 / D44 exit: stop the sounds, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.stopAll(); graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium, align = TextAlign.Center) },
            )
        }
    }
}
