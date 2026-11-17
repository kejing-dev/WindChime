package io.embodit.windchime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v0.1.3 (profile D55, gate low-fps-slowmo; user on the headset: "Windchime 在 gale 时候连续碰撞，会卡"): the system drops
 * the volume to 10 fps; the simulated time must still follow the wall clock and the strikes must not bunch up.
 */
class LowFpsTest {
    private fun flow(kind: ChimeKind = ChimeKind.BRASS, wind: WindLevel = WindLevel.of(3), sink: (Strike, Double) -> Unit = { _, _ -> }): ChimeFlow {
        var st = Settings(firstRunDone = true, chime = kind, wind = wind)
        return ChimeFlow({ st }, { st = it }, { s, _, lag -> sink(s, lag) }, { }, {}).apply { start(); sceneReady() }
    }

    @Test fun advanceTenTimesAtTenthOfASecondIsOneSecond() {
        val s = ChimeSim(ChimeKind.BRASS, 7)
        repeat(10) { s.advance(0.1f) }
        assertEquals("advance(0.1) x10", 1.0, s.time, 0.01)
        // even a 0.25 s frame is caught up whole
        val s2 = ChimeSim(ChimeKind.BRASS, 7)
        repeat(4) { s2.advance(0.25f) }
        assertEquals("advance(0.25) x4", 1.0, s2.time, 0.01)
    }

    @Test fun simLoopFollowsTheWallClockAtAnyWakeRate() {
        for (wakeMs in listOf(1.0, 2.7777, 11.1, 33.3, 100.0, 250.0)) {
            val f = flow()
            var ns = 0L
            val loop = SimLoop(f, clock = { ns })
            loop.pump(0L)
            val wall = 10.0
            val stepNs = (wakeMs * 1e6).toLong()
            while (ns < (wall * 1e9).toLong()) { ns += stepNs; loop.pump(ns) }
            val rate = f.sim.time / (ns / 1e9)
            println("wake ${wakeMs} ms: sim ${"%.4f".format(f.sim.time)} s / wall ${"%.4f".format(ns / 1e9)} s = ${"%.4f".format(rate)}")
            assertEquals("wake $wakeMs ms", 1.0, rate, 0.01)
        }
        // ten 0.1 s wakes = 1 s +-1 %
        val f = flow(); var ns = 0L; val loop = SimLoop(f, clock = { ns }); loop.pump(0L)
        repeat(10) { ns += 100_000_000L; loop.pump(ns) }
        assertEquals(1.0, f.sim.time, 0.01)
    }

    @Test fun galeStrikesAreTheSameAtTenAndNinetyFps() {
        val report = StringBuilder()
        for (k in ChimeKind.entries) for (seed in listOf(1L, 7L, 42L)) {
            val counts = IntArray(2); val coll = IntArray(2)
            for ((j, fps) in listOf(10, 90).withIndex()) {
                val s = ChimeSim(k, seed); s.windTarget = Phys.WIND_MEAN[3]
                s.run(3f, fps)
                val c0 = s.collisions
                counts[j] = s.run(10f, fps)
                coll[j] = s.collisions - c0
            }
            report.append("${k.id} seed $seed: strikes 10fps=${counts[0]} 90fps=${counts[1]}  collisions ${coll[0]} / ${coll[1]}\n")
            assertTrue("${k.id} seed $seed strikes ${counts.toList()}", counts[1] > 0 && abs(counts[0] - counts[1]) <= 0.10 * counts[1])
            assertTrue("${k.id} seed $seed collisions ${coll.toList()}", abs(coll[0] - coll[1]) <= 0.10 * coll[1])
        }
        println(report)
    }

    @Test fun strikesDoNotBunchAtTenFps() {
        for (k in ChimeKind.entries) {
            val s = ChimeSim(k, 7); s.windTarget = Phys.WIND_MEAN[3]
            s.run(3f, 10)
            val times = ArrayList<Double>()
            s.run(10f, 10) { times += it.t }
            assertTrue("${k.id}: strikes ${times.size}", times.size > 20)
            val perMs = times.groupingBy { (it * 1000).toLong() }.eachCount()
            assertTrue("${k.id}: ${perMs.values.maxOrNull()} strikes in one 1 ms bin", perMs.values.max() <= 2)
            // and they spread over the whole 10 s (v0.1.2 at 10 fps: 2.2 s of simulated time)
            assertTrue("${k.id}: span ${times.last() - times.first()}", times.last() - times.first() > 8.0)
        }
    }

    /**
     * The mixer side: the sim thread hands strikes over in batches (normally 1 step, after a stall up to 0.25 s). Each
     * is scheduled SCHED_S - lag after the hand-over, i.e. at its own simulated time + SCHED_S: with wakes up to 25 ms
     * apart the onsets keep exactly the simulated spacing, and never more than 2 land in the same 1 ms.
     */
    @Test fun scheduledOnsetsKeepTheSimulatedSpacing() {
        val sched = 0.03
        val rng = java.util.Random(3)
        val onsets = ArrayList<Pair<Double, Double>>() // (sim t, wall onset)
        var ns = 0L
        val f = flow(sink = { s, lag -> onsets += s.t to (ns / 1e9 + maxOf(0.0, sched - lag)) })
        val loop = SimLoop(f, clock = { ns }); loop.pump(0L)
        while (ns < 13_000_000_000L) { ns += (1 + rng.nextInt(25)) * 1_000_000L; loop.pump(ns) }
        val phys = onsets.filter { it.first > 3.0 }
        assertTrue("strikes ${phys.size}", phys.size > 20)
        // simulated time and wall clock start together here, so onset = t + SCHED within a step
        for ((t, w) in phys) assertEquals("onset of strike at $t", t + sched, w, 1.0 / Phys.HZ + 1e-6)
        val perMs = phys.groupingBy { (it.second * 1000).toLong() }.eachCount()
        assertTrue("max per 1 ms: ${perMs.values.max()}", perMs.values.max() <= 2)
    }

    @Test fun aRenderFrameAtTenFpsSeesTheLatestPose() {
        val f = flow()
        var ns = 0L
        val loop = SimLoop(f, clock = { ns }); loop.pump(0L)
        val pose = ChimePose()
        var lastSim = 0.0
        // sim thread at 360 Hz, a render frame every 100 ms reads a snapshot
        for (frame in 1..30) {
            repeat(36) { ns += SimLoop.STEP_NS; loop.pump(ns) }
            f.snapshot(pose)
            assertEquals(f.sim.time, pose.simTime, 1e-9)
            assertTrue(pose.simTime - lastSim > 0.099)
            lastSim = pose.simTime
        }
        assertEquals(3.0, pose.simTime, 0.01)
    }

    @Test fun mixerStartsAScheduledNoteOnItsFrame() {
        val m = Mixer(44100)
        val click = Mixer.Sample(ShortArray(2000) { if (it < 4) 20000 else 0 })
        m.play(click, 0.5f, key = "a")
        m.play(click, 0.5f, key = "b", delayS = 0.010f)
        m.play(click, 0.5f, key = "c", delayS = 0.0234f)
        val out = FloatArray(240 * 2)
        repeat(10) { m.render(out, 240) }
        val o = m.onsets.toList()
        assertEquals(listOf(0L, 441L, 1031L), o)
    }
}
