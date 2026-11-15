package io.embodit.windchime.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes the strikes of 30 s of gale (seed 7, after a 3 s ramp) per chime to app/build/loudness/events-<chime>.csv;
 * tools/loudness.py mixes the real samples with the app's gain law into qa/loudness-<version>-<chime>.wav
 * (design sound.loudness; "rendered from the sound code at the default gain, not a recording").
 */
class LoudnessEventsTest {
    @Test fun writeGaleEvents() {
        val dir = File("build/loudness").apply { mkdirs() }
        for (k in ChimeKind.entries) {
            val s = ChimeSim(k, 7); s.windTarget = Phys.WIND_MEAN[3]; s.run(3f)
            val t0 = s.time
            val sb = StringBuilder("t,tube,vol\n")
            val n = s.run(30f, 90) { st -> sb.append("%.4f,%d,%.4f\n".format(java.util.Locale.US, st.t - t0, st.tube, st.volume)) }
            File(dir, "events-${k.id}.csv").writeText(sb.toString())
            assertTrue(n > 60)
        }
    }
}
