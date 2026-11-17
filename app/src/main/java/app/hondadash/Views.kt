package app.hondadash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View

/** One colour scheme. Only the accent changes between themes. Warnings stay red and amber in every theme. */
class Palette(val id: String, val name: String, val accent: Int)

object Theme {
    val palettes = listOf(
        Palette("crimson", "Crimson", Color.rgb(255, 42, 60)),
        Palette("papaya", "Papaya", Color.rgb(255, 107, 0)),
        Palette("amber", "Amber", Color.rgb(255, 179, 0)),
        Palette("green", "Light Green", Color.rgb(74, 222, 128)),
        Palette("cyan", "Cyan", Color.rgb(0, 240, 255)),
        Palette("cobalt", "Cobalt", Color.rgb(41, 121, 255)),
        Palette("violet", "Violet", Color.rgb(168, 85, 247)),
    )
    var current: Palette = palettes[1]
        private set
    val ACCENT: Int get() = current.accent

    val BG = Color.rgb(7, 8, 12)
    val PANEL_TOP = Color.rgb(24, 28, 40)
    val PANEL_BOTTOM = Color.rgb(13, 16, 24)
    val PANEL = Color.rgb(16, 19, 28)
    val BAR = Color.rgb(13, 16, 24)
    val BORDER = Color.argb(26, 255, 255, 255)
    val TEXT = Color.rgb(248, 250, 252)
    val DIM = Color.rgb(148, 163, 184)
    val DIM2 = Color.rgb(100, 116, 139)
    val OK = Color.rgb(34, 197, 94)
    val WARN = Color.rgb(245, 158, 11)
    val BAD = Color.rgb(255, 42, 60)

    val TECH: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val MONO: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    val DISPLAY: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    fun set(id: String?) { current = palettes.firstOrNull { it.id == id } ?: palettes[1] }

    fun withAlpha(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /** Card background: dark gradient, thin light border, rounded corners. */
    fun card(radius: Float, border: Int = BORDER): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(PANEL_TOP, PANEL_BOTTOM)).apply {
            cornerRadius = radius
            setStroke(1, border)
        }

    fun fill(color: Int, radius: Float, border: Int = 0, borderWidth: Int = 1): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (border != 0) setStroke(borderWidth, border)
    }
}

/** Draws the shared card background straight on a canvas (used by the custom views). */
private object CardPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val r = RectF()
    fun draw(c: Canvas, w: Float, h: Float, radius: Float, tint: Int? = null, border: Int = Theme.BORDER) {
        r.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        fill.shader = LinearGradient(0f, 0f, w, h, tint ?: Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, fill)
        fill.shader = null
        line.color = border
        c.drawRoundRect(r, radius, radius, line)
    }
}

/** Screen density, so size limits are the same physical size on the head unit and the phone. */
private fun View.dp(v: Float): Float = v * resources.displayMetrics.density

private fun fmtValue(v: Double?, decimals: Int): String =
    if (v == null || v.isNaN()) "--" else if (decimals == 0) Math.round(v).toString() else String.format("%.${decimals}f", v)

/**
 * Round instrument with a metal bezel, ticks, numbers, a red zone, a needle and a digital read-out.
 * The needle glides to new values, because the K-line delivers about one reading a second.
 */
class GaugeView(
    ctx: Context, val key: String, private val title: String, private val unit: String,
    private val min: Double, private val max: Double, private val major: Double, private val minorDiv: Int,
    private val redFrom: Double? = null, private val decimals: Int = 0,
    private val tickLabel: (Double) -> String = { Math.round(it).toString() },
) : View(ctx) {
    var value: Double? = null
        set(v) { field = v; if (v != null && !v.isNaN() && v > peak) peak = v; invalidate() }
    /** Small text at the top right of the card (peak value, temperature state). */
    var badge: String = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    /** Blink the read-out and needle (VTEC on or high rpm). */
    var flash = false
        set(v) { if (v != field) { field = v; invalidate() } }
    /** Show the value in red (over temperature). */
    var alarm = false
        set(v) { if (v != field) { field = v; invalidate() } }
    var peak = Double.NEGATIVE_INFINITY
        private set

    private var shown = Double.NaN
    private var lastFrame = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()

    fun resetPeak() { peak = Double.NEGATIVE_INFINITY }

    private fun frac(v: Double): Float = ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    /** Clock angle (0 = up, clockwise) to canvas angle (0 = right, clockwise). */
    private fun rad(f: Float): Double = Math.toRadians((-135.0 + 270.0 * f) - 90.0)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val radius = Math.min(w, h) * 0.06f
        CardPainter.draw(c, w, h, radius)
        val head = h * 0.11f
        // header: title left, badge right
        t.typeface = Theme.TECH
        t.textSize = Math.max(dp(10f), h * 0.048f)
        t.textAlign = Paint.Align.LEFT
        t.color = Theme.DIM
        c.drawText(title.uppercase(), w * 0.05f, head * 0.72f, t)
        if (badge.isNotEmpty()) {
            t.typeface = Theme.MONO
            t.textSize = Math.max(dp(9f), h * 0.042f)
            t.textAlign = Paint.Align.RIGHT
            t.color = if (alarm) Theme.BAD else Theme.DIM
            c.drawText(badge, w * 0.95f, head * 0.72f, t)
        }
        t.textAlign = Paint.Align.CENTER

        val cx = w / 2
        val cy = head + (h - head) / 2
        val R = Math.min(w * 0.92f, (h - head) * 0.97f) / 2
        val accent = Theme.ACCENT
        val now = System.currentTimeMillis()
        val blinkOn = flash && (now / 160) % 2 == 0L

        // bezel and face
        p.shader = LinearGradient(cx - R, cy - R, cx + R, cy + R,
            intArrayOf(Color.rgb(71, 85, 105), Color.rgb(30, 41, 59), Color.rgb(100, 116, 139), Color.rgb(15, 23, 42), Color.rgb(51, 65, 85)),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R, p)
        p.shader = null
        p.color = Color.rgb(6, 8, 13)
        c.drawCircle(cx, cy, R * 0.939f, p)
        p.shader = RadialGradient(cx, cy, R * 0.913f,
            intArrayOf(Color.rgb(19, 23, 34), Color.rgb(9, 11, 16), Color.rgb(4, 5, 8), Color.BLACK),
            floatArrayOf(0f, 0.72f, 0.94f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 0.913f, p)
        p.shader = null
        s.strokeCap = Paint.Cap.BUTT
        s.strokeWidth = Math.max(1f, R * 0.013f)
        s.color = Theme.withAlpha(accent, if (flash) 150 else 56)
        c.drawCircle(cx, cy, R * 0.878f, s)

        // red zone
        if (redFrom != null) {
            val f0 = frac(redFrom)
            val rr = R * 0.817f
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            s.strokeWidth = R * 0.06f
            s.color = Theme.withAlpha(Theme.BAD, 190)
            c.drawArc(rect, (-225f + 270f * f0), 270f * (1f - f0), false, s)
        }

        // ticks and numbers
        val steps = Math.round((max - min) / major).toInt()
        val minors = steps * minorDiv
        s.strokeCap = Paint.Cap.ROUND
        t.typeface = Theme.TECH
        t.textSize = R * (if (steps > 10) 0.083f else 0.1f)
        for (i in 0..minors) {
            val f = i.toFloat() / minors
            val v = min + f * (max - min)
            val a = rad(f)
            val isMajor = i % minorDiv == 0
            val red = redFrom != null && v >= redFrom - 1e-6
            val r1 = R * 0.843f
            val r2 = R * (if (isMajor) 0.739f else 0.791f)
            s.strokeWidth = R * (if (isMajor) 0.019f else 0.0096f)
            s.color = if (red) Theme.BAD else if (isMajor) Theme.TEXT else Color.argb(115, 148, 163, 184)
            val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
            c.drawLine(cx + r1 * ca, cy + r1 * sa, cx + r2 * ca, cy + r2 * sa, s)
            if (isMajor) {
                val lr = R * 0.617f
                t.color = if (red) Color.rgb(255, 77, 94) else Color.rgb(226, 232, 240)
                c.drawText(tickLabel(v), cx + lr * ca, cy + lr * sa + t.textSize * 0.36f, t)
            }
        }

        // digital window
        val v = value
        val have = v != null && !v.isNaN()
        rect.set(cx - R * 0.33f, cy + R * 0.33f, cx + R * 0.33f, cy + R * 0.66f)
        p.color = if (blinkOn) Theme.withAlpha(accent, 90) else Color.argb(235, 5, 7, 12)
        c.drawRoundRect(rect, R * 0.07f, R * 0.07f, p)
        s.strokeWidth = Math.max(1f, R * 0.01f)
        s.color = if (alarm) Theme.BAD else if (flash) accent else Color.argb(40, 255, 255, 255)
        c.drawRoundRect(rect, R * 0.07f, R * 0.07f, s)
        t.typeface = Theme.DISPLAY
        t.textSize = R * 0.16f
        t.color = if (alarm) Theme.BAD else Theme.TEXT
        var vs = R * 0.16f
        t.textSize = vs
        val txt = fmtValue(v, decimals)
        while (t.measureText(txt) > R * 0.6f && vs > 6f) { vs -= 1f; t.textSize = vs }
        c.drawText(txt, cx, cy + R * 0.52f, t)
        t.typeface = Theme.TECH
        t.textSize = R * 0.085f
        t.color = if (alarm) Theme.BAD else Theme.DIM
        c.drawText(unit, cx, cy + R * 0.62f, t)

        // needle glides toward the value: about 63% of the way every 180 ms
        val target = if (have) frac(v!!) else 0f
        val dt = if (lastFrame == 0L) 1000L else now - lastFrame
        lastFrame = now
        shown = if (shown.isNaN()) target.toDouble() else shown + (target - shown) * (1 - Math.exp(-dt / 180.0))
        val f = shown.toFloat()
        val a = rad(f)
        val ux = Math.cos(a).toFloat(); val uy = Math.sin(a).toFloat()
        val px = -uy; val py = ux
        fun blade(len: Float, base: Float, tip: Float, back: Float) {
            path.reset()
            path.moveTo(cx + px * base - ux * back, cy + py * base - uy * back)
            path.lineTo(cx + ux * len + px * tip, cy + uy * len + py * tip)
            path.lineTo(cx + ux * len - px * tip, cy + uy * len - py * tip)
            path.lineTo(cx - px * base - ux * back, cy - py * base - uy * back)
            path.close()
        }
        val needle = if (blinkOn) Theme.TEXT else accent
        blade(R * 0.817f, R * 0.06f, R * 0.025f, 0f)
        p.color = Theme.withAlpha(needle, 70)
        c.drawPath(path, p)
        blade(R * 0.817f, R * 0.028f, R * 0.0095f, 0f)
        p.color = needle
        c.drawPath(path, p)
        path.reset()
        path.moveTo(cx + px * R * 0.03f, cy + py * R * 0.03f)
        path.lineTo(cx - ux * R * 0.21f + px * R * 0.016f, cy - uy * R * 0.21f + py * R * 0.016f)
        path.lineTo(cx - ux * R * 0.21f - px * R * 0.016f, cy - uy * R * 0.21f - py * R * 0.016f)
        path.lineTo(cx - px * R * 0.03f, cy - py * R * 0.03f)
        path.close()
        p.color = Color.rgb(30, 41, 59)
        c.drawPath(path, p)
        s.strokeWidth = Math.max(1f, R * 0.01f)
        s.color = Color.argb(215, 255, 255, 255)
        c.drawLine(cx + ux * R * 0.05f, cy + uy * R * 0.05f, cx + ux * R * 0.79f, cy + uy * R * 0.79f, s)

        // centre cap
        p.shader = RadialGradient(cx - R * 0.045f, cy - R * 0.045f, R * 0.17f,
            intArrayOf(Color.rgb(148, 163, 184), Color.rgb(51, 65, 85), Color.rgb(15, 23, 42), Color.rgb(2, 6, 23)),
            floatArrayOf(0f, 0.45f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 0.13f, p)
        p.shader = null
        p.color = Color.rgb(9, 13, 22)
        c.drawCircle(cx, cy, R * 0.052f, p)
        s.strokeWidth = Math.max(1f, R * 0.013f)
        s.color = accent
        c.drawCircle(cx, cy, R * 0.052f, s)

        if (Math.abs(target - f) > 0.002f || flash) postInvalidateOnAnimation()
    }
}

/** Row of 12 shift lights: green, amber, red. Fills from [from] to [to] rpm and blinks when [flash] is set. */
class ShiftBarView(ctx: Context) : View(ctx) {
    var rpm: Double? = null
        set(v) { if (v != field) { field = v; invalidate() } }
    var flash = false
        set(v) { if (v != field) { field = v; invalidate() } }
    var from = 3000.0
    var to = 6500.0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.BLACK)
        val n = 12
        val gap = Math.max(dp(2f), h * 0.5f)
        val segW = (w - gap * (n + 1)) / n
        val v = rpm
        val lit = if (v == null || v.isNaN()) 0 else Math.round(((v - from) / (to - from)).coerceIn(0.0, 1.0) * n).toInt()
        val off = flash && (System.currentTimeMillis() / 160) % 2 == 1L
        for (i in 0 until n) {
            val x = gap + i * (segW + gap)
            r.set(x, h * 0.15f, x + segW, h * 0.85f)
            val col = if (i < 4) Theme.OK else if (i < 8) Theme.WARN else Theme.BAD
            p.color = if (i < lit && !off) col else Color.argb(22, 255, 255, 255)
            c.drawRoundRect(r, h, h, p)
        }
        if (flash) postInvalidateDelayed(80)
    }
}

/**
 * Value card: label top left, small tag top right, big value in the middle, unit or a fill bar at the bottom.
 * Turns red above [alarmAbove] or below [alarmBelow].
 */
class TileView(ctx: Context, val key: String, private val label: String, private val unit: String,
               private val alarmAbove: Double? = null, private val alarmBelow: Double? = null,
               private val tag: String = "", private val style: Int = NUMBER, private val barMax: Double? = null) : View(ctx) {
    var text: String = "--"
        set(v) { if (v != field) { field = v; invalidate() } }
    var numeric: Double? = null
    var stale = false
        set(v) { if (v != field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val n = numeric
        val alarm = n != null && ((alarmAbove != null && n > alarmAbove) || (alarmBelow != null && n < alarmBelow))
        CardPainter.draw(c, w, h, Math.min(w, h) * 0.12f, if (alarm) Color.rgb(90, 15, 20) else null,
            if (alarm) Theme.withAlpha(Theme.BAD, 160) else Theme.BORDER)
        val pad = Math.max(dp(6f), Math.min(w * 0.06f, dp(12f)))
        // label and tag
        p.textAlign = Paint.Align.LEFT
        p.typeface = Theme.TECH
        p.color = Theme.DIM
        var ls = Math.max(dp(10f), Math.min(h * 0.15f, dp(20f)))
        p.textSize = ls
        val lab = label.uppercase()
        val tagW = if (tag.isNotEmpty()) w * 0.3f else 0f
        while (p.measureText(lab) > w - 2 * pad - tagW && ls > dp(8f)) { ls -= 1f; p.textSize = ls }
        c.drawText(lab, pad, pad + ls * 0.8f, p)
        if (tag.isNotEmpty()) {
            p.textAlign = Paint.Align.RIGHT
            p.typeface = Theme.MONO
            p.textSize = ls * 0.72f
            p.color = Theme.DIM2
            c.drawText(tag, w - pad, pad + ls * 0.75f, p)
        }
        // footer first, so the value is centred in the space left between the label and the footer
        val labelBottom = pad + ls
        val bh = Math.max(dp(3f), h * 0.045f)
        val unitSize = Math.max(dp(9f), h * 0.11f)
        var showUnit = barMax == null && unit.isNotEmpty()
        var footer = if (barMax != null) bh + pad else if (showUnit) unitSize + pad * 0.6f else pad * 0.5f
        // short card (phone): drop the unit line to give the value room
        if (showUnit && h - labelBottom - footer < ls * 1.6f) { showUnit = false; footer = pad * 0.5f }
        val room = h - labelBottom - footer
        if (barMax != null) {
            val y = h - pad - bh
            r.set(pad, y, w - pad, y + bh)
            p.color = Color.argb(20, 255, 255, 255)
            c.drawRoundRect(r, bh, bh, p)
            if (n != null && !n.isNaN()) {
                r.right = pad + (w - 2 * pad) * (n / barMax).toFloat().coerceIn(0f, 1f)
                p.color = Theme.ACCENT
                c.drawRoundRect(r, bh, bh, p)
            }
        } else if (showUnit) {
            p.textAlign = Paint.Align.CENTER
            p.typeface = Theme.MONO
            p.color = Theme.DIM2
            p.textSize = unitSize
            c.drawText(unit, w / 2, h - pad * 0.8f, p)
        }
        // value
        p.textAlign = Paint.Align.CENTER
        p.typeface = when (style) { WORD -> Theme.TECH; ACCENT -> Theme.DISPLAY; else -> Theme.MONO }
        p.color = when {
            alarm -> Theme.BAD
            stale -> Theme.DIM2
            style == ACCENT -> Theme.ACCENT
            else -> Theme.TEXT
        }
        var size = Math.min(h * (if (style == ACCENT) 0.40f else 0.33f), room * 0.9f)
        p.textSize = size
        while (p.measureText(text) > w - 2 * pad && size > dp(8f)) { size -= 1f; p.textSize = size }
        c.drawText(text, w / 2, labelBottom + room / 2 + size * 0.36f, p)
    }

    companion object {
        const val NUMBER = 0
        const val WORD = 1
        const val ACCENT = 2
    }
}

/** On/off lamp card. Lit in the theme colour. */
class LampView(ctx: Context, val key: String, private val label: String) : View(ctx) {
    var on: Boolean? = null
        set(v) { if (v != field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        CardPainter.draw(c, w, h, Math.min(w, h) * 0.12f)
        val rad = Math.min(w, h) * 0.16f
        val cy = h * 0.40f
        if (on == true) {
            p.color = Theme.withAlpha(Theme.ACCENT, 60)
            c.drawCircle(w / 2, cy, rad * 1.7f, p)
            p.color = Theme.ACCENT
        } else p.color = if (on == false) Color.rgb(51, 58, 72) else Color.rgb(30, 34, 44)
        c.drawCircle(w / 2, cy, rad, p)
        p.typeface = Theme.TECH
        p.color = if (on == true) Theme.TEXT else Theme.DIM
        var ts = Math.max(dp(10f), h * 0.16f)
        p.textSize = ts
        val lab = label.uppercase()
        while (p.measureText(lab) > w * 0.92f && ts > dp(8f)) { ts -= 1f; p.textSize = ts }
        c.drawText(lab, w / 2, h * 0.86f, p)
    }
}

/** Horizontal bar for a signed value around zero, used for timing corrections. */
class BarView(ctx: Context, val key: String, private val label: String, private val min: Double, private val max: Double) : View(ctx) {
    var value: Double? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        CardPainter.draw(c, w, h, h * 0.18f)
        val pad = h * 0.12f
        val top = h * 0.52f; val bot = h * 0.80f
        r.set(pad, top, w - pad, bot)
        p.color = Color.argb(20, 255, 255, 255)
        c.drawRoundRect(r, 4f, 4f, p)
        val span = w - 2 * pad
        val zero = pad + (((0 - min) / (max - min)).toFloat().coerceIn(0f, 1f)) * span
        val v = value
        if (v != null && !v.isNaN()) {
            val x = pad + (((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)) * span
            p.color = if (v < 0) Theme.WARN else Theme.ACCENT
            c.drawRect(Math.min(zero, x), top, Math.max(zero, x), bot, p)
        }
        p.color = Theme.TEXT
        c.drawRect(zero - 1, top - 3, zero + 1, bot + 3, p)
        p.textSize = h * 0.26f
        p.typeface = Theme.TECH
        p.color = Theme.DIM
        p.textAlign = Paint.Align.LEFT
        c.drawText(label.uppercase(), pad, h * 0.38f, p)
        p.typeface = Theme.MONO
        p.color = Theme.TEXT
        p.textAlign = Paint.Align.RIGHT
        c.drawText(if (v == null || v.isNaN()) "--" else String.format("%.1f", v), w - pad, h * 0.38f, p)
    }
}
