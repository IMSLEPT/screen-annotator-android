package com.imslept.screenannotator

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.materialswitch.MaterialSwitch

/** Una scelta di una griglia di riquadri: una o due icone, un'etichetta e il valore salvato. */
class Tile(val icons: IntArray, val label: String, val value: Int)

/**
 * Piccolo kit di componenti grafici costruiti in codice, cosi' tutta l'app ha lo stesso aspetto:
 * schede, selettori a segmenti, riquadri, cursori con valore, interruttori e pulsanti.
 */
class Ui(val ctx: Context) {
    val density: Float = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density).toInt()
    fun c(@ColorRes id: Int): Int = ContextCompat.getColor(ctx, id)

    fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    fun weight(w: Float = 1f, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(0, h, w)

    fun bg(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun oval(color: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

    /** Sfondo con effetto onda al tocco, limitato alla stessa forma arrotondata. */
    fun ripple(content: Drawable, radiusDp: Int, rippleColor: Int = 0x22000000): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, bg(0xFFFFFFFF.toInt(), radiusDp))

    fun text(s: String, sp: Float, @ColorRes colorRes: Int = R.color.ink, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(c(colorRes))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.12f)
        }

    fun icon(@DrawableRes res: Int, @ColorRes tint: Int, sizeDp: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(c(tint))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Colora icone e testi di una vista (anche annidati). */
    fun ink(v: View, color: Int) {
        when (v) {
            is ImageView -> v.imageTintList = ColorStateList.valueOf(color)
            is TextView -> v.setTextColor(color)
            is ViewGroup -> for (i in 0 until v.childCount) ink(v.getChildAt(i), color)
        }
    }

    // ------------------------------------------------------------------ Schede e intestazioni

    fun card(vararg children: View, pad: Int = 18): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(c(R.color.card), 26, c(R.color.card_variant))
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        for (v in children) addView(v)
    }

    fun sectionHeader(@DrawableRes iconRes: Int, title: String, subtitle: String? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(ctx).apply {
                background = oval(c(R.color.primary_container))
                addView(icon(iconRes, R.color.on_primary_container, 22).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                })
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 18f, bold = true))
                if (subtitle != null) addView(text(subtitle, 13f, R.color.ink_muted))
            }, weight().apply { marginStart = dp(12) })
        }

    fun divider(top: Int = 14): View = View(ctx).apply {
        setBackgroundColor(c(R.color.card_variant))
        layoutParams = lp(top, h = dp(1))
    }

    /** Nota con icona "info": per spiegazioni brevi. */
    fun note(s: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(icon(R.drawable.ic_info, R.color.ink_muted, 18).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { topMargin = dp(1) }
        })
        addView(text(s, 13f, R.color.ink_muted), weight().apply { marginStart = dp(8) })
    }

    // ------------------------------------------------------------------ Pulsanti

    /** Pulsante a pillola. filled = arancione pieno, altrimenti tonale. */
    fun button(label: String, filled: Boolean = true, @DrawableRes iconRes: Int? = null, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val fill = if (filled) c(R.color.brand_orange) else c(R.color.primary_container)
            val ink = if (filled) c(R.color.ink_on_orange) else c(R.color.on_primary_container)
            background = ripple(bg(fill, 22), 22, 0x33000000)
            setPadding(dp(18), 0, dp(18), 0)
            minimumHeight = dp(44)
            isClickable = true
            if (iconRes != null) {
                addView(ImageView(ctx).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(ink)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
                })
            }
            addView(TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ink)
                typeface = Typeface.DEFAULT_BOLD
            })
            setOnClickListener { onClick() }
        }

    fun roundIconButton(@DrawableRes iconRes: Int, description: String, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            background = ripple(oval(c(R.color.card_variant)), 24)
            isClickable = true
            contentDescription = description
            setOnClickListener { onClick() }
            addView(icon(iconRes, R.color.ink, 24).apply {
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            })
        }

    // ------------------------------------------------------------------ Selettori

    /** Selettore a segmenti: la scelta attiva e' una pillola arancione. */
    fun segmented(options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(c(R.color.card_variant), 18)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val items = ArrayList<Pair<TextView, Int>>()
        fun paint(sel: Int) {
            for ((tv, v) in items) {
                val on = v == sel
                tv.background = if (on) bg(c(R.color.brand_orange), 14) else null
                tv.setTextColor(c(if (on) R.color.ink_on_orange else R.color.ink))
            }
        }
        for ((label, value) in options) {
            val tv = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                setOnClickListener {
                    paint(value)
                    onPick(value)
                }
            }
            items.add(tv to value)
            box.addView(tv)
        }
        paint(current)
        return box
    }

    /** Griglia di riquadri con icona ed etichetta (audio, strumenti). */
    fun tiles(
        options: List<Tile>,
        columns: Int,
        current: Int,
        heightDp: Int = 76,
        labelSp: Float = 13f,
        onPick: (Int) -> Unit,
    ): LinearLayout {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cells = ArrayList<Pair<LinearLayout, Int>>()
        fun paint(sel: Int) {
            for ((cell, v) in cells) {
                val on = v == sel
                cell.background = if (on) ripple(bg(c(R.color.brand_orange), 18), 18, 0x33000000)
                else ripple(bg(c(R.color.card_variant), 18), 18)
                ink(cell, c(if (on) R.color.ink_on_orange else R.color.ink))
            }
        }
        var index = 0
        while (index < options.size) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until columns) {
                val margin = if (i > 0) dp(8) else 0
                val opt = options.getOrNull(index + i)
                if (opt == null) {
                    row.addView(View(ctx), LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply { marginStart = margin })
                    continue
                }
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        for ((k, res) in opt.icons.withIndex()) {
                            addView(ImageView(ctx).apply {
                                setImageResource(res)
                                imageTintList = ColorStateList.valueOf(c(R.color.ink))
                                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                                    if (k > 0) marginStart = dp(4)
                                }
                            })
                        }
                    })
                    addView(TextView(ctx).apply {
                        text = opt.label
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
                        setTextColor(c(R.color.ink))
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                    }, lp(6))
                    setOnClickListener {
                        paint(opt.value)
                        onPick(opt.value)
                    }
                }
                cells.add(cell to opt.value)
                row.addView(cell, LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply { marginStart = margin })
            }
            col.addView(row, lp(if (index == 0) 0 else 8))
            index += columns
        }
        paint(current)
        return col
    }

    // ------------------------------------------------------------------ Cursore con valore

    private fun sliderTrack(): LayerDrawable {
        val h = dp(8)
        val back = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(c(R.color.outline), 90))
            cornerRadius = h / 2f
            setSize(1, h)
        }
        val front = GradientDrawable().apply {
            setColor(c(R.color.brand_orange))
            cornerRadius = h / 2f
            setSize(1, h)
        }
        val clip = ClipDrawable(front, Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf<Drawable>(back, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun sliderThumb(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(c(R.color.brand_orange))
        setStroke(dp(3), c(R.color.card))
        setSize(dp(28), dp(28))
    }

    /** Titolo a sinistra, valore in un badge a destra e sotto un cursore pulito. */
    fun slider(
        title: String,
        from: Int,
        to: Int,
        step: Int,
        value: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val valueTv = text(format(value), 14f, R.color.on_primary_container, bold = true).apply {
            background = bg(c(R.color.primary_container), 12)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 15f, R.color.ink), weight())
            addView(valueTv)
        })
        val steps = max1((to - from) / step)
        val seek = SeekBar(ctx).apply {
            max = steps
            progress = Math.round((value.coerceIn(from, to) - from).toFloat() / step)
            progressDrawable = sliderTrack()
            thumb = sliderThumb()
            splitTrack = false
            setPadding(dp(14), 0, dp(14), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = from + progress * step
                    valueTv.text = format(v)
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(seek, lp(8, h = dp(36)))
        return box
    }

    private fun max1(v: Int) = if (v < 1) 1 else v

    // ------------------------------------------------------------------ Interruttore e colori

    fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f, bold = true))
                if (subtitle != null) addView(text(subtitle, 13f, R.color.ink_muted))
            }, weight().apply { marginEnd = dp(12) })
            addView(MaterialSwitch(ctx).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            })
        }

    /** Griglia di colori a 6 colonne; quello scelto ha un anello. */
    fun swatchGrid(palette: IntArray, current: Int, onPick: (Int) -> Unit): LinearLayout {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val dots = ArrayList<View>()
        fun paint(sel: Int) {
            for (v in dots) {
                val color = v.tag as Int
                val on = color == sel
                v.background = oval(
                    color,
                    if (on) c(R.color.ink) else ColorUtils.setAlphaComponent(c(R.color.outline), 140),
                    if (on) 3 else 1,
                )
            }
        }
        var index = 0
        while (index < palette.size) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in index until minOf(index + 6, palette.size)) {
                val color = palette[i]
                val dot = View(ctx).apply {
                    tag = color
                    setOnClickListener {
                        paint(color)
                        onPick(color)
                    }
                }
                dots.add(dot)
                row.addView(FrameLayout(ctx).apply {
                    addView(dot, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
                }, LinearLayout.LayoutParams(0, dp(46), 1f))
            }
            col.addView(row)
            index += 6
        }
        paint(current)
        return col
    }
}
