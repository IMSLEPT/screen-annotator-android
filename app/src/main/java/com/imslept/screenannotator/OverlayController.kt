package com.imslept.screenannotator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Gestisce le due finestre sovrapposte alle altre app:
 *  1) la superficie di disegno a schermo intero (trasparente ai tocchi quando il disegno e' spento)
 *  2) la barra degli strumenti flottante: trascinabile, con timer di registrazione,
 *     riducibile a una bolla che si attacca al bordo dello schermo e si nasconde da sola.
 */
class OverlayController(
    private val ctx: Context,
    private val onStopRequested: () -> Unit,
    private val onDrawingChanged: (Boolean) -> Unit = {},
) {
    private companion object {
        const val ORANGE = 0xFFD97757.toInt()
        const val INK = 0xFF141413.toInt()
        const val PRIMARY = 0xFFFFFFFF.toInt()
        const val MUTED = 0xB3FFFFFF.toInt()
        const val REC_RED = 0xFFE5484D.toInt()

        /** Misure in dp a scala 100%. */
        const val BAR_W = 64f
        const val EST_BAR_H = 790f
        const val PANEL_W = 252f
        const val EST_PANEL_H = 480f
        const val BUBBLE = 58f

        /** Zona invisibile al bordo dello schermo che riapre la barra (dp reali, non scalati). */
        const val GHOST_W = 34f
        const val GHOST_H = 150f
        const val HIDE_DELAY = 4000L

        /**
         * Da Android 12 i tocchi attraverso un overlay "non toccabile" passano solo se l'opacita'
         * della finestra e' <= 0.8: per questo i disegni hanno al massimo l'80% di opacita'.
         */
        const val OVERLAY_ALPHA = 0.8f

        val PALETTE = intArrayOf(
            0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
            0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt(), 0xFF00ACC1.toInt(),
            0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt(), 0xFF212121.toInt(), 0xFF000000.toInt(),
        )
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private var u = density * Prefs.toolbarScale / 100f
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private val drawingView = DrawingView(ctx)
    private lateinit var drawParams: WindowManager.LayoutParams
    private lateinit var barParams: WindowManager.LayoutParams

    private lateinit var root: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var bubble: FrameLayout
    private lateinit var bubbleDisc: ImageView
    private lateinit var bubbleDot: View
    private lateinit var ghost: View

    private lateinit var headerDot: View
    private lateinit var timerText: TextView
    private val toolButtons = HashMap<Tool, ImageView>()
    private lateinit var drawToggle: LinearLayout
    private lateinit var drawToggleIcon: ImageView
    private lateinit var drawToggleLabel: TextView
    private lateinit var styleButton: FrameLayout
    private lateinit var styleDot: View

    private val swatches = ArrayList<View>()
    private lateinit var panelTitle: TextView
    private lateinit var preview: StrokePreview
    private lateinit var colorBlock: LinearLayout
    private lateinit var opacityBlock: LinearLayout
    private lateinit var shapeRow: LinearLayout
    private val shapeButtons = HashMap<ShapeType, ImageView>()
    private lateinit var widthSeek: SeekBar
    private lateinit var widthLabel: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityLabel: TextView
    private lateinit var tempButton: TextView
    private lateinit var permButton: TextView
    private lateinit var modeHint: TextView

    private val anims = ArrayList<Animator>()
    private var startElapsed = 0L

    private var drawing = false
    private var expanded = true
    private var panelOpen = false
    private var panelOnLeft = false
    private var dockedLeft = false
    private var peeking = false
    private var pendingText: PointF? = null
    private var textWindow: View? = null
    private var shown = false

    /** Barra invisibile nel video: si chiude da sola e al suo posto resta una zona trasparente al bordo. */
    private val ghostMode = Prefs.hideBar

    val isDrawing: Boolean get() = drawing

    private fun dp(v: Float): Int = (v * u).toInt()

    /** Misura in pixel senza la scala della barra (per elementi che non devono ridimensionarsi). */
    private fun px(v: Float): Int = (v * density).toInt()

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    // ------------------------------------------------------------------ Ciclo di vita

    fun show() {
        if (shown) return
        shown = true
        expanded = !ghostMode
        // Se la barra non entra nello schermo (scala alta) la riduco automaticamente
        val (_, sh) = screenSize()
        val maxH = sh * 0.9f
        if (EST_BAR_H * u > maxH) u = maxH / EST_BAR_H

        addDrawingWindow()
        buildToolbar()
        addToolbarWindow()
        drawingView.onTextRequest = { x, y -> requestText(x, y) }
        drawingView.onStrokeStart = {
            // appena inizi a disegnare la barra aperta si richiude presto
            if (ghostMode && expanded && !panelOpen) scheduleAutoHide(1200)
        }
        setDrawing(false)
        selectTool(Prefs.lastTool, enableDrawing = false)

        startElapsed = SystemClock.elapsedRealtime()
        handler.post(tick)
        pulse(headerDot)
        pulse(bubbleDot)

        root.alpha = 0f
        root.animate().alpha(1f).setDuration(240).start()
        scheduleAutoHide()
    }

    /** Apre/chiude la barra (usato dal pulsante «Barra» della notifica). */
    fun toggleBar() {
        if (!shown) return
        setExpanded(!expanded)
    }

    fun destroy() {
        if (!shown) return
        shown = false
        handler.removeCallbacksAndMessages(null)
        for (a in anims) a.cancel()
        anims.clear()
        closeText(null)
        try { root.animate().cancel() } catch (_: Exception) {}
        try { wm.removeView(root) } catch (_: Exception) {}
        try { wm.removeView(drawingView) } catch (_: Exception) {}
    }

    fun toggleDrawing() = setDrawing(!drawing)

    private val tick = object : Runnable {
        override fun run() {
            val s = ((SystemClock.elapsedRealtime() - startElapsed) / 1000).toInt()
            timerText.text = formatTime(s)
            handler.postDelayed(this, 1000)
        }
    }

    private fun formatTime(s: Int): String =
        if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)

    private fun pulse(v: View) {
        val a = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.25f).apply {
            duration = 750
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        anims.add(a)
    }

    // ------------------------------------------------------------------ Finestra di disegno

    private fun drawFlags(touchable: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun addDrawingWindow() {
        drawParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            drawFlags(touchable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = OVERLAY_ALPHA
            title = "ScreenAnnotator-draw"
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(drawingView, drawParams)
    }

    /** Disegno attivo = la superficie intercetta i tocchi; spento = i tocchi passano alle app sotto. */
    fun setDrawing(on: Boolean) {
        drawing = on
        drawParams.flags = drawFlags(touchable = on)
        try { wm.updateViewLayout(drawingView, drawParams) } catch (_: Exception) {}
        if (::drawToggle.isInitialized) {
            drawToggleIcon.setImageResource(if (on) R.drawable.ic_pencil else R.drawable.ic_touch)
            drawToggleLabel.text = if (on) "Disegno" else "Tocco"
            select(drawToggle, on)
            bubbleDisc.setImageResource(if (on) R.drawable.ic_pencil else R.drawable.ic_touch)
            bubbleDisc.imageTintList = ColorStateList.valueOf(if (on) INK else PRIMARY)
            bubbleDisc.background = circle(if (on) ORANGE else 0xF2262624.toInt())
        }
        onDrawingChanged(on)
    }

    // ------------------------------------------------------------------ Inserimento testo

    private fun roundPx(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = px(radiusDp).toFloat()
        }

    /**
     * Pannello per scrivere il testo, mostrato come finestra sovrapposta (nessuna activity):
     * cosi' l'app che stai registrando resta dov'e' e la tastiera si apre subito.
     */
    private fun requestText(x: Float, y: Float) {
        if (textWindow != null || !shown) return
        pendingText = PointF(x, y)

        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.text_dialog_hint)
            setHintTextColor(MUTED)
            setTextColor(PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = 1
            maxLines = 4
            highlightColor = 0x66D97757
            background = roundPx(0x1FFFFFFF, 14f)
            setPadding(px(14f), px(12f), px(14f), px(12f))
            textCursorDrawable = GradientDrawable().apply {
                setColor(ORANGE)
                setSize(px(2f), px(24f))
            }
        }

        fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(if (filled) INK else PRIMARY)
            background = roundPx(if (filled) ORANGE else 0x26FFFFFF, 14f)
            layoutParams = LinearLayout.LayoutParams(0, px(48f), 1f).apply {
                if (filled) marginStart = px(10f)
            }
            setOnClickListener { onClick() }
        }

        val titleView = TextView(ctx).apply {
            text = ctx.getString(R.string.text_dialog_title)
            setTextColor(PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val hintLine = TextView(ctx).apply {
            text = "Il testo comparirà dove hai toccato lo schermo."
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(ctx.getString(R.string.cancel), filled = false) { closeText(null) })
            addView(button("Aggiungi", filled = true) { closeText(input.text.toString()) })
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFA292927.toInt(), 0xFA151514.toInt())
            ).apply {
                cornerRadius = px(24f).toFloat()
                setStroke(px(1f).coerceAtLeast(1), 0x33FFFFFF)
            }
            setPadding(px(18f), px(16f), px(18f), px(18f))
            isClickable = true // i tocchi sulla scheda non devono arrivare allo sfondo
            addView(titleView)
            addView(hintLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2f) })
            addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
            addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        }

        val scrim = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) closeText(null)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(0x40000000)
            // un tocco fuori dalla scheda conferma il testo (o annulla se e' vuoto)
            setOnClickListener {
                val t = input.text.toString()
                closeText(if (t.isBlank()) null else t)
            }
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                setMargins(px(16f), px(72f), px(16f), 0)
            })
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "ScreenAnnotator-text"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        try {
            wm.addView(scrim, lp)
        } catch (_: Exception) {
            pendingText = null
            return
        }
        textWindow = scrim
        input.post {
            input.requestFocus()
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Chiude il pannello del testo; se `text` non e' vuoto lo aggiunge dove l'utente aveva toccato. */
    private fun closeText(text: String?) {
        val w = textWindow ?: return
        textWindow = null
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(w.windowToken, 0)
        } catch (_: Exception) {}
        try { wm.removeView(w) } catch (_: Exception) {}
        val p = pendingText
        pendingText = null
        if (shown && text != null && p != null && text.isNotBlank()) {
            drawingView.addText(p.x, p.y, text.trim())
        }
        scheduleAutoHide()
    }

    // ------------------------------------------------------------------ Elementi grafici

    private fun round(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * u
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1f).coerceAtLeast(1), 0x33FFFFFF)
        }

    /** Sfondo "vetro scuro" con bordo sottile, usato da barra e pannello. */
    private fun glass(radiusDp: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2292927.toInt(), 0xF2151514.toInt())).apply {
            cornerRadius = radiusDp * u
            setStroke(dp(1f).coerceAtLeast(1), 0x33FFFFFF)
        }

    private fun applyInk(v: View, ink: Int) {
        when (v) {
            is ImageView -> v.imageTintList = ColorStateList.valueOf(ink)
            is TextView -> v.setTextColor(ink)
            is ViewGroup -> for (i in 0 until v.childCount) applyInk(v.getChildAt(i), ink)
        }
    }

    /** Stato "selezionato": riquadro arancione con contenuto scuro. */
    private fun select(v: View, on: Boolean, radiusDp: Float = 16f) {
        v.background = if (on) round(ORANGE, radiusDp) else null
        applyInk(v, if (on) INK else PRIMARY)
    }

    private fun pop(v: View) {
        v.animate().cancel()
        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }

    private fun iconButton(
        @DrawableRes res: Int,
        color: Int = PRIMARY,
        wDp: Float = 52f,
        hDp: Float = 48f,
        onClick: () -> Unit,
    ): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(color)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dp(11f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(wDp), dp(hDp)).apply { setMargins(0, dp(1f), 0, dp(1f)) }
            setOnClickListener {
                pop(this)
                onClick()
            }
        }

    private fun divider(): View = View(ctx).apply {
        setBackgroundColor(0x26FFFFFF)
        layoutParams = LinearLayout.LayoutParams(dp(30f), dp(1f).coerceAtLeast(1)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(4f), 0, dp(4f))
        }
    }

    private fun label(text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * u)
        letterSpacing = 0.08f
        setPadding(0, dp(14f), 0, dp(4f))
    }

    private fun toolName(t: Tool): String = when (t) {
        Tool.PEN -> "Penna"
        Tool.PENCIL -> "Matita"
        Tool.HIGHLIGHTER -> "Evidenziatore"
        Tool.TEXT -> "Testo"
        Tool.SHAPE -> "Forme"
        Tool.ERASER -> "Gomma"
    }

    // ------------------------------------------------------------------ Barra degli strumenti

    private fun buildToolbar() {
        bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glass(30f)
            setPadding(dp(6f), dp(6f), dp(6f), dp(10f))
        }

        // --- testata: maniglia + timer (si trascina da qui)
        val grip = View(ctx).apply {
            background = round(0x66FFFFFF, 3f)
            layoutParams = LinearLayout.LayoutParams(dp(30f), dp(5f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(4f), 0, dp(8f))
            }
        }
        headerDot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(REC_RED)
            }
            layoutParams = LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { setMargins(0, 0, dp(5f), 0) }
        }
        timerText = TextView(ctx).apply {
            text = "00:00"
            maxLines = 1
            setTextColor(PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * u)
            typeface = Typeface.DEFAULT_BOLD
        }
        val timerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(headerDot)
            addView(timerText)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(6f))
            addView(grip)
            addView(timerRow)
        }
        attachDrag(header)
        bar.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(divider())

        // --- Tocco / Disegno
        drawToggleIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_touch)
            imageTintList = ColorStateList.valueOf(PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f))
        }
        drawToggleLabel = TextView(ctx).apply {
            text = "Tocco"
            setTextColor(PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 10f * u)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        drawToggle = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(52f), dp(58f)).apply { setMargins(0, dp(2f), 0, dp(2f)) }
            addView(drawToggleIcon)
            addView(drawToggleLabel)
            setOnClickListener {
                pop(this)
                toggleDrawing()
            }
        }
        bar.addView(drawToggle)
        bar.addView(divider())

        // --- strumenti
        val tools = listOf(
            Tool.PEN to R.drawable.ic_pen,
            Tool.PENCIL to R.drawable.ic_pencil,
            Tool.HIGHLIGHTER to R.drawable.ic_highlighter,
            Tool.TEXT to R.drawable.ic_text,
            Tool.SHAPE to R.drawable.ic_shape,
            Tool.ERASER to R.drawable.ic_eraser,
        )
        for ((tool, res) in tools) {
            val b = iconButton(res) { selectTool(tool, enableDrawing = true) }
            toolButtons[tool] = b
            bar.addView(b)
        }
        bar.addView(divider())

        // --- colore/stile (apre il pannello) + annulla/ripeti/cancella
        styleDot = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.CENTER)
        }
        styleButton = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52f), dp(48f)).apply { setMargins(0, dp(1f), 0, dp(1f)) }
            addView(styleDot)
            setOnClickListener {
                pop(this)
                togglePanel()
            }
        }
        bar.addView(styleButton)
        bar.addView(iconButton(R.drawable.ic_undo) { drawingView.undo() })
        bar.addView(iconButton(R.drawable.ic_redo) { drawingView.redo() })
        bar.addView(iconButton(R.drawable.ic_delete) { drawingView.clearAll() })
        bar.addView(divider())

        // --- nascondi + stop
        bar.addView(iconButton(R.drawable.ic_minimize) { setExpanded(false) })
        val stop = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_stop)
            imageTintList = ColorStateList.valueOf(PRIMARY)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dp(14f)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(REC_RED)
            }
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f)).apply { setMargins(0, dp(6f), 0, dp(2f)) }
            setOnClickListener {
                pop(this)
                onStopRequested()
            }
        }
        bar.addView(stop)

        buildPanel()

        // --- bolla (barra nascosta)
        bubbleDisc = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_touch)
            imageTintList = ColorStateList.valueOf(PRIMARY)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dp(15f)
            setPadding(p, p, p, p)
            background = circle(0xF2262624.toInt())
            layoutParams = FrameLayout.LayoutParams(dp(BUBBLE), dp(BUBBLE))
        }
        bubbleDot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(REC_RED)
                setStroke(dp(2f).coerceAtLeast(1), 0xFF151514.toInt())
            }
        }
        bubble = FrameLayout(ctx).apply {
            addView(bubbleDisc)
            addView(bubbleDot, FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(1f), dp(1f), 0)
            })
        }
        attachDrag(bubble, onClick = { setExpanded(true) }, onDrop = { snapBubble() })

        // --- zona invisibile (barra nascosta): trasparente, quindi non compare nel video
        ghost = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(px(GHOST_W), px(GHOST_H))
            contentDescription = "Apri la barra degli strumenti"
            setOnClickListener { setExpanded(true) }
        }

        root = object : LinearLayout(ctx) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) onBarTouched()
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        layoutRoot()
    }

    private fun buildPanel() {
        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(26f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(dp(PANEL_W), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        panelTitle = TextView(ctx).apply {
            setTextColor(PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f * u)
            typeface = Typeface.DEFAULT_BOLD
        }
        panel.addView(panelTitle)

        // Anteprima del tratto
        preview = StrokePreview(ctx)
        panel.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64f)).apply {
            topMargin = dp(10f)
        })

        // Colore
        colorBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        colorBlock.addView(label("Colore"))
        for (rowStart in intArrayOf(0, 6)) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in rowStart until rowStart + 6) {
                val color = PALETTE[i]
                val sw = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f)).apply {
                        setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
                    }
                    tag = color
                    setOnClickListener {
                        Prefs.setColor(Prefs.lastTool, color)
                        refreshSwatches()
                    }
                }
                swatches.add(sw)
                row.addView(sw)
            }
            colorBlock.addView(row)
        }
        panel.addView(colorBlock)

        // Forme
        shapeRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        shapeRow.addView(label("Forma"))
        val shapesLine = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val shapes = listOf(
            ShapeType.CIRCLE to R.drawable.ic_circle, ShapeType.ARROW to R.drawable.ic_arrow,
            ShapeType.LINE to R.drawable.ic_line, ShapeType.RECT to R.drawable.ic_rect,
        )
        for ((type, res) in shapes) {
            val b = iconButton(res, wDp = 48f, hDp = 44f) {
                Prefs.shapeType = type
                refreshShapes()
            }
            shapeButtons[type] = b
            shapesLine.addView(b)
        }
        shapeRow.addView(shapesLine)
        panel.addView(shapeRow)

        // Spessore
        widthLabel = label("Spessore")
        panel.addView(widthLabel)
        widthSeek = seekBar { progress ->
            val t = Prefs.lastTool
            Prefs.setWidth(t, progress + widthRange(t).first)
            updateLabels()
        }
        panel.addView(widthSeek)

        // Trasparenza
        opacityBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        opacityLabel = label("Opacità")
        opacityBlock.addView(opacityLabel)
        opacitySeek = seekBar { progress ->
            Prefs.setOpacity(Prefs.lastTool, progress + 10)
            updateLabels()
        }
        opacitySeek.max = 90
        opacityBlock.addView(opacitySeek)
        panel.addView(opacityBlock)

        // Modalita' temporanea / permanente
        panel.addView(label("Modalità"))
        val seg = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(0x26FFFFFF, 16f)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        tempButton = modeButton("Temporanea") {
            drawingView.temporary = true
            refreshMode()
        }
        permButton = modeButton("Permanente") {
            drawingView.temporary = false
            refreshMode()
        }
        seg.addView(tempButton)
        seg.addView(permButton)
        panel.addView(seg)
        modeHint = TextView(ctx).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * u)
            setPadding(dp(2f), dp(6f), 0, 0)
        }
        panel.addView(modeHint)
    }

    private fun modeButton(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(PRIMARY)
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 12.5f * u)
        layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1f)
        setOnClickListener { onClick() }
    }

    private fun seekBar(onChange: (Int) -> Unit): SeekBar = SeekBar(ctx).apply {
        progressTintList = ColorStateList.valueOf(ORANGE)
        thumbTintList = ColorStateList.valueOf(ORANGE)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun widthRange(t: Tool): Pair<Int, Int> = when (t) {
        Tool.HIGHLIGHTER -> Pair(6, 60)
        Tool.TEXT -> Pair(12, 72)
        Tool.ERASER -> Pair(8, 64)
        else -> Pair(1, 30)
    }

    /** Anteprima di come verra' il tratto con colore, spessore e opacita' correnti. */
    private inner class StrokePreview(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            background = round(0xFF4A4A47.toInt(), 16f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val t = Prefs.lastTool
            val w = width.toFloat()
            val h = height.toFloat()
            val maxW = h * 0.7f
            val sw = (Prefs.width(t) * density).coerceIn(2f, maxW)
            val alpha = (Prefs.opacity(t).coerceIn(10, 100) * 255 / 100)
            val color = (Prefs.color(t) and 0x00FFFFFF) or (alpha shl 24)
            when (t) {
                Tool.ERASER -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2f * density
                    p.color = PRIMARY
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
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeWidth = sw
                    p.color = color
                    path.reset()
                    val pad = max(sw, 16f * density)
                    path.moveTo(pad, h * 0.62f)
                    path.cubicTo(w * 0.35f, h * 0.15f, w * 0.6f, h * 0.95f, w - pad, h * 0.38f)
                    canvas.drawPath(path, p)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Stato della barra

    private fun selectTool(tool: Tool, enableDrawing: Boolean) {
        Prefs.lastTool = tool
        for ((t, b) in toolButtons) select(b, t == tool)
        if (enableDrawing && !drawing) setDrawing(true)
        refreshPanel()
    }

    private fun refreshPanel() {
        val t = Prefs.lastTool
        val eraser = t == Tool.ERASER
        panelTitle.text = toolName(t)
        colorBlock.visibility = if (eraser) View.GONE else View.VISIBLE
        opacityBlock.visibility = if (eraser) View.GONE else View.VISIBLE
        shapeRow.visibility = if (t == Tool.SHAPE) View.VISIBLE else View.GONE

        val (mn, mx) = widthRange(t)
        widthSeek.max = mx - mn
        widthSeek.progress = Prefs.width(t).coerceIn(mn, mx) - mn
        opacitySeek.progress = Prefs.opacity(t).coerceIn(10, 100) - 10
        updateLabels()
        refreshSwatches()
        refreshShapes()
        refreshMode()
    }

    private fun updateLabels() {
        val t = Prefs.lastTool
        widthLabel.text = ((if (t == Tool.TEXT) "Dimensione  " else "Spessore  ") + Prefs.width(t)).uppercase()
        opacityLabel.text = ("Opacità  " + Prefs.opacity(t) + "%").uppercase()
        preview.invalidate()
    }

    private fun refreshSwatches() {
        val t = Prefs.lastTool
        val selected = Prefs.color(t)
        for (sw in swatches) {
            val c = sw.tag as Int
            val on = c == selected
            sw.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(if (on) dp(3f) else dp(1f), if (on) ORANGE else 0x66FFFFFF)
            }
        }
        // Pallino del colore sul pulsante "stile"
        styleDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (t == Tool.ERASER) {
                setColor(0x33FFFFFF)
                setStroke(dp(2f), PRIMARY)
            } else {
                setColor(selected)
                setStroke(dp(2.5f), PRIMARY)
            }
        }
        preview.invalidate()
    }

    private fun refreshShapes() {
        for ((type, b) in shapeButtons) select(b, type == Prefs.shapeType)
    }

    private fun refreshMode() {
        select(tempButton, drawingView.temporary, 13f)
        select(permButton, !drawingView.temporary, 13f)
        modeHint.text = if (drawingView.temporary) "I disegni svaniscono da soli" else "I disegni restano finché non li cancelli"
    }

    // ------------------------------------------------------------------ Layout e posizione

    private fun layoutRoot() {
        root.removeAllViews()
        if (!expanded) {
            root.addView(if (ghostMode) ghost else bubble)
            return
        }
        val gap = dp(8f)
        if (panelOpen && panelOnLeft) {
            root.addView(panel)
            (panel.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, gap, 0)
        }
        root.addView(bar)
        if (panelOpen && !panelOnLeft) {
            root.addView(panel)
            (panel.layoutParams as LinearLayout.LayoutParams).setMargins(gap, 0, 0, 0)
        }
    }

    private fun updateBar() {
        layoutRoot()
        try { wm.updateViewLayout(root, barParams) } catch (_: Exception) {}
    }

    private fun togglePanel() {
        val (sw, sh) = screenSize()
        val barW = dp(BAR_W)
        val extra = dp(PANEL_W) + dp(8f)
        if (!panelOpen) {
            panelOnLeft = barParams.x + barW / 2 > sw / 2
            panelOpen = true
            if (panelOnLeft) barParams.x -= extra
            else barParams.x = min(barParams.x, sw - barW - extra)
            barParams.y = barParams.y.coerceIn(0, max(0, sh - dp(EST_PANEL_H)))
            refreshPanel()
        } else {
            panelOpen = false
            if (panelOnLeft) barParams.x += extra
        }
        styleButton.background = if (panelOpen) round(0x33FFFFFF, 16f) else null
        barParams.x = barParams.x.coerceIn(0, max(0, sw - barW))
        updateBar()
    }

    /** Mostra la barra completa (true) oppure la riduce a una bolla (false), con una piccola animazione. */
    private fun setExpanded(value: Boolean) {
        if (!value && panelOpen) togglePanel()
        handler.removeCallbacks(peekRun)
        handler.removeCallbacks(autoHideRun)
        peeking = false
        root.animate().cancel()
        root.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(100).withEndAction {
            if (!shown) return@withEndAction
            val (sw, sh) = screenSize()
            if (value) {
                expanded = true
                val margin = dp(10f)
                barParams.x = if (dockedLeft) margin else sw - dp(BAR_W) - margin
                val maxY = max(dp(24f), sh - dp(EST_BAR_H) - dp(24f))
                barParams.y = barParams.y.coerceIn(dp(24f), maxY)
            } else if (ghostMode) {
                expanded = false
                val gw = px(GHOST_W)
                val gh = px(GHOST_H)
                dockedLeft = barParams.x + dp(BAR_W) / 2 < sw / 2
                barParams.x = if (dockedLeft) 0 else sw - gw
                val minY = px(40f)
                barParams.y = (barParams.y + dp(60f)).coerceIn(minY, max(minY, sh - gh - minY))
            } else {
                expanded = false
                val size = dp(BUBBLE)
                dockedLeft = barParams.x + dp(BAR_W) / 2 < sw / 2
                barParams.x = if (dockedLeft) dp(6f) else sw - size - dp(6f)
                barParams.y = barParams.y + dp(10f)
            }
            updateBar()
            root.scaleX = 0.8f
            root.scaleY = 0.8f
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
                .setInterpolator(OvershootInterpolator(1.1f))
                .withEndAction { if (expanded) scheduleAutoHide() else if (!ghostMode) schedulePeek() }
                .start()
        }.start()
    }

    private fun addToolbarWindow() {
        val (sw, sh) = screenSize()
        val margin = dp(10f)
        val barW = dp(BAR_W)
        val barH = dp(EST_BAR_H)
        val corner = Prefs.toolbarCorner
        dockedLeft = !(corner == 1 || corner == 3)
        var x = if (dockedLeft) margin else sw - barW - margin
        var y = if (corner >= 2) max(margin, sh - barH - dp(60f)) else dp(60f)
        if (!expanded) {
            // barra nascosta: parto subito dalla zona invisibile sul bordo
            x = if (dockedLeft) 0 else sw - px(GHOST_W)
            y = if (corner >= 2) sh - px(GHOST_H) - px(160f) else px(120f)
        }
        barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "ScreenAnnotator-toolbar"
        }
        wm.addView(root, barParams)
    }

    // ------------------------------------------------------------------ Bolla: aggancio al bordo e "sbirciata"

    // ------------------------------------------------------------------ Barra che si nasconde da sola

    private val autoHideRun = Runnable {
        if (!shown || !expanded) return@Runnable
        if (textWindow != null) {
            scheduleAutoHide()
            return@Runnable
        }
        setExpanded(false)
    }

    /** Con la barra «invisibile nel video» la richiudo dopo un po' che non la usi. */
    private fun scheduleAutoHide(ms: Long = HIDE_DELAY) {
        handler.removeCallbacks(autoHideRun)
        if (ghostMode && expanded && shown) handler.postDelayed(autoHideRun, ms)
    }

    private fun onBarTouched() {
        if (expanded) scheduleAutoHide(if (panelOpen) 10_000L else HIDE_DELAY)
    }

    private val peekRun = Runnable {
        if (!shown || expanded) return@Runnable
        val (sw, _) = screenSize()
        val size = dp(BUBBLE)
        val tx = if (dockedLeft) -(size * 0.45f).toInt() else sw - (size * 0.55f).toInt()
        peeking = true
        animateTo(tx, barParams.y)
        root.animate().alpha(0.6f).setDuration(220).start()
    }

    private fun schedulePeek() {
        handler.removeCallbacks(peekRun)
        handler.postDelayed(peekRun, 2500)
    }

    /** Sveglia la bolla se e' mezza nascosta oltre il bordo. */
    private fun wake() {
        handler.removeCallbacks(peekRun)
        if (!peeking) return
        peeking = false
        val (sw, _) = screenSize()
        val size = dp(BUBBLE)
        val tx = if (dockedLeft) dp(6f) else sw - size - dp(6f)
        animateTo(tx, barParams.y)
        root.animate().alpha(1f).setDuration(150).start()
    }

    private fun snapBubble() {
        val (sw, sh) = screenSize()
        val size = dp(BUBBLE)
        dockedLeft = barParams.x + size / 2 < sw / 2
        val tx = if (dockedLeft) dp(6f) else sw - size - dp(6f)
        val top = dp(24f)
        val ty = barParams.y.coerceIn(top, max(top, sh - size - top))
        animateTo(tx, ty) { schedulePeek() }
    }

    private fun animateTo(tx: Int, ty: Int, end: (() -> Unit)? = null) {
        val sx = barParams.x
        val sy = barParams.y
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                barParams.x = (sx + (tx - sx) * f).toInt()
                barParams.y = (sy + (ty - sy) * f).toInt()
                try { wm.updateViewLayout(root, barParams) } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    end?.invoke()
                }
            })
        }
        anims.add(a)
        a.start()
    }

    /** Trascinamento della barra (o della bolla) con soglia per distinguere tocco e spostamento. */
    private fun attachDrag(handle: View, onClick: (() -> Unit)? = null, onDrop: (() -> Unit)? = null) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wake()
                    startX = barParams.x
                    startY = barParams.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (moved) {
                        val (sw, sh) = screenSize()
                        barParams.x = (startX + dx).coerceIn(0, max(0, sw - max(root.width, 1)))
                        barParams.y = (startY + dy).coerceIn(0, max(0, sh - max(root.height, 1)))
                        try { wm.updateViewLayout(root, barParams) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onClick?.invoke() else onDrop?.invoke()
                MotionEvent.ACTION_CANCEL -> if (moved) onDrop?.invoke()
            }
            true
        }
    }
}
