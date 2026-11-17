package app.hondadash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

object Theme {
    val BG = Color.rgb(10, 10, 12)
    val PANEL = Color.rgb(24, 22, 26)
    val TEXT = Color.rgb(235, 235, 235)
    val DIM = Color.rgb(140, 140, 150)
    val ACCENT = Color.rgb(230, 60, 40)
    val ACCENT2 = Color.rgb(255, 140, 40)
    val OK = Color.rgb(60, 200, 110)
    val WARN = Color.rgb(255, 190, 40)
    val BAD = Color.rgb(255, 50, 50)
}

/** Round gauge with an arc, a needle-free bar and a big number. */
class GaugeView(ctx: Context, val key: String, val label: String, private val min: Double, private val max: Double,
                private val redFrom: Double? = null, private val decimals: Int = 0) : View(ctx) {
    var value: Double? = null
        set(v) { field = v; invalidate() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = Math.min(w, h)
        val sw = s * 0.09f
        rect.set((w - s) / 2 + sw, (h - s) / 2 + sw, (w + s) / 2 - sw, (h + s) / 2 - sw)
        arc.strokeWidth = sw
        arc.color = Theme.PANEL
        c.drawArc(rect, 135f, 270f, false, arc)
        if (redFrom != null) {
            arc.color = Color.argb(110, 255, 40, 40)
            val f = ((redFrom - min) / (max - min)).toFloat().coerceIn(0f, 1f)
            c.drawArc(rect, 135f + 270f * f, 270f * (1 - f), false, arc)
        }
        val v = value
        if (v != null && !v.isNaN()) {
            val f = ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
            arc.color = if (redFrom != null && v >= redFrom) Theme.BAD else Theme.ACCENT
            c.drawArc(rect, 135f, 270f * f, false, arc)
        }
        txt.color = Theme.TEXT
        txt.textSize = s * 0.22f
        val vs = if (v == null || v.isNaN()) "--" else if (decimals == 0) Math.round(v).toString() else String.format("%.${decimals}f", v)
        c.drawText(vs, w / 2, h / 2 + s * 0.08f, txt)
        txt.color = Theme.DIM
        txt.textSize = s * 0.09f
        c.drawText(label, w / 2, h / 2 + s * 0.30f, txt)
    }
}

/** Number tile: label on top, value in the middle, optional unit. Turns red above [alarmAbove]. */
class TileView(ctx: Context, val key: String, private val label: String, private val unit: String,
               private val alarmAbove: Double? = null, private val alarmBelow: Double? = null) : View(ctx) {
    var text: String = "--"
        set(v) { if (v != field) { field = v; invalidate() } }
    var numeric: Double? = null
    var stale = false
        set(v) { if (v != field) { field = v; invalidate() } }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val n = numeric
        val alarm = n != null && ((alarmAbove != null && n > alarmAbove) || (alarmBelow != null && n < alarmBelow))
        bg.color = if (alarm) Color.rgb(90, 15, 15) else Theme.PANEL
        r.set(3f, 3f, w - 3f, h - 3f)
        c.drawRoundRect(r, 12f, 12f, bg)
        p.typeface = Typeface.DEFAULT
        p.color = Theme.DIM
        p.textSize = h * 0.17f
        c.drawText(label, w / 2, h * 0.24f, p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = if (alarm) Theme.BAD else if (stale) Theme.DIM else Theme.TEXT
        var size = h * 0.40f
        p.textSize = size
        while (p.measureText(text) > w * 0.92f && size > 10f) { size -= 2f; p.textSize = size }
        c.drawText(text, w / 2, h * 0.66f, p)
        if (unit.isNotEmpty()) {
            p.typeface = Typeface.DEFAULT
            p.color = Theme.DIM
            p.textSize = h * 0.14f
            c.drawText(unit, w / 2, h * 0.88f, p)
        }
    }
}

/** On/off lamp with a label. */
class LampView(ctx: Context, val key: String, private val label: String) : View(ctx) {
    var on: Boolean? = null
        set(v) { if (v != field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = Math.min(w, h) * 0.22f
        p.color = when (on) { true -> Theme.OK; false -> Color.rgb(50, 50, 55); null -> Color.rgb(30, 30, 34) }
        c.drawCircle(w / 2, h * 0.38f, rad, p)
        p.color = Theme.DIM
        p.textSize = h * 0.18f
        c.drawText(label, w / 2, h * 0.88f, p)
    }
}

/** Horizontal bar for a signed value around zero, used for timing corrections. */
class BarView(ctx: Context, val key: String, private val label: String, private val min: Double, private val max: Double) : View(ctx) {
    var value: Double? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = Theme.PANEL
        c.drawRect(0f, h * 0.35f, w, h * 0.75f, p)
        val zero = (((0 - min) / (max - min)).toFloat().coerceIn(0f, 1f)) * w
        val v = value
        if (v != null && !v.isNaN()) {
            val x = (((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)) * w
            p.color = if (v < 0) Theme.ACCENT else Theme.OK
            c.drawRect(Math.min(zero, x), h * 0.35f, Math.max(zero, x), h * 0.75f, p)
        }
        p.color = Theme.TEXT
        c.drawRect(zero - 1, h * 0.30f, zero + 1, h * 0.80f, p)
        p.textSize = h * 0.26f
        p.color = Theme.DIM
        c.drawText(label, 4f, h * 0.27f, p)
        p.color = Theme.TEXT
        val s = if (v == null || v.isNaN()) "--" else String.format("%.1f", v)
        c.drawText(s, w - p.measureText(s) - 4f, h * 0.27f, p)
    }
}
