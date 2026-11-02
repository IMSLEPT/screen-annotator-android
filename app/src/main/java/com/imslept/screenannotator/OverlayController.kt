package com.imslept.screenannotator

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Gestisce le due finestre sovrapposte alle altre app:
 *  1) la superficie di disegno a schermo intero (trasparente ai tocchi quando il disegno e' spento)
 *  2) la barra degli strumenti flottante, trascinabile e riducibile a bolla.
 */
class OverlayController(
    private val ctx: Context,
    private val onStopRequested: () -> Unit,
) {
    private companion object {
        const val ORANGE = 0xFFD97757.toInt()
        const val DARK = 0xE61E1E1C.toInt()
        const val PRIMARY = 0xFFFFFFFF.toInt()
        const val MUTED = 0xB3FFFFFF.toInt()

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
    private val u = ctx.resources.displayMetrics.density * Prefs.toolbarScale / 100f
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val drawingView = DrawingView(ctx)
    private lateinit var drawParams: WindowManager.LayoutParams
    private lateinit var barParams: WindowManager.LayoutParams

    private lateinit var root: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var bubble: TextView

    private val toolButtons = HashMap<Tool, TextView>()
    private lateinit var drawToggle: TextView
    private lateinit var optionsButton: TextView

    private val swatches = ArrayList<View>()
    private lateinit var colorBlock: LinearLayout
    private lateinit var opacityBlock: LinearLayout
    private lateinit var shapeRow: LinearLayout
    private val shapeButtons = HashMap<ShapeType, TextView>()
    private lateinit var widthSeek: SeekBar
    private lateinit var widthLabel: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityLabel: TextView
    private lateinit var tempButton: TextView
    private lateinit var permButton: TextView

    private var drawing = false
    private var expanded = true
    private var panelOpen = false
    private var panelOnLeft = false
    private var pendingText: PointF? = null
    private var shown = false

    val isDrawing: Boolean get() = drawing

    private fun dp(v: Float): Int = (v * u).toInt()

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
        addDrawingWindow()
        buildToolbar()
        addToolbarWindow()
        drawingView.onTextRequest = { x, y -> requestText(x, y) }
        setDrawing(false)
        selectTool(Prefs.lastTool, enableDrawing = false)
    }

    fun destroy() {
        if (!shown) return
        shown = false
        OverlayBridge.textListener = null
        try { wm.removeView(root) } catch (_: Exception) {}
        try { wm.removeView(drawingView) } catch (_: Exception) {}
    }

    fun toggleDrawing() = setDrawing(!drawing)

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
            drawToggle.text = if (on) "✍︎" else "☝︎"
            highlight(drawToggle, on)
            bubble.background = round(if (on) ORANGE else 0xE6555555.toInt(), 22f)
        }
    }

    private fun requestText(x: Float, y: Float) {
        pendingText = PointF(x, y)
        OverlayBridge.textListener = { text ->
            val p = pendingText
            if (text != null && p != null) drawingView.addText(p.x, p.y, text)
            pendingText = null
            OverlayBridge.textListener = null
        }
        ctx.startActivity(
            Intent(ctx, TextInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ------------------------------------------------------------------ Barra degli strumenti

    private fun round(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * u
        }

    private fun highlight(v: TextView, on: Boolean) {
        v.background = if (on) round(ORANGE, 12f) else null
    }

    private fun iconButton(glyph: String, color: Int = PRIMARY, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f * u)
            layoutParams = LinearLayout.LayoutParams(dp(40f), dp(38f))
            setOnClickListener { onClick() }
        }

    private fun label(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * u)
        setPadding(0, dp(8f), 0, dp(2f))
    }

    private fun buildToolbar() {
        // --- colonna di strumenti
        bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(DARK, 22f)
            setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
        }
        val handle = iconButton("≡", MUTED) {}
        attachDrag(handle)
        bar.addView(handle)

        drawToggle = iconButton("☝︎") { toggleDrawing() }
        bar.addView(drawToggle)

        val tools = listOf(
            Tool.PEN to "✒︎",
            Tool.PENCIL to "✎︎",
            Tool.HIGHLIGHTER to "▬",
            Tool.TEXT to "T",
            Tool.SHAPE to "◯",
            Tool.ERASER to "⌫",
        )
        for ((tool, glyph) in tools) {
            val b = iconButton(glyph) { selectTool(tool, enableDrawing = true) }
            toolButtons[tool] = b
            bar.addView(b)
        }
        bar.addView(iconButton("↶") { drawingView.undo() })
        bar.addView(iconButton("↷") { drawingView.redo() })
        bar.addView(iconButton("🗑") { drawingView.clearAll() })
        optionsButton = iconButton("⚙︎") { togglePanel() }
        bar.addView(optionsButton)
        bar.addView(iconButton("—") { setExpanded(false) })
        bar.addView(iconButton("■", 0xFFFF5252.toInt()) { onStopRequested() })

        buildPanel()

        // --- bolla (barra ridotta)
        bubble = TextView(ctx).apply {
            text = "✎︎"
            gravity = Gravity.CENTER
            setTextColor(PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * u)
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
            background = round(0xE6555555.toInt(), 22f)
        }
        attachDrag(bubble) { setExpanded(true) }

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        layoutRoot()
    }

    private fun buildPanel() {
        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(DARK, 22f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(dp(220f), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Colore
        colorBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        colorBlock.addView(label("Colore"))
        for (rowStart in intArrayOf(0, 6)) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in rowStart until rowStart + 6) {
                val color = PALETTE[i]
                val sw = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f)).apply {
                        setMargins(dp(2f), dp(2f), dp(2f), dp(2f))
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
            ShapeType.CIRCLE to "◯", ShapeType.ARROW to "➚", ShapeType.LINE to "╱", ShapeType.RECT to "▭"
        )
        for ((type, glyph) in shapes) {
            val b = iconButton(glyph) {
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
        val modeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tempButton = modeButton("⏱ Temporanea") {
            drawingView.temporary = true
            refreshMode()
        }
        permButton = modeButton("∞ Permanente") {
            drawingView.temporary = false
            refreshMode()
        }
        modeRow.addView(tempButton)
        modeRow.addView(permButton)
        panel.addView(modeRow)
    }

    private fun modeButton(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(PRIMARY)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * u)
        layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply { setMargins(dp(2f), 0, dp(2f), 0) }
        setOnClickListener { onClick() }
    }

    private fun seekBar(onChange: (Int) -> Unit): SeekBar = SeekBar(ctx).apply {
        progressTintList = android.content.res.ColorStateList.valueOf(ORANGE)
        thumbTintList = android.content.res.ColorStateList.valueOf(ORANGE)
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

    // ------------------------------------------------------------------ Stato della barra

    private fun selectTool(tool: Tool, enableDrawing: Boolean) {
        Prefs.lastTool = tool
        for ((t, b) in toolButtons) highlight(b, t == tool)
        if (enableDrawing && !drawing) setDrawing(true)
        refreshPanel()
    }

    private fun refreshPanel() {
        val t = Prefs.lastTool
        val eraser = t == Tool.ERASER
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
        widthLabel.text = (if (t == Tool.TEXT) "Dimensione: " else "Spessore: ") + Prefs.width(t)
        opacityLabel.text = "Opacità: " + Prefs.opacity(t) + "%"
    }

    private fun refreshSwatches() {
        val selected = Prefs.color(Prefs.lastTool)
        for (sw in swatches) {
            val c = sw.tag as Int
            val on = c == selected
            sw.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(if (on) dp(3f) else dp(1f), if (on) ORANGE else 0x66FFFFFF)
            }
        }
    }

    private fun refreshShapes() {
        for ((type, b) in shapeButtons) highlight(b, type == Prefs.shapeType)
    }

    private fun refreshMode() {
        highlight(tempButton, drawingView.temporary)
        highlight(permButton, !drawingView.temporary)
    }

    // ------------------------------------------------------------------ Layout e posizione

    private fun layoutRoot() {
        root.removeAllViews()
        if (!expanded) {
            root.addView(bubble)
            return
        }
        val gap = dp(6f)
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
        val (sw, _) = screenSize()
        val extra = dp(220f) + dp(6f)
        if (!panelOpen) {
            panelOnLeft = barParams.x + dp(44f) / 2 > sw / 2
            panelOpen = true
            if (panelOnLeft) barParams.x -= extra
            refreshPanel()
        } else {
            panelOpen = false
            if (panelOnLeft) barParams.x += extra
        }
        highlight(optionsButton, panelOpen)
        barParams.x = barParams.x.coerceIn(0, max(0, sw - dp(44f)))
        updateBar()
    }

    private fun setExpanded(value: Boolean) {
        if (!value && panelOpen) togglePanel()
        expanded = value
        updateBar()
    }

    private fun addToolbarWindow() {
        val (sw, sh) = screenSize()
        val margin = dp(12f)
        val barW = dp(44f)
        val barH = dp(4f) * 2 + dp(38f) * 13
        val corner = Prefs.toolbarCorner
        val x = if (corner == 1 || corner == 3) sw - barW - margin else margin
        val y = if (corner >= 2) max(margin, sh - barH - dp(90f)) else dp(70f)
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

    /** Trascinamento della barra (o della bolla) con soglia per distinguere tocco e spostamento. */
    private fun attachDrag(handle: View, onClick: (() -> Unit)? = null) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                MotionEvent.ACTION_UP -> if (!moved) onClick?.invoke()
            }
            true
        }
    }
}
