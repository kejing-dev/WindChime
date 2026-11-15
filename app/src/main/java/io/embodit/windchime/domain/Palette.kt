package io.embodit.windchime.domain

import kotlin.math.pow

/** Every UI colour as ARGB (Theme.kt wraps them; the contrast unit test reads them here, D13). */
object Palette {
    const val PRIMARY = 0xFF1F5F7AL
    const val ON_PRIMARY = 0xFFFFFFFFL
    const val PRIMARY_CONTAINER = 0xFFD7ECF4L
    const val SECONDARY = 0xFF8C6A3FL
    const val ERROR = 0xFFB3261EL
    const val ON_ERROR = 0xFFFFFFFFL
    const val INK = 0xFF1B1F23L
    const val INK_SECONDARY = 0xFF3A3F44L
    const val AMBER = 0xFFE0A040L
    const val CARD = 0xFFF7F3EAL
    const val BRASS = 0xFFC9A15AL
    const val WALNUT = 0xFF4A3222L
    const val SKY = 0xFF8ECAE6L
    /** Darkest window glass measured on the emulator (Cloud Air lineage, huarongdao / zen-orb ui-audit). */
    const val GLASS_DARK = 0xFFB4B3B1L
    /** Lightest glass. */
    const val GLASS_LIGHT = 0xFFE8E8E6L
    /** Unselected segment / secondary button fill on glass (fillSecondary, measured). */
    const val FILL_SECONDARY = 0xFFD9D9D7L
    /** Base tiles: carved ink on light wood (Labels.kt). */
    const val TILE_INK = 0xFF3A2A1CL
    const val TILE_WOOD = 0xFFE3C9A0L

    private fun lin(c: Int): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    fun luminance(argb: Long): Double = 0.2126 * lin(((argb shr 16) and 255).toInt()) + 0.7152 * lin(((argb shr 8) and 255).toInt()) + 0.0722 * lin((argb and 255).toInt())
    fun contrast(a: Long, b: Long): Double { val la = luminance(a); val lb = luminance(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }
}
