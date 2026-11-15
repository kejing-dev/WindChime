package io.embodit.windchime.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.windchime.data.SettingsStore
import io.embodit.windchime.domain.ChimeFlow
import io.embodit.windchime.domain.ChimeKind
import io.embodit.windchime.domain.Geo
import io.embodit.windchime.domain.Strike
import io.embodit.windchime.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.exp

const val TAG = "WindChime"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        // board-input template: mirror input lines into files/input-log.txt (headset logcat drops lines)
        io.embodit.windchime.ui.scene.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** Process-wide objects shared by the volume and the control window (native-window-move.md §3.4). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val flow = ChimeFlow(
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onStrike = { s, k -> sounds.strike(s, k) },
        onSound = { sounds.fx(it) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()

    init {
        scope.launch {
            settings.settings.collect { s ->
                sounds.volume = s.volume
                sounds.setMuted(s.muted)
            }
        }
        flow.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Tube notes and the two little effects through one SoundPool (design sound.engine): 3 chimes x 5 tubes x 2 variants
 * of CC0 recordings (tools/make_sounds.py), maxStreams 12. A tube may ring twice at once (the older note keeps
 * decaying); beyond that, or when the pool is nearly full, the oldest stream fades out over 80 ms instead of being cut.
 * Mix headroom: the new stream's gain keeps the sum of the (decaying) peaks still sounding under -1 dBFS
 * (tools/loudness.py renders the same law: gale peaks -1.4..-2.6 dBFS, sounding RMS -17..-18 dBFS).
 */
class SoundPlayer(context: Context) {
    @Volatile var volume = 1f
    @Volatile var muted = false
        private set

    private val pool = SoundPool.Builder().setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val loaded = HashSet<Int>()
    /** "brass:0" -> sample ids of the variants. */
    private val tubeIds = HashMap<String, List<Int>>()
    private val fxIds = HashMap<ChimeFlow.Fx, Int>()
    private val variantNext = HashMap<String, Int>()
    private val main = Handler(Looper.getMainLooper())

    private class Active(val stream: Int, val key: String, val startMs: Long, val peak: Float, val tau: Float, var l: Float, var r: Float)
    private val active = ArrayList<Active>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (k in ChimeKind.entries) for (t in 0 until 5) {
            val ids = (1..2).mapNotNull { v -> load(context, "sounds/${k.id}_${t + 1}_$v.ogg") }
            if (ids.isNotEmpty()) tubeIds["${k.id}:$t"] = ids else Log.w(TAG, "SOUND missing ${k.id} tube ${t + 1}")
        }
        load(context, "sounds/gust.ogg")?.let { fxIds[ChimeFlow.Fx.GUST] = it }
        load(context, "sounds/rustle.ogg")?.let { fxIds[ChimeFlow.Fx.RUSTLE] = it }
    }

    private fun load(context: Context, path: String): Int? = runCatching { context.assets.openFd(path).use { fd -> pool.load(fd, 1) } }
        .onFailure { Log.w(TAG, "SOUND load $path: $it") }.getOrNull()

    /** A strike: volume from the hit speed, fixed pan from the tube's x (design sound.spatial fallback: +-0.3). */
    fun strike(s: Strike, kind: ChimeKind) {
        if (muted) return
        val key = "${kind.id}:${s.tube}"
        val ids = tubeIds[key] ?: return
        val v = variantNext[key] ?: 0
        variantNext[key] = (v + 1) % ids.size
        val id = ids[v]
        if (synchronized(loaded) { id !in loaded }) return
        val vol = (s.volume * volume).coerceIn(0f, 1f)
        if (vol <= 0.001f) return
        val pan = (Geo.TUBE_XZ[s.tube].first / 0.062f) * 0.3f
        val t0 = SystemClock.uptimeMillis()
        synchronized(active) {
            prune(t0)
            // polyphony: at most 2 per tube, and leave room in the pool
            val same = active.filter { it.key == key }
            if (same.size >= 2) fadeOut(same.minByOrNull { it.startMs }!!)
            if (active.size >= MAX_STREAMS - 1) fadeOut(active.minByOrNull { it.startMs }!!)
            val g = MixHeadroom.gain(vol * SAMPLE_PEAK, othersPower(t0))
            val l = vol * g * (1f - pan).coerceAtMost(1f)
            val r = vol * g * (1f + pan).coerceAtMost(1f)
            val stream = pool.play(id, l, r, 1, 0, 1f)
            if (stream != 0) active += Active(stream, key, t0, vol * g * SAMPLE_PEAK, TAU[kind.ordinal], l, r)
        }
        Log.i(TAG, "SOUND ${kind.id} ${s.tube + 1} vol=${"%.2f".format(vol)} src=${s.source}")
    }

    fun fx(f: ChimeFlow.Fx) {
        if (muted) return
        val id = fxIds[f] ?: return
        val v = (if (f == ChimeFlow.Fx.GUST) GUST_LEVEL else RUSTLE_LEVEL) * volume
        pool.play(id, v, v, 0, 0, 1f)
    }

    private fun othersPower(now: Long): Float {
        var p = 0f
        for (a in active) { val age = (now - a.startMs) / 1000f; p += a.peak * exp(-age / a.tau) }
        return p
    }

    private fun prune(now: Long) { active.removeAll { now - it.startMs > 7000 } }

    /** 80 ms fade then stop (never a hard cut, design sound.engine). */
    private fun fadeOut(a: Active) {
        active.remove(a)
        for (k in 1..4) main.postDelayed({ val f = 1f - k / 4f; pool.setVolume(a.stream, a.l * f, a.r * f); if (k == 4) pool.stop(a.stream) }, k * 20L)
    }

    /** T9: everything fades out over 80 ms; the state is kept in settings. */
    fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m
        if (m) synchronized(active) { ArrayList(active).forEach { fadeOut(it) } }
    }

    fun stopAll() = synchronized(active) { ArrayList(active).forEach { fadeOut(it) } }

    fun release() = runCatching { pool.release() }

    companion object {
        const val MAX_STREAMS = 12
        /** Every sample is normalised to -1 dBFS (tools/make_sounds.py). */
        const val SAMPLE_PEAK = 0.891f
        /** Envelope time constant per chime for the headroom estimate (brass rings longest). */
        val TAU = floatArrayOf(0.8f, 0.2f, 0.5f)
        /** The gust whoosh sits about 18 dB under a full strike; the paper rustle lower still (assets: sound). */
        const val GUST_LEVEL = 0.35f
        const val RUSTLE_LEVEL = 0.2f
    }
}

/** The mix rule (pure for the unit test): the decayed peaks of what is sounding plus the new note stay <= -1 dBFS. */
object MixHeadroom {
    const val CEILING = 0.891f

    fun gain(newPeak: Float, othersPeak: Float): Float {
        if (newPeak <= 0f) return 1f
        return ((CEILING - othersPeak) / newPeak).coerceIn(0.25f, 1f)
    }
}
