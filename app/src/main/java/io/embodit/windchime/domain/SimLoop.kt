package io.embodit.windchime.domain

import java.util.concurrent.locks.LockSupport

/**
 * v0.1.3 (profile D55, Windchime on the headset: "在 gale 时候连续碰撞，会卡"): the system drops the volume container to
 * 10 fps (`updateWindowContainerDivisor ... divisor=9`) and v0.1.2 stepped the physics from the render frame with at
 * most 8 x 1/360 s per frame, so the chime ran at 22 % speed and a frame's strikes rang together.
 *
 * Now the wind, the pendulums and the collision tests run here, on their own thread, at a fixed 1/360 s step caught up
 * by the monotonic clock (up to [Phys.MAX_CATCHUP_S] per wake; a longer stall drops the backlog). The volume only
 * reads [ChimeFlow.snapshot] per frame; the mixer places each strike at its simulated time.
 *
 * [pump] is plain Kotlin with an injected clock so the unit test runs it at any wake / frame rate.
 *
 * v0.1.4 (headset v0.1.3: audio underruns ~70/s, client_cpu_cost 100-157 ms): the thread no longer wakes 360 times a
 * second at a raised priority. It wakes every [WAKE_NS] (120 Hz) and runs the 1/360 s steps that have elapsed (usually
 * 3), at the normal priority set by [threadSetup] (below the render and audio threads). The physics step and the
 * simulated clock are unchanged, so strikes keep their simulated times (the mixer schedules them 30 ms out).
 */
class SimLoop(
    private val flow: ChimeFlow,
    private val clock: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
    /** Runs first on the sim thread (the app sets an Android thread priority here). */
    private val threadSetup: () -> Unit = {},
) {
    @Volatile var running = false
        private set
    private var thread: Thread? = null
    private var lastNs = 0L
    private var carryNs = 0L

    // stats (log window)
    private var winWallNs = 0L
    private var winSim = 0.0
    private var winSteps = 0
    private var winMaxBatch = 0
    /** Simulated seconds over wall seconds in the last log window (1.000 = real time). */
    @Volatile var lastRate = 0.0
        private set
    /** Steps dropped after stalls longer than the catch-up cap (whole run). */
    var droppedSteps = 0L
        private set

    /** Starts (or restarts) from "now": no catch-up for the time it was stopped. */
    @Synchronized fun start() {
        if (running) return
        lastNs = clock(); carryNs = 0L; resetWindow()
        running = true
        thread = Thread(::loop, "WindChimeSim").apply { isDaemon = true; start() }
        log("SIM start ${Phys.HZ} Hz steps, wake ${1_000_000_000L / WAKE_NS} Hz, catch-up <= ${Phys.MAX_CATCHUP_S} s")
    }

    @Synchronized fun stop() {
        if (!running) return
        running = false
        thread?.let { LockSupport.unpark(it) }
        thread = null
        log("SIM stop")
    }

    private fun loop() {
        runCatching(threadSetup)
        while (running) {
            pump(clock())
            LockSupport.parkNanos(WAKE_NS)
        }
    }

    /** Runs every whole 1/360 s step that has elapsed since the last call; returns the number of steps run. */
    fun pump(nowNs: Long): Int {
        val elapsed = (nowNs - lastNs).coerceAtLeast(0L)
        lastNs = nowNs
        carryNs += elapsed
        var n = (carryNs / STEP_NS).toInt()
        if (n > Phys.MAX_SUBSTEPS) {
            droppedSteps += n - Phys.MAX_SUBSTEPS
            n = Phys.MAX_SUBSTEPS
            carryNs = 0L
        } else carryNs -= n * STEP_NS
        if (n > 0) {
            val t0 = flow.sim.time
            flow.tick(n * Phys.H)
            winSim += flow.sim.time - t0
            winSteps += n
            if (n > winMaxBatch) winMaxBatch = n
        }
        winWallNs += elapsed
        if (winWallNs >= LOG_NS) {
            lastRate = winSim / (winWallNs / 1e9)
            log("SIM rate=${"%.3f".format(lastRate)} steps=$winSteps maxBatch=$winMaxBatch dropped=$droppedSteps simT=${"%.2f".format(flow.sim.time)} strikes=${flow.strikesTotal}")
            resetWindow()
        }
        return n
    }

    private fun resetWindow() { winWallNs = 0L; winSim = 0.0; winSteps = 0; winMaxBatch = 0 }

    companion object {
        /** 1/360 s in nanoseconds (2 777 777 ns: the simulated clock runs 0.3 ppm fast, far inside the +-1 % budget). */
        const val STEP_NS = 1_000_000_000L / Phys.HZ
        const val LOG_NS = 5_000_000_000L
        /** v0.1.4: wake at 120 Hz (3 physics steps per wake) instead of 360 Hz. */
        const val WAKE_NS = STEP_NS * 3
    }
}
