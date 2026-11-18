package io.embodit.windchime.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
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
        onStrike = { s, k, lag -> sounds.strike(s, k, lag) },
        onSound = { sounds.fx(it) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()
    /** v0.1.3 (D55): the physics' own 360 Hz thread; started while the volume is in the foreground. */
    val simLoop = io.embodit.windchime.domain.SimLoop(flow, log = { Log.i(TAG, it) },
        threadSetup = { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) })

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
 *
 * v0.1.4 (headset v0.1.3: AudioTrack underruns ~70/s = crackle / harsh noise, profile D55 补, gate audio-thread):
 * the write thread runs at [Process.THREAD_PRIORITY_URGENT_AUDIO]; nothing it does takes a lock a producer holds or
 * allocates; the AudioTrack buffer is sized in multiples of the device burst (AudioManager
 * PROPERTY_OUTPUT_FRAMES_PER_BUFFER) and never forced down to 960 frames; the headset routes this track to the system
 * spatializer output (48 kHz, 512-frame period, FAST is not available there); v0.1.4 opts out of it
 * (SPATIALIZATION_BEHAVIOR_NEVER, the pan was always ours) and the buffer still covers several periods of either path.
 * Every 5 s while the track is open: `MIX underruns=<n> (+<new>) buffer=<frames> render=<avg>/<max> us
 * of <block> us`.
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
    @Volatile private var running = true
    private var track: AudioTrack? = null
    private val thread = Thread(::loop, "WindChimeMix").apply { isDaemon = true }
    private val am = context.getSystemService(AudioManager::class.java)
    /** The device's output burst in frames (headset: 192 at 48 kHz); the mixer renders blocks of this size. */
    private val burst: Int = (am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 240).coerceIn(64, 1024)

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

    /**
     * Mixer thread (THREAD_PRIORITY_URGENT_AUDIO): renders [burst]-frame blocks into the AudioTrack; pauses the track
     * after 2 s of silence and parks until a note arrives. No locks, no allocation inside the loop.
     */
    private fun loop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            .onFailure { Log.w(TAG, "SOUND URGENT_AUDIO refused: $it") }
        Log.i(TAG, "SOUND mix thread priority=${Process.getThreadPriority(Process.myTid())} burst=$burst")
        val frames = burst
        val buf = FloatArray(frames * 2)
        val blockUs = frames * 1_000_000L / SR
        var idleSince = SystemClock.uptimeMillis()
        var lastLog = SystemClock.uptimeMillis()
        var lastUnderruns = 0
        var loggedUnderruns = 0
        while (running) {
            if (mixer.active == 0) {
                if (SystemClock.uptimeMillis() - idleSince > 2000) {
                    track?.let { runCatching { it.pause(); it.flush() } }
                    while (running && mixer.active == 0) java.util.concurrent.locks.LockSupport.parkNanos(500_000_000L)
                    idleSince = SystemClock.uptimeMillis()
                    continue
                }
            } else idleSince = SystemClock.uptimeMillis()
            val t = track ?: openTrack() ?: run { Thread.sleep(200); null } ?: continue
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }
            mixer.render(buf, frames)
            t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            // safety net (build.md 空中琴 v0.1.4): an underrun grows the buffer by one burst, up to the capacity
            val u = t.underrunCount
            if (u > lastUnderruns && t.bufferSizeInFrames < t.bufferCapacityInFrames) {
                runCatching { t.setBufferSizeInFrames(minOf(t.bufferCapacityInFrames, t.bufferSizeInFrames + frames)) }
                Log.i(TAG, "SOUND underrun $u -> buffer ${t.bufferSizeInFrames} frames")
            }
            lastUnderruns = u
            val now = SystemClock.uptimeMillis()
            if (now - lastLog >= 5000) {
                lastLog = now
                val (avg, mx) = mixer.takeRenderStats()
                Log.i(TAG, "MIX underruns=$u (+${u - loggedUnderruns}) voices=${mixer.active} buffer=${t.bufferSizeInFrames} render=$avg/$mx us of $blockUs us limiterMin=${"%.2f".format(mixer.takeMinLimiterGain())}")
                loggedUnderruns = u
            }
        }
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
    }

    private fun openTrack(): AudioTrack? = runCatching {
        val fmt = AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        // capacity: 16 bursts (or 4x the minimum) so the safety net has room; start at BUFFER_BURSTS bursts
        val bytesPerFrame = 2 * 4
        val cap = max(min * 4, burst * 16 * bytesPerFrame)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                // v0.1.4: keep this track off the system spatializer thread (48 kHz, 512-frame period, ~58 ms write
                // latency, where v0.1.3 underran ~70/s); the stereo pan is ours (design sound.spatial fallback +-0.3),
                // the same route beat-pad plays through on this headset
                .setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER).build())
            .setAudioFormat(fmt).setBufferSizeInBytes(cap)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
            .also {
                val want = max(min / bytesPerFrame, burst * BUFFER_BURSTS)
                runCatching { it.setBufferSizeInFrames(minOf(it.bufferCapacityInFrames, ((want + burst - 1) / burst) * burst)) }
                track = it
                Log.i(TAG, "SOUND AudioTrack open buffer=${it.bufferSizeInFrames} frames (capacity ${it.bufferCapacityInFrames}, burst $burst, min ${min / bytesPerFrame}, out ${am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} Hz) spatializer=never")
            }
    }.onFailure { Log.w(TAG, "SOUND AudioTrack: $it") }.getOrNull()

    private fun wake() = java.util.concurrent.locks.LockSupport.unpark(thread)

    /**
     * A strike: volume from the hit speed, fixed pan from the tube's x (design sound.spatial fallback: +-0.3).
     * [lagS] >= 0 (physics, v0.1.3 D55): the strike happened that long ago in simulated time; it is scheduled at
     * [SCHED_S] - lag on the mixer clock so strikes handed over in one batch keep their spacing. < 0: at once (taps).
     */
    fun strike(s: Strike, kind: ChimeKind, lagS: Double = -1.0) {
        if (muted) return
        val key = "${kind.id}:${s.tube}"
        val ids = tubes[key] ?: return
        val v = synchronized(variantNext) { (variantNext[key] ?: 0).also { variantNext[key] = (it + 1) % ids.size } }
        val vol = (s.volume * volume).coerceIn(0f, 1f)
        if (vol <= 0.001f) return
        val delay = if (lagS < 0) 0f else (SCHED_S - lagS).toFloat().coerceAtLeast(0f)
        mixer.play(ids[v % ids.size], vol, Mixer.tubePan(s.tube), key, duckable = true, delayS = delay)
        wake()
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "SOUND ${kind.id} ${s.tube + 1} vol=$vol src=${s.source} t=${s.t} delay=${delay * 1000}ms")
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
        /** Start the track buffer at this many device bursts (headset: 8 x 192 = 1536 frames, ~35 ms, three spatializer periods). */
        const val BUFFER_BURSTS = 8
        /** Physics strikes play this long after their simulated time (covers the sim thread's batch, ~3 ms normally). */
        const val SCHED_S = 0.03
        /** The gust whoosh sits about 18 dB under a full strike; the paper rustle lower still (assets: sound). */
        const val GUST_LEVEL = 0.35f
        const val RUSTLE_LEVEL = 0.2f
    }
}
