package io.embodit.windchime.ui.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.embodit.windchime.domain.ChimeKind

/**
 * Text carved into the base tiles, the panel tile and the paper sail, drawn into bitmaps at run time (design
 * spatialLayout.tokens: 5 positions x 3 chimes x 2 languages). Dark #3A2A1C on light wood #E3C9A0 (contrast 9.6:1).
 * [testF] draws the asymmetric 「F↑」 pattern instead, to check the texture orientation on the emulator (D10).
 */
internal object Labels {
    const val INK = 0xFF3A2A1C.toInt()
    const val WOOD = 0xFFE3C9A0.toInt()

    private fun paint(size: Float, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; textSize = size; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun grain(c: Canvas, w: Int, h: Int, base: Int) {
        c.drawColor(base)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18000000; strokeWidth = 2f }
        var y = 6f
        val r = java.util.Random(3)
        while (y < h) { c.drawLine(0f, y, w.toFloat(), y + r.nextFloat() * 6 - 3, p); y += 9 + r.nextFloat() * 10 }
    }

    private fun testF(c: Canvas, w: Int, h: Int) {
        c.drawText("F↑", w / 2f, h * 0.68f, paint(h * 0.6f))
    }

    /** A base tile: two lines (zh 「宫 / C」, en 「C / do」), 256 x 192 (the tile is 4.5 x 3.4 cm). */
    fun tile(kind: ChimeKind, tube: Int, zh: Boolean, testF: Boolean): Bitmap {
        val w = 256; val h = 192
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        grain(c, w, h, WOOD)
        // a carved border
        c.drawRoundRect(RectF(8f, 8f, w - 8f, h - 8f), 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x553A2A1C })
        if (testF) { testF(c, w, h); return b }
        val (a, s) = kind.notes[tube].tileLines(zh)
        c.drawText(a, w / 2f, h * 0.52f, paint(if (zh) 92f else 84f))
        c.drawText(s, w / 2f, h * 0.88f, paint(if (zh) 58f else 56f))
        return b
    }

    /** The panel tile on the screen: a small window glyph + 「面板 / Panel」, 256 x 150. */
    fun panel(zh: Boolean, word: String, testF: Boolean): Bitmap {
        val w = 256; val h = 150
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        grain(c, w, h, WOOD)
        if (testF) { testF(c, w, h); return b }
        val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = INK }
        c.drawRoundRect(RectF(26f, 42f, 92f, 108f), 10f, 10f, g)
        c.drawLine(26f, 62f, 92f, 62f, g)
        val p = paint(if (zh) 60f else 50f).apply { textAlign = Paint.Align.LEFT }
        c.drawText(word, 112f, h * 0.5f + p.textSize * 0.36f, p)
        return b
    }

    /** The glass chime's paper strip: one 「风」 (English: 「wind」, written down the strip), 128 x 256. */
    fun sailPaper(word: String, zh: Boolean, testF: Boolean): Bitmap {
        val w = 128; val h = 256
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(0xF4, 0xEE, 0xE2))
        if (testF) { testF(c, w, h); return b }
        val ink = paint(if (zh) 84f else 40f).apply { color = Color.rgb(0x22, 0x22, 0x22) }
        if (zh) c.drawText(word, w / 2f, h * 0.55f, ink)
        else { c.save(); c.rotate(90f, w / 2f, h / 2f); c.drawText(word, w / 2f, h / 2f + 14f, ink); c.restore() }
        return b
    }
}
