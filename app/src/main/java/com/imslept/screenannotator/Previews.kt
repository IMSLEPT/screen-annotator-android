package com.imslept.screenannotator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Anteprima animata della scia a scomparsa: disegna un tratto, lo tiene visibile e lo fa svanire
 * con i tempi e il tipo di dissolvenza impostati, cosi' si vede subito l'effetto di ogni cursore.
 */
class TrailPreview(context: Context) : View(context) {
    private val ui = Ui(context)
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * ui.density
        typeface = Typeface.DEFAULT_BOLD
        color = ui.c(R.color.ink_muted)
    }
    private val n = 90
    private val xs = FloatArray(n + 1)
    private val ys = FloatArray(n + 1)
    private var t0 = 0L

    init {
        background = ui.bg(ui.c(R.color.card_variant), 20)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        t0 = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun fade(age: Long, hold: Int, fade: Int): Float {
        if (age <= hold) return 1f
        val s = ((age - hold).toFloat() / fade).coerceIn(0f, 1f)
        return 1f - s * s * (3f - 2f * s)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val hold = Prefs.holdMs
        val fadeMs = max(1, Prefs.fadeMs)
        val trail = Prefs.fadeMode == 1
        val drawMs = 1100L
        val cycle = drawMs + hold + fadeMs + 700L
        val t = (SystemClock.uptimeMillis() - t0) % cycle

        val padX = 30f * ui.density
        for (i in 0..n) {
            val f = i.toFloat() / n
            xs[i] = padX + (w - 2 * padX) * f
            ys[i] = h * 0.52f + sin(f * PI * 2.3 + 0.5).toFloat() * h * 0.22f
        }

        seg.strokeWidth = 7f * ui.density
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        for (i in 1..n) {
            val ti = drawMs * i / n
            if (ti > t) break
            val age = if (trail) t - ti else t - drawMs
            val f = fade(age, hold, fadeMs)
            if (f <= 0f) continue
            seg.color = ui.c(R.color.brand_orange)
            seg.alpha = (255 * f).toInt()
            canvas.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], seg)
        }
        canvas.restoreToCount(layer)
        canvas.drawText("ANTEPRIMA", 14f * ui.density, 22f * ui.density, tagPaint)
        postInvalidateOnAnimation()
    }
}

/** Anteprima di come verra' il tratto dello strumento scelto (colore, spessore, opacita'). */
class ToolPreview(context: Context) : View(context) {
    private val ui = Ui(context)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    var tool: Tool = Tool.PEN

    init {
        background = ui.bg(0xFFB0AEA5.toInt(), 20)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val t = tool
        val sw = (Prefs.width(t) * ui.density).coerceIn(2f, h * 0.7f)
        val alpha = Prefs.opacity(t).coerceIn(10, 100) * 255 / 100
        val color = (Prefs.color(t) and 0x00FFFFFF) or (alpha shl 24)
        when (t) {
            Tool.ERASER -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * ui.density
                p.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(w / 2f, h / 2f, sw / 2f, p)
            }
            Tool.TEXT -> {
                p.style = Paint.Style.FILL
                p.color = color
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = min(sw * 1.4f, h * 0.8f)
                canvas.drawText("Aa", w / 2f, h / 2f + p.textSize * 0.35f, p)
            }
            else -> {
                p.style = Paint.Style.STROKE
                p.strokeCap = if (t == Tool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
                p.strokeWidth = sw
                p.color = color
                path.reset()
                val pad = max(sw, 22f * ui.density)
                path.moveTo(pad, h * 0.64f)
                path.cubicTo(w * 0.35f, h * 0.1f, w * 0.62f, h * 1.0f, w - pad, h * 0.36f)
                canvas.drawPath(path, p)
            }
        }
    }
}

/** Mini telefono con 4 angoli: si tocca quello dove far comparire la barra. */
class CornerPicker(context: Context, private var corner: Int, private val onPick: (Int) -> Unit) : View(context) {
    private val ui = Ui(context)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ui.dp(108), ui.dp(204))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = ui.density
        // corpo del telefono
        r.set(3 * d, 3 * d, w - 3 * d, h - 3 * d)
        p.style = Paint.Style.FILL
        p.color = ui.c(R.color.card_variant)
        canvas.drawRoundRect(r, 22 * d, 22 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f * d
        p.color = ColorUtils.setAlphaComponent(ui.c(R.color.outline), 200)
        canvas.drawRoundRect(r, 22 * d, 22 * d, p)
        // le quattro barre
        val bw = 16f * d
        val bh = 58f * d
        val m = 14f * d
        for (i in 0..3) {
            val left = if (i % 2 == 0) m else w - m - bw
            val top = if (i < 2) m + 6 * d else h - m - bh - 6 * d
            r.set(left, top, left + bw, top + bh)
            val on = i == corner
            p.style = Paint.Style.FILL
            p.color = if (on) ui.c(R.color.brand_orange) else ColorUtils.setAlphaComponent(ui.c(R.color.outline), 110)
            canvas.drawRoundRect(r, 8 * d, 8 * d, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val c = (if (e.x > width / 2f) 1 else 0) + (if (e.y > height / 2f) 2 else 0)
            corner = c
            invalidate()
            onPick(c)
            return true
        }
        return true
    }
}
