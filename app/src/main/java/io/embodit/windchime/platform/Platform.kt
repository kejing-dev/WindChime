package io.embodit.windchime.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.windchime.data.SettingsStore
import io.embodit.windchime.domain.ChimeFlow
import io.embodit.windchime.domain.ChimeKind
import io.embodit.windchime.domain.Mixer
import io.embodit.windchime.domain.Strike
import io.embodit.windchime.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max

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
 * Tube notes and the two little effects (v0.1.1, QA #1 q1-1): 3 chimes x 5 tubes x 2 variants of CC0 recordings
 * (tools/make_sounds.py) decoded once to PCM, mixed by the app's own bus ([Mixer]: every new note at its own level,
 * older tails ducked just enough, look-ahead limiter at -1 dBFS) and played through one low-latency AudioTrack.
 * The unit test MixerRenderTest renders the same Mixer with the same samples (qa/melody-peaks-<version>.txt).
 */
class SoundPlayer(private val context: Context) {
    @Volatile var volume = 1f
    @Volatile var muted = false
        private set

    private val mixer = Mixer(SR)
    /** "brass:0" -> decoded variants. */
    private val tubes = java.util.concurrent.ConcurrentHashMap<String, List<Mixer.Sample>>()
    private val fxs = java.util.concurrent.ConcurrentHashMap<ChimeFlow.Fx, Mixer.Sample>()
    private val variantNext = HashMap<String, Int>()
    private val lock = Object()
    @Volatile private var running = true
    private var track: AudioTrack? = null
    private val thread = Thread(::loop, "WindChimeMix").apply { priority = Thread.MAX_PRIORITY; isDaemon = true }

    init {
        Thread({
            val t0 = SystemClock.uptimeMillis()
            for (k in ChimeKind.entries) for (t in 0 until 5) {
                val v = (1..2).mapNotNull { decode("sounds/${k.id}_${t + 1}_$it.ogg") }
                if (v.isNotEmpty()) tubes["${k.id}:$t"] = v else Log.w(TAG, "SOUND missing ${k.id} tube ${t + 1}")
            }
            decode("sounds/gust.ogg")?.let { fxs[ChimeFlow.Fx.GUST] = it }
            decode("sounds/rustle.ogg")?.let { fxs[ChimeFlow.Fx.RUSTLE] = it }
            Log.i(TAG, "SOUND decoded ${tubes.size} tubes + ${fxs.size} fx in ${SystemClock.uptimeMillis() - t0} ms")
        }, "WindChimeDecode").start()
        thread.start()
    }

    /** OGG asset -> mono 16-bit PCM at [SR] (MediaExtractor + MediaCodec). */
    private fun decode(path: String): Mixer.Sample? = runCatching {
        val ex = MediaExtractor()
        context.assets.openFd(path).use { fd -> ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        ex.selectTrack(0)
        val fmt = ex.getTrackFormat(0)
        val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val out = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!
                val bytes = ByteArray(info.size); b.position(info.offset); b.get(bytes)
                out.write(bytes)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        val sb = java.nio.ByteBuffer.wrap(out.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val all = ShortArray(sb.remaining()).also { sb.get(it) }
        val mono = if (ch == 1) all else ShortArray(all.size / ch) { f -> (0 until ch).sumOf { all[f * ch + it].toInt() }.div(ch).toShort() }
        Mixer.Sample(mono)
    }.onFailure { Log.w(TAG, "SOUND decode $path: $it") }.getOrNull()

    /** Mixer thread: renders 240-frame blocks into the AudioTrack; pauses the track after 2 s of silence. */
    private fun loop() {
        val frames = 240
        val buf = FloatArray(frames * 2)
        var idleSince = SystemClock.uptimeMillis()
        var lastLog = 0L
        var lastUnderruns = 0
        while (running) {
            if (mixer.active == 0) {
                if (SystemClock.uptimeMillis() - idleSince > 2000) {
                    track?.let { runCatching { it.pause(); it.flush() } }
                    synchronized(lock) { while (running && mixer.active == 0) lock.wait(500) }
                    idleSince = SystemClock.uptimeMillis()
                    continue
                }
            } else idleSince = SystemClock.uptimeMillis()
            val t = track ?: openTrack() ?: run { Thread.sleep(200); null } ?: continue
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }
            mixer.render(buf, frames)
            t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            // build.md (空中琴 v0.1.4): an underrun grows the buffer by one block, up to the track's capacity
            val u = t.underrunCount
            if (u > lastUnderruns && t.bufferSizeInFrames < t.bufferCapacityInFrames) {
                runCatching { t.setBufferSizeInFrames(minOf(t.bufferCapacityInFrames, t.bufferSizeInFrames + frames)) }
                Log.i(TAG, "SOUND underrun $u -> buffer ${t.bufferSizeInFrames} frames")
            }
            lastUnderruns = u
            val now = SystemClock.uptimeMillis()
            if (now - lastLog > 5000 && mixer.active > 0) { lastLog = now; Log.i(TAG, "MIX voices=${mixer.active} limiterMin=${"%.2f".format(mixer.takeMinLimiterGain())} underruns=${t.underrunCount}") }
        }
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
    }

    private fun openTrack(): AudioTrack? = runCatching {
        val fmt = AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(fmt).setBufferSizeInBytes(max(min, 240 * 2 * 4 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setTransferMode(AudioTrack.MODE_STREAM).build()
            .also {
                // keep the tap-to-sound latency low: ask for ~4 blocks (22 ms) instead of the default (~70 ms)
                runCatching { it.setBufferSizeInFrames(BUFFER_FRAMES) }
                track = it; Log.i(TAG, "SOUND AudioTrack open buffer=${it.bufferSizeInFrames} frames (capacity ${it.bufferCapacityInFrames})")
            }
    }.onFailure { Log.w(TAG, "SOUND AudioTrack: $it") }.getOrNull()

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    /** A strike: volume from the hit speed, fixed pan from the tube's x (design sound.spatial fallback: +-0.3). */
    fun strike(s: Strike, kind: ChimeKind) {
        if (muted) return
        val key = "${kind.id}:${s.tube}"
        val ids = tubes[key] ?: return
        val v = synchronized(variantNext) { (variantNext[key] ?: 0).also { variantNext[key] = (it + 1) % ids.size } }
        val vol = (s.volume * volume).coerceIn(0f, 1f)
        if (vol <= 0.001f) return
        mixer.play(ids[v % ids.size], vol, Mixer.tubePan(s.tube), key, duckable = true)
        wake()
        Log.i(TAG, "SOUND ${kind.id} ${s.tube + 1} vol=${"%.2f".format(vol)} src=${s.source} duck=${"%.2f".format(mixer.lastDuck)}")
    }

    fun fx(f: ChimeFlow.Fx) {
        if (muted) return
        val smp = fxs[f] ?: return
        val v = (if (f == ChimeFlow.Fx.GUST) GUST_LEVEL else RUSTLE_LEVEL) * volume
        mixer.play(smp, v, 0f, "fx:${f.name}", duckable = false)
        wake()
    }

    /** T9: everything fades out over 80 ms; the state is kept in settings. */
    fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m
        if (m) mixer.fadeAll()
    }

    fun stopAll() = mixer.fadeAll()

    fun release() { running = false; wake() }

    companion object {
        const val SR = 44100
        const val BUFFER_FRAMES = 960
        /** The gust whoosh sits about 18 dB under a full strike; the paper rustle lower still (assets: sound). */
        const val GUST_LEVEL = 0.35f
        const val RUSTLE_LEVEL = 0.2f
    }
}
