package io.embodit.junglechess.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.embodit.junglechess.domain.model.Pieces
import io.embodit.junglechess.domain.model.Side

/**
 * Canvas painters for every texture of the board scene (Xiangqi BoardTextures approach): base-top labels
 * (rebuilt per language), the marker vocabulary (sample §6) and the den inscriptions.
 *
 * Orientation (D10): a bitmap's top edge maps to -Z of a plane laid flat (pitch -90°), i.e. away from the
 * user; labels are drawn so they read upright for the player the piece belongs to. Verified on the
 * emulator with [testArrow] ("F↑", debug `ftest on`).
 */
object BoardTextures {
    private val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private fun bitmap(w: Int, h: Int = w) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.TRANSPARENT) }

    /**
     * Base top for piece [id]: cream disc with a team-coloured ring; the animal name on the left crescent and
     * the rank on the right (the animal stands on the middle). [zh]: 象 / E.
     */
    fun baseTop(id: Int, zh: Boolean, size: Int = 256): Bitmap {
        val bmp = bitmap(size)
        val c = Canvas(bmp)
        val cx = size / 2f
        val team = if (Pieces.side(id) == Side.RED) 0xFFB5452E.toInt() else 0xFF2F5F9E.toInt()
        c.drawCircle(cx, cx, size * 0.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEFE3C8.toInt() })
        c.drawCircle(cx, cx, size * 0.455f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = team; style = Paint.Style.STROKE; strokeWidth = size * 0.05f })
        val a = Pieces.animal(id)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = team; typeface = bold; textAlign = Paint.Align.CENTER; textSize = size * 0.2f }
        val fm = text.fontMetrics
        val base = cx - (fm.ascent + fm.descent) / 2f
        c.drawText(if (zh) a.zh else a.rank.toString(), size * 0.2f, base, text)
        c.drawText(a.rank.toString(), size * 0.8f, base, text)
        return bmp
    }

    /**
     * Rank tag (critic B2): a team-coloured plate with the rank digit, standing at the user's edge of every base so
     * the rank reads at a glance from the seat for both teams. Digits only: no letters (R7).
     */
    fun rankTag(id: Int, w: Int = 128, h: Int = 104): Bitmap {
        val bmp = bitmap(w, h)
        val c = Canvas(bmp)
        val team = if (Pieces.side(id) == Side.RED) 0xFFB5452E.toInt() else 0xFF2F5F9E.toInt()
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h * 0.28f, h * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = team })
        c.drawRoundRect(RectF(w * 0.06f, h * 0.07f, w * 0.94f, h * 0.93f), h * 0.22f, h * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF7F1E3.toInt(); style = Paint.Style.STROKE; strokeWidth = h * 0.05f
        })
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF7F1E3.toInt(); typeface = bold; textAlign = Paint.Align.CENTER; textSize = h * 0.82f }
        val fm = t.fontMetrics
        c.drawText(Pieces.animal(id).rank.toString(), w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, t)
        return bmp
    }

    fun disc(color: Int, size: Int = 96): Bitmap {
        val bmp = bitmap(size)
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size * 0.46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return bmp
    }

    fun ring(color: Int, width: Float = 0.1f, size: Int = 128): Bitmap {
        val bmp = bitmap(size)
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size * (0.5f - width / 2 - 0.01f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = size * width
        })
        return bmp
    }

    /** Square frame; [dot] adds a centre dot (capture), [dashed] the last-move style, [arc] the leap arrow. */
    fun frame(color: Int, dashed: Boolean = false, dot: Boolean = false, arc: Boolean = false, size: Int = 128): Bitmap {
        val bmp = bitmap(size)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = size * 0.075f; strokeCap = Paint.Cap.ROUND }
        if (dashed) p.pathEffect = DashPathEffect(floatArrayOf(size * 0.12f, size * 0.09f), 0f)
        val inset = size * 0.07f
        c.drawRoundRect(RectF(inset, inset, size - inset, size - inset), size * 0.12f, size * 0.12f, p)
        if (dot) c.drawCircle(size / 2f, size / 2f, size * 0.12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        if (arc) {
            val a = Paint(p).apply { pathEffect = null; strokeWidth = size * 0.06f }
            val path = Path().apply { moveTo(size * 0.28f, size * 0.66f); quadTo(size * 0.5f, size * 0.18f, size * 0.72f, size * 0.66f) }
            c.drawPath(path, a)
            c.drawLine(size * 0.72f, size * 0.66f, size * 0.60f, size * 0.60f, a)
            c.drawLine(size * 0.72f, size * 0.66f, size * 0.74f, size * 0.52f, a)
        }
        return bmp
    }

    fun cross(color: Int, size: Int = 96): Bitmap {
        val bmp = bitmap(size)
        val c = Canvas(bmp)
        c.drawCircle(size / 2f, size / 2f, size * 0.46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = size * 0.14f; strokeCap = Paint.Cap.ROUND }
        c.drawLine(size * 0.3f, size * 0.3f, size * 0.7f, size * 0.7f, p)
        c.drawLine(size * 0.7f, size * 0.3f, size * 0.3f, size * 0.7f, p)
        return bmp
    }

    /** Engraved plate on the den lintel: 家 / Den, light stone text on dark. */
    fun denPlate(zh: Boolean, w: Int = 256, h: Int = 96): Bitmap {
        val bmp = bitmap(w, h)
        val c = Canvas(bmp)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h * 0.2f, h * 0.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5E5A50.toInt() })
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2EBDD.toInt(); typeface = bold; textAlign = Paint.Align.CENTER; textSize = h * 0.62f }
        val fm = t.fontMetrics
        c.drawText(if (zh) "家" else "Den", w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, t)
        return bmp
    }

    /** Coordinates: a..g along the near border, 1..9 along the left border (light text, transparent ground). */
    fun files(w: Int = 1024, h: Int = 96): Bitmap {
        val bmp = bitmap(w, h)
        val c = Canvas(bmp)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3E6CC.toInt(); typeface = bold; textAlign = Paint.Align.CENTER; textSize = h * 0.6f }
        val fm = t.fontMetrics
        for (i in 0 until 7) c.drawText("abcdefg"[i].toString(), w * (i + 0.5f) / 7f, h / 2f - (fm.ascent + fm.descent) / 2f, t)
        return bmp
    }

    fun ranks(w: Int = 96, h: Int = 1296): Bitmap {
        val bmp = bitmap(w, h)
        val c = Canvas(bmp)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3E6CC.toInt(); typeface = bold; textAlign = Paint.Align.CENTER; textSize = w * 0.6f }
        val fm = t.fontMetrics
        // top of the bitmap = far edge = row 1
        for (r in 1..9) c.drawText(r.toString(), w / 2f, h * (r - 0.5f) / 9f - (fm.ascent + fm.descent) / 2f, t)
        return bmp
    }

    /** Orientation test card (D10): an "F" and an up arrow; must read upright from the red seat. */
    fun testArrow(size: Int = 128): Bitmap {
        val bmp = bitmap(size)
        val c = Canvas(bmp)
        c.drawColor(0xCCFFFFFF.toInt())
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = bold; textAlign = Paint.Align.CENTER; textSize = size * 0.6f }
        c.drawText("F↑", size / 2f, size * 0.72f, t)
        return bmp
    }
}
