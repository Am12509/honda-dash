package app.hondadash

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
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
 * Moves a shown value smoothly toward each new reading. The move takes as long as the time between readings,
 * so the needle keeps moving at an even speed instead of jumping and waiting (about one reading behind).
 */
class Glide {
    private var from = Double.NaN
    private var to = Double.NaN
    private var t0 = 0L
    private var dur = 400L
    private var lastStamp = Long.MIN_VALUE
    private var lastArrive = 0L

    /** [stamp] is the time the value was read. A new stamp starts a new move. Null clears the value. */
    fun set(v: Double?, stamp: Long, now: Long) {
        if (v == null || v.isNaN()) { from = Double.NaN; to = Double.NaN; lastStamp = Long.MIN_VALUE; return }
        if (stamp == lastStamp) return
        val cur = value(now)
        val gap = if (lastArrive == 0L) 400L else now - lastArrive
        lastStamp = stamp
        lastArrive = now
        from = if (cur.isNaN()) v else cur
        to = v
        t0 = now
        dur = gap.coerceIn(MIN_MS, MAX_MS)
    }

    fun value(now: Long): Double {
        if (to.isNaN()) return Double.NaN
        if (from.isNaN()) return to
        val f = ((now - t0).toDouble() / dur).coerceIn(0.0, 1.0)
        return from + (to - from) * f
    }

    fun moving(now: Long): Boolean = !to.isNaN() && now - t0 < dur

    companion object {
        const val MIN_MS = 120L
        const val MAX_MS = 1500L
    }
}

/**
 * Round instrument: metal bezel, accent progress ring, ticks lit up to the needle, red zone, glowing needle and a
 * digital read-out. Feed it with [update]; the needle glides between readings.
 */
class GaugeView(
    ctx: Context, val key: String, private val title: String, private val unit: String,
    private val min: Double, private val max: Double, private val major: Double, private val minorDiv: Int,
    private val redFrom: Double? = null, private val decimals: Int = 0,
    private val tickLabel: (Double) -> String = { Math.round(it).toString() },
) : View(ctx) {
    /** Latest reading (shown as the number). */
    var value: Double? = null
        private set
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

    private val glide = Glide()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()

    fun update(v: Double?, stamp: Long, now: Long = System.currentTimeMillis()) {
        val changed = v != value
        value = v
        if (v != null && !v.isNaN() && v > peak) peak = v
        glide.set(v, stamp, now)
        if (changed || glide.moving(now)) invalidate()
    }

    /** The value the needle shows right now (between readings). */
    fun shown(now: Long = System.currentTimeMillis()): Double = glide.value(now)

    fun resetPeak() { peak = Double.NEGATIVE_INFINITY }

    private fun frac(v: Double): Float = ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    /** Clock angle (0 = up, clockwise) to canvas angle (0 = right, clockwise). */
    private fun rad(f: Float): Double = Math.toRadians((-135.0 + 270.0 * f) - 90.0)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val radius = Math.min(w, h) * 0.06f
        CardPainter.draw(c, w, h, radius)
        val head = h * 0.11f
        val accent = Theme.ACCENT
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
            t.color = if (alarm) Theme.BAD else accent
            c.drawText(badge, w * 0.95f, head * 0.72f, t)
        }
        t.textAlign = Paint.Align.CENTER

        val cx = w / 2
        val cy = head + (h - head) / 2
        val R = Math.min(w * 0.92f, (h - head) * 0.97f) / 2
        val now = System.currentTimeMillis()
        val blinkOn = flash && (now / 160) % 2 == 0L
        val sv = glide.value(now)
        val f = if (sv.isNaN()) 0f else frac(sv)

        // bezel and face (face lightly tinted with the accent)
        p.shader = LinearGradient(cx - R, cy - R, cx + R, cy + R,
            intArrayOf(Color.rgb(71, 85, 105), Color.rgb(30, 41, 59), Color.rgb(100, 116, 139), Color.rgb(15, 23, 42), Color.rgb(51, 65, 85)),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R, p)
        p.shader = null
        p.color = Color.rgb(6, 8, 13)
        c.drawCircle(cx, cy, R * 0.939f, p)
        p.shader = RadialGradient(cx, cy, R * 0.913f,
            intArrayOf(mix(Color.rgb(19, 23, 34), accent, 0.10f), Color.rgb(9, 11, 16), Color.rgb(4, 5, 8), Color.BLACK),
            floatArrayOf(0f, 0.72f, 0.94f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 0.913f, p)
        p.shader = null

        // accent progress ring: dim track, lit arc up to the needle with a soft glow
        val pr = R * 0.878f
        rect.set(cx - pr, cy - pr, cx + pr, cy + pr)
        s.strokeCap = Paint.Cap.BUTT
        s.strokeWidth = R * 0.04f
        s.color = Theme.withAlpha(accent, 38)
        c.drawArc(rect, -225f, 270f, false, s)
        if (f > 0.002f) {
            s.strokeCap = Paint.Cap.ROUND
            s.strokeWidth = R * 0.10f
            s.color = Theme.withAlpha(accent, if (flash) 110 else 60)
            c.drawArc(rect, -225f, 270f * f, false, s)
            s.strokeWidth = R * 0.04f
            s.color = if (blinkOn) Theme.TEXT else accent
            c.drawArc(rect, -225f, 270f * f, false, s)
        }

        // red zone
        if (redFrom != null) {
            val f0 = frac(redFrom)
            val rr = R * 0.80f
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            s.strokeCap = Paint.Cap.BUTT
            s.strokeWidth = R * 0.035f
            s.color = Theme.withAlpha(Theme.BAD, 200)
            c.drawArc(rect, (-225f + 270f * f0), 270f * (1f - f0), false, s)
        }

        // ticks (lit in the accent up to the needle) and numbers
        val steps = Math.round((max - min) / major).toInt()
        val minors = steps * minorDiv
        s.strokeCap = Paint.Cap.ROUND
        t.typeface = Theme.TECH
        t.textSize = R * (if (steps > 10) 0.083f else 0.1f)
        for (i in 0..minors) {
            val fi = i.toFloat() / minors
            val v = min + fi * (max - min)
            val a = rad(fi)
            val isMajor = i % minorDiv == 0
            val red = redFrom != null && v >= redFrom - 1e-6
            val lit = !sv.isNaN() && fi <= f + 1e-4f
            val r1 = R * 0.835f
            val r2 = R * (if (isMajor) 0.735f else 0.785f)
            s.strokeWidth = R * (if (isMajor) 0.019f else 0.0096f)
            s.color = when {
                red -> Theme.BAD
                lit -> if (isMajor) accent else Theme.withAlpha(accent, 200)
                isMajor -> Theme.TEXT
                else -> Color.argb(115, 148, 163, 184)
            }
            val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
            c.drawLine(cx + r1 * ca, cy + r1 * sa, cx + r2 * ca, cy + r2 * sa, s)
            if (isMajor) {
                val lr = R * 0.62f
                t.color = if (red) Color.rgb(255, 77, 94) else Color.rgb(226, 232, 240)
                c.drawText(tickLabel(v), cx + lr * ca, cy + lr * sa + t.textSize * 0.36f, t)
            }
        }

        // digital window
        val v = value
        rect.set(cx - R * 0.33f, cy + R * 0.33f, cx + R * 0.33f, cy + R * 0.66f)
        p.color = if (blinkOn) Theme.withAlpha(accent, 110) else Color.argb(235, 5, 7, 12)
        c.drawRoundRect(rect, R * 0.07f, R * 0.07f, p)
        s.strokeWidth = Math.max(1f, R * 0.012f)
        s.color = if (alarm) Theme.BAD else Theme.withAlpha(accent, if (flash) 255 else 140)
        c.drawRoundRect(rect, R * 0.07f, R * 0.07f, s)
        t.typeface = Theme.DISPLAY
        t.color = if (alarm) Theme.BAD else Theme.TEXT
        var vs = R * 0.16f
        t.textSize = vs
        val txt = fmtValue(v, decimals)
        while (t.measureText(txt) > R * 0.6f && vs > 6f) { vs -= 1f; t.textSize = vs }
        c.drawText(txt, cx, cy + R * 0.52f, t)
        t.typeface = Theme.TECH
        t.textSize = R * 0.085f
        t.color = if (alarm) Theme.BAD else accent
        c.drawText(unit, cx, cy + R * 0.62f, t)

        // needle with a glow, accent coloured
        val a = rad(f)
        val ux = Math.cos(a).toFloat(); val uy = Math.sin(a).toFloat()
        val px = -uy; val py = ux
        fun blade(len: Float, base: Float, tip: Float) {
            path.reset()
            path.moveTo(cx + px * base, cy + py * base)
            path.lineTo(cx + ux * len + px * tip, cy + uy * len + py * tip)
            path.lineTo(cx + ux * len - px * tip, cy + uy * len - py * tip)
            path.lineTo(cx - px * base, cy - py * base)
            path.close()
        }
        val needle = if (blinkOn) Theme.TEXT else accent
        blade(R * 0.82f, R * 0.075f, R * 0.035f)
        p.color = Theme.withAlpha(needle, 45)
        c.drawPath(path, p)
        blade(R * 0.82f, R * 0.045f, R * 0.018f)
        p.color = Theme.withAlpha(needle, 90)
        c.drawPath(path, p)
        blade(R * 0.817f, R * 0.028f, R * 0.0095f)
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

        // centre cap with an accent ring
        p.shader = RadialGradient(cx - R * 0.045f, cy - R * 0.045f, R * 0.17f,
            intArrayOf(Color.rgb(148, 163, 184), Color.rgb(51, 65, 85), Color.rgb(15, 23, 42), Color.rgb(2, 6, 23)),
            floatArrayOf(0f, 0.45f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 0.13f, p)
        p.shader = null
        s.strokeWidth = Math.max(1f, R * 0.012f)
        s.color = Theme.withAlpha(accent, 150)
        c.drawCircle(cx, cy, R * 0.13f, s)
        p.color = Color.rgb(9, 13, 22)
        c.drawCircle(cx, cy, R * 0.052f, p)
        s.strokeWidth = Math.max(1f, R * 0.013f)
        s.color = accent
        c.drawCircle(cx, cy, R * 0.052f, s)

        if (glide.moving(now) || flash) postInvalidateOnAnimation()
    }
}

/**
 * Rpm bar: 30 segments from [from] to [to] rpm, green then amber then red, with a glow. The rpm glides between
 * readings like the needles, and the next segment fills in part, so the bar moves with every small change.
 * Blinks when [flash] is set (VTEC on or high rpm).
 */
class ShiftBarView(ctx: Context) : View(ctx) {
    var flash = false
        set(v) { if (v != field) { field = v; invalidate() } }
    var from = 1000.0
    var to = 6500.0
    private val glide = Glide()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun update(v: Double?, stamp: Long, now: Long = System.currentTimeMillis()) {
        glide.set(v, stamp, now)
        if (glide.moving(now)) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.rgb(3, 4, 7))
        val n = SEGMENTS
        val gap = Math.max(dp(2f), w * 0.0025f)
        val segW = (w - gap * (n + 1)) / n
        val now = System.currentTimeMillis()
        val v = glide.value(now)
        val pos = if (v.isNaN()) 0.0 else ((v - from) / (to - from)).coerceIn(0.0, 1.0) * n
        val off = flash && (now / 140) % 2 == 1L
        for (i in 0 until n) {
            val x = gap + i * (segW + gap)
            val col = if (i < n * 0.6) Theme.OK else if (i < n * 0.83) Theme.WARN else Theme.BAD
            val fill = (pos - i).coerceIn(0.0, 1.0).toFloat()
            // unlit segment
            r.set(x, h * 0.18f, x + segW, h * 0.82f)
            p.color = Color.argb(30, 255, 255, 255)
            c.drawRoundRect(r, h * 0.2f, h * 0.2f, p)
            if (fill > 0f && !off) {
                // glow then the lit part (partly lit for the segment the rpm is in)
                r.set(x - gap * 0.6f, 0f, x + segW * fill + gap * 0.6f, h)
                p.color = Theme.withAlpha(col, (70 * fill).toInt())
                c.drawRoundRect(r, h * 0.3f, h * 0.3f, p)
                r.set(x, h * 0.18f, x + segW * fill, h * 0.82f)
                p.color = col
                c.drawRoundRect(r, h * 0.2f, h * 0.2f, p)
            }
        }
        if (glide.moving(now) || flash) postInvalidateOnAnimation()
    }

    companion object { const val SEGMENTS = 30 }
}

/** Mix two colours: [f] = 0 gives [a], 1 gives [b]. */
fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())

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

/**
 * A row of real buttons with a title on the left. The option the ECU reports as active is lit in the accent colour
 * with a glow and an ON dot. A pressed option shows an accent outline until the ECU confirms it.
 */
class SegmentView(ctx: Context, private val title: String, private val options: List<String>,
                  private val onPick: (Int) -> Unit) : View(ctx) {
    /** Option the ECU reports as active, -1 when unknown. */
    var selected = -1
        set(v) { if (v != field) { field = v; if (v == pending) pending = -1; invalidate() } }
    private var pending = -1
    private var pendingAt = 0L
    private var down = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private fun titleW(): Float = if (title.isEmpty()) 0f else Math.min(width * 0.2f, dp(150f))

    private fun indexAt(x: Float): Int {
        val tw = titleW()
        if (x < tw) return -1
        val i = ((x - tw) / ((width - tw) / options.size)).toInt()
        return if (i in options.indices) i else -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { down = indexAt(e.x); invalidate() }
            MotionEvent.ACTION_UP -> {
                val i = indexAt(e.x)
                if (i >= 0 && i == down) {
                    pending = i; pendingAt = System.currentTimeMillis()
                    onPick(i)
                }
                down = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { down = -1; invalidate() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val accent = Theme.ACCENT
        val now = System.currentTimeMillis()
        if (pending >= 0 && now - pendingAt > 4000) pending = -1
        val tw = titleW()
        if (tw > 0) {
            p.typeface = Theme.TECH
            p.color = Theme.DIM
            p.textAlign = Paint.Align.LEFT
            var ts = Math.min(h * 0.30f, dp(17f))
            p.textSize = ts
            while (p.measureText(title.uppercase()) > tw - dp(8f) && ts > dp(8f)) { ts -= 1f; p.textSize = ts }
            c.drawText(title.uppercase(), dp(6f), h / 2 + ts * 0.36f, p)
        }
        val n = options.size
        val gap = dp(6f)
        val bw = (w - tw - gap * (n - 1) - dp(2f)) / n
        val rad = Math.min(h * 0.22f, dp(12f))
        for (i in 0 until n) {
            val x = tw + i * (bw + gap)
            val top = h * 0.10f; val bot = h * 0.90f
            val on = i == selected
            if (on) {
                // glow behind the lit button
                r.set(x - dp(3f), top - dp(3f), x + bw + dp(3f), bot + dp(3f))
                p.shader = null
                p.color = Theme.withAlpha(accent, 60)
                c.drawRoundRect(r, rad + dp(3f), rad + dp(3f), p)
                r.set(x, top, x + bw, bot)
                p.shader = LinearGradient(0f, top, 0f, bot, mix(accent, Color.WHITE, 0.18f), mix(accent, Color.BLACK, 0.25f), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, rad, rad, p)
                p.shader = null
            } else {
                r.set(x, top + dp(2f), x + bw, bot + dp(2f))
                p.color = Color.argb(140, 0, 0, 0)                                   // drop shadow
                c.drawRoundRect(r, rad, rad, p)
                r.set(x, top, x + bw, bot)
                val pressed = i == down
                p.shader = LinearGradient(0f, top, 0f, bot,
                    if (pressed) Color.rgb(30, 34, 46) else Color.rgb(52, 58, 74),
                    if (pressed) Color.rgb(20, 23, 32) else Color.rgb(26, 30, 41), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, rad, rad, p)
                p.shader = null
                // top highlight line, like a raised key
                p.color = Color.argb(45, 255, 255, 255)
                c.drawRect(x + rad, top + dp(1f), x + bw - rad, top + dp(2f), p)
            }
            // outline: accent while waiting for the ECU, else thin light border
            p.style = Paint.Style.STROKE
            p.strokeWidth = if (i == pending && !on) dp(2f) else 1f
            p.color = when {
                i == pending && !on -> accent
                on -> mix(accent, Color.WHITE, 0.4f)
                else -> Color.argb(40, 255, 255, 255)
            }
            r.set(x, top, x + bw, bot)
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.FILL
            // label
            p.typeface = Theme.TECH
            p.textAlign = Paint.Align.CENTER
            var ts = Math.min(h * 0.32f, dp(19f))
            p.textSize = ts
            while (p.measureText(options[i]) > bw - dp(10f) && ts > dp(8f)) { ts -= 1f; p.textSize = ts }
            p.color = if (on) onColor(accent) else Theme.TEXT
            c.drawText(options[i], x + bw / 2, (top + bot) / 2 + ts * 0.36f, p)
            if (on) {
                // ON dot
                p.color = onColor(accent)
                c.drawCircle(x + bw - dp(9f), top + dp(9f), dp(3.5f), p)
            }
        }
        if (pending >= 0) postInvalidateDelayed(500)
    }
}

/** Text colour on an accent background: dark on bright accents, white on deep ones. */
fun onColor(accent: Int): Int {
    val lum = (0.299 * Color.red(accent) + 0.587 * Color.green(accent) + 0.114 * Color.blue(accent)) / 255
    return if (lum > 0.6) Theme.BG else Color.WHITE
}

/**
 * Bottom tab bar. The selected tab is wider (about 2.4 times an inactive one), shows its full name and sits on a
 * glowing accent background. Inactive tabs are narrow with short names. Widths animate when the tab changes.
 */
class TabBarView(ctx: Context, private val titles: List<String>, private val shorts: List<String>,
                 private val textSp: Float, private val onPick: (Int) -> Unit) : View(ctx) {
    var selected = 0
        set(v) { field = v; invalidate() }
    private val weights = FloatArray(titles.size) { 1f }
    private var lastFrame = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var blurFor = 0f

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // the blur glow needs a software layer

    private fun xs(w: Float): FloatArray {
        val pad = dp(4f)
        val sum = weights.sum()
        val out = FloatArray(titles.size + 1)
        out[0] = pad
        for (i in titles.indices) out[i + 1] = out[i] + (w - 2 * pad) * weights[i] / sum
        return out
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val x = xs(width.toFloat())
            for (i in titles.indices) if (e.x >= x[i] && e.x < x[i + 1]) { if (i != selected) onPick(i); break }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 1000L else now - lastFrame
        lastFrame = now
        var moving = false
        for (i in titles.indices) {
            val target = if (i == selected) SELECTED_WEIGHT else 1f
            val k = (1 - Math.exp(-dt / 90.0)).toFloat()
            weights[i] += (target - weights[i]) * k
            if (Math.abs(target - weights[i]) > 0.01f) moving = true else weights[i] = target
        }
        // bar background
        r.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        p.shader = null
        p.color = Theme.withAlpha(Theme.BAR, 245)
        c.drawRoundRect(r, dp(14f), dp(14f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = Theme.BORDER
        c.drawRoundRect(r, dp(14f), dp(14f), p)
        p.style = Paint.Style.FILL
        val x = xs(w)
        val accent = Theme.ACCENT
        val top = dp(4f); val bot = h - dp(4f)
        // selected: blurred glow, gradient pill, accent border
        val sx0 = x[selected] + dp(2f); val sx1 = x[selected + 1] - dp(2f)
        val blur = dp(12f)
        if (blurFor != blur) { glow.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL); blurFor = blur }
        glow.color = Theme.withAlpha(accent, 150)
        r.set(sx0 + dp(4f), top + dp(4f), sx1 - dp(4f), bot - dp(2f))
        c.drawRoundRect(r, dp(12f), dp(12f), glow)
        r.set(sx0, top, sx1, bot)
        p.shader = LinearGradient(0f, top, 0f, bot, Theme.withAlpha(mix(accent, Color.WHITE, 0.15f), 120),
            Theme.withAlpha(accent, 55), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, dp(11f), dp(11f), p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.5f); p.color = Theme.withAlpha(accent, 220)
        c.drawRoundRect(r, dp(11f), dp(11f), p)
        p.style = Paint.Style.FILL
        // labels
        p.typeface = Theme.TECH
        p.textAlign = Paint.Align.CENTER
        for (i in titles.indices) {
            val on = i == selected
            val label = if (on || weights[i] > 1.6f) titles[i] else shorts[i]
            var ts = dp(textSp) * (if (on) 1.08f else 0.92f)
            p.textSize = ts
            val room = x[i + 1] - x[i] - dp(10f)
            while (p.measureText(label) > room && ts > dp(8f)) { ts -= 1f; p.textSize = ts }
            p.color = if (on) Theme.TEXT else Theme.DIM
            c.drawText(label, (x[i] + x[i + 1]) / 2, h / 2 + ts * 0.36f, p)
        }
        if (moving) postInvalidateOnAnimation()
    }

    companion object { const val SELECTED_WEIGHT = 2.4f }
}

/**
 * Grid of small live line charts, one per value, for the Graph screen. Each shows the name, the latest value,
 * a line with a soft fill and the min/max of the time window. Only the cells on screen are drawn.
 */
class ChartGridView(ctx: Context) : View(ctx) {
    var keys: List<String> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var windowMs = 120_000L
    var name: (String) -> String = { it }
    var unit: (String) -> String = { "" }
    var format: (String, Double) -> String = { _, v -> String.format("%.1f", v) }
    var source: ((String, Long) -> Pair<LongArray, FloatArray>)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val fill = Path()
    private val r = RectF()
    private val clip = Rect()

    fun columns(w: Int): Int = Math.max(2, (w / dp(210f)).toInt())
    private fun cellH(): Float = dp(112f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cols = columns(w)
        val rows = (keys.size + cols - 1) / cols
        setMeasuredDimension(w, Math.max(1, (rows * cellH()).toInt()))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cols = columns(width)
        val cw = w / cols
        val ch = cellH()
        val now = System.currentTimeMillis()
        val since = now - windowMs
        if (!c.getClipBounds(clip)) clip.set(0, 0, width, height)
        val accent = Theme.ACCENT
        line.strokeWidth = Math.max(2f, dp(1.6f))
        for ((idx, k) in keys.withIndex()) {
            val row = idx / cols; val col = idx % cols
            val x0 = col * cw + dp(3f); val y0 = row * ch + dp(3f)
            val x1 = x0 + cw - dp(6f); val y1 = y0 + ch - dp(6f)
            if (y1 < clip.top || y0 > clip.bottom) continue
            r.set(x0, y0, x1, y1)
            p.shader = LinearGradient(x0, y0, x1, y1, Theme.PANEL_TOP, Theme.PANEL_BOTTOM, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, dp(10f), dp(10f), p)
            p.shader = null
            val (ts, vs) = source?.invoke(k, since) ?: (LongArray(0) to FloatArray(0))
            val pad = dp(8f)
            // title and latest value
            p.typeface = Theme.TECH
            p.textAlign = Paint.Align.LEFT
            p.color = Theme.DIM
            var tsz = dp(12f)
            p.textSize = tsz
            val nm = name(k).uppercase()
            val roomL = (x1 - x0) * 0.58f
            var label = nm
            while (p.measureText(label) > roomL && label.length > 4) label = label.substring(0, label.length - 2) + "."
            c.drawText(label, x0 + pad, y0 + pad + tsz * 0.8f, p)
            p.textAlign = Paint.Align.RIGHT
            p.typeface = Theme.MONO
            p.color = if (vs.isEmpty()) Theme.DIM2 else accent
            p.textSize = dp(14f)
            val last = if (vs.isEmpty()) "--" else format(k, vs[vs.size - 1].toDouble()) + " " + unit(k)
            c.drawText(last.trim(), x1 - pad, y0 + pad + dp(12f), p)
            // plot area
            val px0 = x0 + pad; val px1 = x1 - pad
            val py0 = y0 + pad + dp(20f); val py1 = y1 - pad - dp(12f)
            if (vs.size >= 2) {
                var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                for (v in vs) { if (v < lo) lo = v; if (v > hi) hi = v }
                if (hi - lo < 1e-6f) { hi += 0.5f; lo -= 0.5f }
                path.reset(); fill.reset()
                val step = Math.max(1, vs.size / Math.max(1, (px1 - px0).toInt()))
                var first = true
                var lastX = px0
                var i = 0
                while (i < vs.size) {
                    val xx = px0 + (px1 - px0) * ((ts[i] - since).toFloat() / windowMs)
                    val yy = py1 - (py1 - py0) * ((vs[i] - lo) / (hi - lo))
                    if (first) { path.moveTo(xx, yy); fill.moveTo(xx, py1); fill.lineTo(xx, yy); first = false }
                    else { path.lineTo(xx, yy); fill.lineTo(xx, yy) }
                    lastX = xx
                    i += step
                }
                fill.lineTo(lastX, py1); fill.close()
                p.shader = LinearGradient(0f, py0, 0f, py1, Theme.withAlpha(accent, 70), Theme.withAlpha(accent, 0), Shader.TileMode.CLAMP)
                c.drawPath(fill, p)
                p.shader = null
                line.color = accent
                c.drawPath(path, line)
                // min / max
                p.typeface = Theme.MONO
                p.textSize = dp(10f)
                p.color = Theme.DIM2
                p.textAlign = Paint.Align.LEFT
                c.drawText("max " + format(k, hi.toDouble()), px0, y1 - pad * 0.4f, p)
                p.textAlign = Paint.Align.RIGHT
                c.drawText("min " + format(k, lo.toDouble()), px1, y1 - pad * 0.4f, p)
            } else {
                p.typeface = Theme.TECH
                p.textSize = dp(11f)
                p.color = Theme.DIM2
                p.textAlign = Paint.Align.CENTER
                c.drawText("waiting for data", (px0 + px1) / 2, (py0 + py1) / 2, p)
            }
        }
    }
}
