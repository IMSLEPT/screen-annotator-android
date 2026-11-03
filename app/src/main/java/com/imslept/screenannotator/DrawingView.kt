package com.imslept.screenannotator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DiscretePathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Superficie di disegno a schermo intero, mostrata come overlay sopra le altre app.
 * Gestisce tratti liberi, forme e testo, con dissolvenza automatica (modalita' temporanea)
 * oppure permanenza (modalita' permanente).
 */
class DrawingView(context: Context) : View(context) {

    class Pt(val x: Float, val y: Float, var t: Long)

    class Stroke(
        val tool: Tool,
        val shape: ShapeType,
        val color: Int,
        /** Spessore in px (per il testo: dimensione in px) */
        val widthPx: Float,
        /** Opacita' base 0..255 */
        val alpha: Int,
        val temporary: Boolean,
    ) {
        val points = ArrayList<Pt>()
        val bounds = RectF()
        var endTime = 0L
        var text: String? = null
        var path: Path? = null
        var pathCount = -1
        val freehand: Boolean
            get() = tool == Tool.PEN || tool == Tool.PENCIL || tool == Tool.HIGHLIGHTER
    }

    private val density = resources.displayMetrics.density
    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null

    /**
     * Cronologia delle operazioni per Annulla/Ripeti: aggiunta di un tratto oppure rimozione
     * (gomma o cancella tutto), cosi' anche gomma e cestino si possono annullare.
     */
    private sealed class Op {
        class Add(val s: Stroke) : Op()
        /** Tratti rimossi, nell'ordine di rimozione, con la posizione che avevano. */
        class Remove(val items: ArrayList<Pair<Int, Stroke>>) : Op()
    }

    private val history = ArrayList<Op>()
    private val redoOps = ArrayList<Op>()
    private var eraseOp: Op.Remove? = null

    private var touching = false
    private var touchX = 0f
    private var touchY = 0f

    /** true = le nuove annotazioni svaniscono da sole */
    var temporary: Boolean = Prefs.temporaryDefault

    /** Chiamato quando l'utente tocca con lo strumento Testo (coordinate dello schermo) */
    var onTextRequest: ((Float, Float) -> Unit)? = null

    /** Chiamato quando il dito si appoggia per iniziare un gesto (serve a richiudere la barra) */
    var onStrokeStart: (() -> Unit)? = null

    /** true se Android sta gia' mostrando i tocchi di sistema: evito il doppio cerchio */
    private var systemTouches = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val pencilFx = DiscretePathEffect(6f, 1.0f)
    private val tmpRect = RectF()

    // ------------------------------------------------------------------ API pubblica

    /** I tratti temporanei rimessi sullo schermo ripartono con la dissolvenza da adesso. */
    private fun restart(s: Stroke) {
        if (!s.temporary) return
        val now = SystemClock.uptimeMillis()
        val delta = now - (s.points.lastOrNull()?.t ?: now)
        for (p in s.points) p.t += delta
        s.endTime = now
    }

    private fun push(op: Op) {
        history.add(op)
        redoOps.clear()
        if (history.size > 300) history.removeAt(0)
    }

    fun undo() {
        while (history.isNotEmpty()) {
            when (val op = history.removeAt(history.size - 1)) {
                is Op.Add -> {
                    // un tratto temporaneo gia' svanito non conta: passo al precedente
                    if (strokes.remove(op.s)) {
                        redoOps.add(op)
                        invalidate()
                        return
                    }
                }
                is Op.Remove -> {
                    for ((idx, st) in op.items.asReversed()) {
                        restart(st)
                        strokes.add(idx.coerceIn(0, strokes.size), st)
                    }
                    redoOps.add(op)
                    invalidate()
                    return
                }
            }
        }
    }

    fun redo() {
        if (redoOps.isEmpty()) return
        val op = redoOps.removeAt(redoOps.size - 1)
        when (op) {
            is Op.Add -> {
                restart(op.s)
                strokes.add(op.s)
            }
            is Op.Remove -> for ((_, st) in op.items) strokes.remove(st)
        }
        history.add(op)
        invalidate()
    }

    fun clearAll() {
        current = null
        if (strokes.isEmpty()) return
        val items = ArrayList<Pair<Int, Stroke>>()
        for (i in strokes.indices.reversed()) items.add(i to strokes[i])
        strokes.clear()
        push(Op.Remove(items))
        invalidate()
    }

    fun addText(x: Float, y: Float, text: String) {
        if (text.isBlank()) return
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, Prefs.width(Tool.TEXT).toFloat(), resources.displayMetrics
        )
        val s = Stroke(
            Tool.TEXT, Prefs.shapeType, Prefs.color(Tool.TEXT), sizePx,
            Prefs.opacity(Tool.TEXT) * 255 / 100, temporary
        )
        val now = SystemClock.uptimeMillis()
        s.text = text
        s.points.add(Pt(x, y, now))
        s.endTime = now
        val tp = textPaint(s, 1f)
        val lines = text.split("\n")
        var w = 0f
        for (l in lines) w = max(w, tp.measureText(l))
        s.bounds.set(x, y - sizePx, x + w, y - sizePx + tp.fontSpacing * lines.size)
        strokes.add(s)
        push(Op.Add(s))
        invalidate()
    }

    // ------------------------------------------------------------------ Tocco

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                touchX = e.x
                touchY = e.y
                systemTouches = try {
                    android.provider.Settings.System.getInt(context.contentResolver, "show_touches", 0) == 1
                } catch (_: Exception) { false }
                onStrokeStart?.invoke()
                when (val tool = Prefs.lastTool) {
                    Tool.ERASER -> {
                        eraseOp = Op.Remove(ArrayList())
                        eraseAt(e.x, e.y)
                    }
                    Tool.TEXT -> onTextRequest?.invoke(e.x, e.y)
                    else -> startStroke(tool, e.x, e.y)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = e.x
                touchY = e.y
                val cur = current
                if (cur != null) extend(cur, e)
                else if (Prefs.lastTool == Tool.ERASER) eraseAt(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                current?.let { finish(it) }
                current = null
                eraseOp?.let { if (it.items.isNotEmpty()) push(it) }
                eraseOp = null
                invalidate()
            }
        }
        return true
    }

    private fun startStroke(tool: Tool, x: Float, y: Float) {
        val s = Stroke(
            tool, Prefs.shapeType, Prefs.color(tool), Prefs.width(tool) * density,
            Prefs.opacity(tool) * 255 / 100, temporary
        )
        s.points.add(Pt(x, y, SystemClock.uptimeMillis()))
        s.bounds.set(x, y, x, y)
        strokes.add(s)
        push(Op.Add(s))
        current = s
    }

    private fun extend(s: Stroke, e: MotionEvent) {
        if (s.tool == Tool.SHAPE) {
            val p = Pt(e.x, e.y, e.eventTime)
            if (s.points.size > 1) s.points[1] = p else s.points.add(p)
            val a = s.points[0]
            s.bounds.set(min(a.x, p.x), min(a.y, p.y), max(a.x, p.x), max(a.y, p.y))
            return
        }
        for (i in 0 until e.historySize) {
            addPoint(s, e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
        }
        addPoint(s, e.x, e.y, e.eventTime)
    }

    private fun addPoint(s: Stroke, x: Float, y: Float, t: Long) {
        val last = s.points[s.points.size - 1]
        if (hypot(x - last.x, y - last.y) < 1.5f) return
        s.points.add(Pt(x, y, t))
        s.bounds.union(x, y)
    }

    private fun finish(s: Stroke) {
        s.endTime = SystemClock.uptimeMillis()
        if (s.tool == Tool.SHAPE) {
            val a = s.points[0]
            val b = s.points.getOrNull(1)
            if (b == null || hypot(b.x - a.x, b.y - a.y) < 12f) {
                // forma troppo piccola (un tocco): la scarto anche dalla cronologia
                strokes.remove(s)
                val last = history.lastOrNull()
                if (last is Op.Add && last.s === s) history.removeAt(history.size - 1)
            }
        }
    }

    // ------------------------------------------------------------------ Gomma

    private fun eraseAt(x: Float, y: Float) {
        val r = Prefs.width(Tool.ERASER) * density
        var i = 0
        while (i < strokes.size) {
            val st = strokes[i]
            if (hits(st, x, y, r)) {
                strokes.removeAt(i)
                eraseOp?.items?.add(i to st)
            } else {
                i++
            }
        }
    }

    private fun hits(s: Stroke, x: Float, y: Float, r: Float): Boolean {
        if (s.tool == Tool.TEXT) {
            tmpRect.set(s.bounds)
            tmpRect.inset(-r, -r)
            return tmpRect.contains(x, y)
        }
        val reach = r + s.widthPx / 2f
        for (p in samples(s)) {
            if (hypot(p.x - x, p.y - y) <= reach) return true
        }
        return false
    }

    private fun samples(s: Stroke): List<PointF> {
        val out = ArrayList<PointF>()
        if (s.tool != Tool.SHAPE) {
            for (i in s.points.indices) {
                val p = s.points[i]
                if (i > 0) {
                    val q = s.points[i - 1]
                    lineSamples(q.x, q.y, p.x, p.y, out)
                }
                out.add(PointF(p.x, p.y))
            }
            return out
        }
        val a = s.points[0]
        val b = s.points.getOrNull(1) ?: return listOf(PointF(a.x, a.y))
        val b0 = s.bounds
        when (s.shape) {
            ShapeType.LINE, ShapeType.ARROW -> lineSamples(a.x, a.y, b.x, b.y, out)
            ShapeType.RECT -> {
                lineSamples(b0.left, b0.top, b0.right, b0.top, out)
                lineSamples(b0.right, b0.top, b0.right, b0.bottom, out)
                lineSamples(b0.right, b0.bottom, b0.left, b0.bottom, out)
                lineSamples(b0.left, b0.bottom, b0.left, b0.top, out)
            }
            ShapeType.CIRCLE -> {
                val cx = b0.centerX()
                val cy = b0.centerY()
                val rx = b0.width() / 2f
                val ry = b0.height() / 2f
                for (i in 0 until 96) {
                    val ang = i * (2.0 * Math.PI / 96.0)
                    out.add(PointF(cx + rx * cos(ang).toFloat(), cy + ry * sin(ang).toFloat()))
                }
            }
        }
        return out
    }

    private fun lineSamples(ax: Float, ay: Float, bx: Float, by: Float, out: MutableList<PointF>) {
        val d = hypot(bx - ax, by - ay)
        val n = (d / 12f).toInt()
        for (i in 1..n) {
            val f = i / (n + 1f)
            out.add(PointF(ax + (bx - ax) * f, ay + (by - ay) * f))
        }
    }

    // ------------------------------------------------------------------ Disegno

    private fun fadeFactor(age: Long, hold: Int, fade: Int): Float {
        if (age <= hold) return 1f
        val s = ((age - hold).toFloat() / fade).coerceIn(0f, 1f)
        return 1f - s * s * (3f - 2f * s) // smoothstep: dissolvenza morbida, senza scatti
    }

    private fun isTrail(s: Stroke): Boolean = s.temporary && s.freehand && Prefs.fadeMode == 1

    private fun isExpired(s: Stroke, now: Long, hold: Int, fade: Int): Boolean {
        if (s.endTime == 0L) return false
        val ref = if (isTrail(s)) s.points[s.points.size - 1].t else s.endTime
        return now - ref >= hold + fade
    }

    /** Fattore di visibilita' dell'intero tratto (modalita' non-scia) */
    private fun wholeFactor(s: Stroke, now: Long, hold: Int, fade: Int): Float {
        if (!s.temporary) return 1f
        if (s.endTime == 0L) return 1f
        return fadeFactor(now - s.endTime, hold, fade)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val hold = Prefs.holdMs
        val fade = max(1, Prefs.fadeMs)
        var animate = touching
        val it = strokes.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.temporary) {
                if (isExpired(s, now, hold, fade)) {
                    it.remove()
                    continue
                }
                animate = true
            }
            when {
                s.tool == Tool.TEXT -> drawText(canvas, s, wholeFactor(s, now, hold, fade))
                s.tool == Tool.SHAPE -> drawShape(canvas, s, wholeFactor(s, now, hold, fade))
                isTrail(s) -> drawTrail(canvas, s, now, hold, fade)
                else -> drawFreehand(canvas, s, wholeFactor(s, now, hold, fade))
            }
        }
        if (touching && Prefs.lastTool == Tool.ERASER) drawEraser(canvas)
        else if (touching && Prefs.showTouches && !systemTouches) drawTouch(canvas)
        if (animate) postInvalidateOnAnimation()
    }

    private fun strokePaint(color: Int, alpha: Int, width: Float, cap: Paint.Cap): Paint {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = cap
        paint.strokeWidth = width
        paint.color = color
        paint.alpha = alpha.coerceIn(0, 255)
        return paint
    }

    private fun buildPath(s: Stroke): Path {
        val cached = s.path
        if (cached != null && s.pathCount == s.points.size) return cached
        val path = cached ?: Path()
        path.reset()
        val pts = s.points
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 1) {
            path.lineTo(pts[0].x + 0.01f, pts[0].y)
        } else {
            for (i in 1 until pts.size) {
                val p0 = pts[i - 1]
                val p1 = pts[i]
                path.quadTo(p0.x, p0.y, (p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
            }
            val last = pts[pts.size - 1]
            path.lineTo(last.x, last.y)
        }
        s.path = path
        s.pathCount = pts.size
        return path
    }

    private fun drawFreehand(canvas: Canvas, s: Stroke, factor: Float) {
        val a = (s.alpha * factor).toInt()
        if (a <= 0) return
        val cap = if (s.tool == Tool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        val widthPx = if (s.tool == Tool.PENCIL) s.widthPx * 0.9f else s.widthPx
        val p = strokePaint(s.color, a, widthPx, cap)
        if (s.tool == Tool.PENCIL) p.pathEffect = pencilFx
        canvas.drawPath(buildPath(s), p)
    }

    /** Dissolvenza punto per punto: il tratto si "consuma" dalla parte piu' vecchia, come una scia. */
    private fun drawTrail(canvas: Canvas, s: Stroke, now: Long, hold: Int, fade: Int) {
        val pad = s.widthPx + 4f
        val save = canvas.saveLayerAlpha(
            s.bounds.left - pad, s.bounds.top - pad, s.bounds.right + pad, s.bounds.bottom + pad, s.alpha
        )
        segPaint.style = Paint.Style.STROKE
        segPaint.strokeCap = Paint.Cap.ROUND
        segPaint.strokeJoin = Paint.Join.ROUND
        segPaint.strokeWidth = s.widthPx
        segPaint.color = s.color or Color.BLACK // colore pieno: l'opacita' del tratto e' nel layer
        val pts = s.points
        if (pts.size == 1) {
            val f = fadeFactor(now - pts[0].t, hold, fade)
            segPaint.alpha = (255 * f).toInt()
            canvas.drawPoint(pts[0].x, pts[0].y, segPaint)
        } else {
            for (i in 1 until pts.size) {
                val f = fadeFactor(now - pts[i].t, hold, fade)
                if (f <= 0f) continue
                segPaint.alpha = (255 * f).toInt()
                canvas.drawLine(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, segPaint)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawShape(canvas: Canvas, s: Stroke, factor: Float) {
        val a = (s.alpha * factor).toInt()
        if (a <= 0 || s.points.size < 2) return
        val p = strokePaint(s.color, a, s.widthPx, Paint.Cap.ROUND)
        val p0 = s.points[0]
        val p1 = s.points[s.points.size - 1]
        when (s.shape) {
            ShapeType.LINE -> canvas.drawLine(p0.x, p0.y, p1.x, p1.y, p)
            ShapeType.RECT -> canvas.drawRoundRect(s.bounds, 8f * density, 8f * density, p)
            ShapeType.CIRCLE -> canvas.drawOval(s.bounds, p)
            ShapeType.ARROW -> {
                canvas.drawLine(p0.x, p0.y, p1.x, p1.y, p)
                val ang = atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble())
                val len = max(s.widthPx * 4f, 22f * density)
                val spread = 0.5
                canvas.drawLine(
                    p1.x, p1.y,
                    p1.x - len * cos(ang - spread).toFloat(), p1.y - len * sin(ang - spread).toFloat(), p
                )
                canvas.drawLine(
                    p1.x, p1.y,
                    p1.x - len * cos(ang + spread).toFloat(), p1.y - len * sin(ang + spread).toFloat(), p
                )
            }
        }
    }

    private fun textPaint(s: Stroke, factor: Float): Paint {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.textSize = s.widthPx
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = s.color
        paint.alpha = (s.alpha * factor).toInt().coerceIn(0, 255)
        paint.setShadowLayer(4f * density, 0f, 2f * density, Color.argb((110 * factor).toInt(), 0, 0, 0))
        return paint
    }

    private fun drawText(canvas: Canvas, s: Stroke, factor: Float) {
        if (factor <= 0f) return
        val p = textPaint(s, factor)
        val pt = s.points[0]
        val lines = (s.text ?: return).split("\n")
        for (i in lines.indices) {
            canvas.drawText(lines[i], pt.x, pt.y + i * p.fontSpacing, p)
        }
    }

    /** Anteprima della gomma: un anello grande quanto l'area che cancella. */
    private fun drawEraser(canvas: Canvas) {
        val r = Prefs.width(Tool.ERASER) * density
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = 50
        canvas.drawCircle(touchX, touchY, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = Color.BLACK
        paint.alpha = 120
        canvas.drawCircle(touchX, touchY, r, paint)
        paint.strokeWidth = 1.5f * density
        paint.color = Color.WHITE
        paint.alpha = 230
        canvas.drawCircle(touchX, touchY, r, paint)
    }

    private fun drawTouch(canvas: Canvas) {
        val r = 26f * density
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = 70
        canvas.drawCircle(touchX, touchY, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.alpha = 170
        canvas.drawCircle(touchX, touchY, r, paint)
    }
}
