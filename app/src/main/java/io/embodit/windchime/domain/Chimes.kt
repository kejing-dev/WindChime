package io.embodit.windchime.domain

/**
 * The three chimes (design chimes.list): same five-tube shape, three materials and three pentatonic modes. Tube
 * lengths are the design table (f ~ 1/L^2 at one diameter per chime, the longest tube 0.30 m).
 */
enum class ChimeKind(
    val id: String,
    /** Outer tube radius, metres. */
    val tubeR: Float,
    /** Sail width x height, metres (glass = a paper strip, taller). */
    val sailW: Float,
    val sailH: Float,
    val sailT: Float,
    val notes: List<Note>,
) {
    BRASS(
        "brass", 0.008f, 0.07f, 0.10f, 0.004f,
        listOf(Note(1, "C5", 523.25f, Lv.GONG, 0.300f), Note(2, "D5", 587.33f, Lv.SHANG, 0.283f), Note(3, "E5", 659.26f, Lv.JUE, 0.267f),
            Note(4, "G5", 783.99f, Lv.ZHI, 0.245f), Note(5, "A5", 880.00f, Lv.YU, 0.231f)),
    ),
    BAMBOO(
        "bamboo", 0.014f, 0.07f, 0.10f, 0.004f,
        listOf(Note(1, "G4", 392.00f, Lv.ZHI, 0.300f), Note(2, "A4", 440.00f, Lv.YU, 0.283f), Note(3, "C5", 523.25f, Lv.GONG, 0.260f),
            Note(4, "D5", 587.33f, Lv.SHANG, 0.245f), Note(5, "E5", 659.26f, Lv.JUE, 0.231f)),
    ),
    GLASS(
        "glass", 0.009f, 0.06f, 0.12f, 0.002f,
        listOf(Note(1, "E5", 659.26f, Lv.JUE, 0.300f), Note(2, "G5", 783.99f, Lv.ZHI, 0.275f), Note(3, "A5", 880.00f, Lv.YU, 0.260f),
            Note(4, "C6", 1046.50f, Lv.GONG, 0.238f), Note(5, "D6", 1174.66f, Lv.SHANG, 0.225f)),
    );

    fun length(tube: Int): Float = notes[tube].lengthM

    /** Distance from the sail's hanging point (under the clapper) to the sail centre / bottom (physics.bodies). */
    val sailCordL: Float get() = 0.104f
    val sailCenterL: Float get() = sailCordL + sailH / 2
    val sailBottomL: Float get() = sailCordL + sailH

    companion object {
        fun of(id: String?): ChimeKind = entries.firstOrNull { it.id == id } ?: BRASS
    }
}

/** 宫商角徵羽 and the solfege syllable for the English tile (design spatialLayout.tokens). */
enum class Lv(val zh: String, val solfege: String) { GONG("宫", "do"), SHANG("商", "re"), JUE("角", "mi"), ZHI("徵", "sol"), YU("羽", "la") }

data class Note(val n: Int, val pitch: String, val hz: Float, val lv: Lv, val lengthM: Float) {
    /** Pitch letter without the octave ("C5" -> "C"). */
    val letter: String get() = pitch.takeWhile { !it.isDigit() }

    /** The two lines carved on the base tile: zh 「宫 / C」, en 「C / do」. */
    fun tileLines(zh: Boolean): Pair<String, String> = if (zh) lv.zh to letter else letter to lv.solfege
}

/** Wind levels (stateModifiers W0..W3). */
enum class WindLevel(val index: Int) { STILL(0), BREEZE(1), STEADY(2), GALE(3);
    companion object { fun of(i: Int): WindLevel = entries.firstOrNull { it.index == i } ?: STILL }
}

enum class Language { SYSTEM, ZH, EN }

/** Settings (design dataModel.prefs); everything is written to SharedPreferences on change. */
data class Settings(
    val chime: ChimeKind = ChimeKind.BRASS,
    val wind: WindLevel = WindLevel.STILL,
    /** 0..1; the slider snaps to [VolumeSnap.STOPS] near them. Default = high (100 %). */
    val volume: Float = 1.0f,
    val muted: Boolean = false,
    val gustSound: Boolean = true,
    val reducedMotion: Boolean = false,
    val language: Language = Language.SYSTEM,
    val firstRunDone: Boolean = false,
)

/**
 * The volume slider (B20 「点和拖都能变档」): the slider is bound to a local Float, every tap / drag changes it, and on
 * release the value snaps to a stop when it is within [SNAP] of one (low 35 % / mid 65 % / high 100 %).
 */
object VolumeSnap {
    val STOPS = floatArrayOf(0.35f, 0.65f, 1.0f)
    const val SNAP = 0.08f

    fun snap(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        val near = STOPS.minByOrNull { kotlin.math.abs(it - c) }!!
        return if (kotlin.math.abs(near - c) <= SNAP) near else c
    }

    fun percent(v: Float): Int = kotlin.math.round(v.coerceIn(0f, 1f) * 100).toInt()
}

/**
 * Press classification (design input.rule, M14): travel < 2 cm and < 0.35 s = a tap; travel >= 2 cm = a drag; a
 * press held longer without travel is neither (nothing happens), so one pinch never takes both paths.
 */
object TapRule {
    const val DRAG_M = 0.02f
    const val TAP_MS = 350L

    enum class Kind { TAP, DRAG, NONE }

    fun classify(travelM: Float, ms: Long): Kind = when {
        travelM >= DRAG_M -> Kind.DRAG
        ms < TAP_MS -> Kind.TAP
        else -> Kind.NONE
    }
}
