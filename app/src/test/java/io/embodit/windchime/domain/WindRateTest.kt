package io.embodit.windchime.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * physics.wind: the four levels, three seeds each, 60 s after a 3 s ramp, sound strikes per second in the design's
 * bands: still 0, breeze 0.1-0.25, steady 0.6-1.5, gale 2.5-5 (counted as whole strikes over 60 s).
 */
class WindRateTest {
    private val bands = listOf(0..0, 6..15, 36..90, 150..300)

    @Test fun everyLevelEveryChimeEverySeedIsInItsBand() {
        val report = StringBuilder()
        for (k in ChimeKind.entries) for (lvl in 0..3) for (seed in listOf(1L, 7L, 42L)) {
            val s = ChimeSim(k, seed)
            s.windTarget = Phys.WIND_MEAN[lvl]
            s.run(3f)
            val n = s.run(60f)
            report.append("${k.id} W$lvl seed $seed: $n strikes = ${"%.2f".format(n / 60f)}/s\n")
            assertTrue("${k.id} W$lvl seed $seed: $n strikes in 60 s, want ${bands[lvl]}", n in bands[lvl])
        }
        println(report)
    }
}
