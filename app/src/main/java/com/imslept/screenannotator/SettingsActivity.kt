package com.imslept.screenannotator

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** Tutte le impostazioni: ogni modifica viene salvata subito. */
class SettingsActivity : AppCompatActivity() {

    private val palette = intArrayOf(
        0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
        0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt(), 0xFF00ACC1.toInt(),
        0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt(), 0xFF212121.toInt(), 0xFF000000.toInt(),
    )

    private lateinit var column: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        title(getString(R.string.settings), com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium, 0)

        // ---------------- Registrazione
        title("Registrazione", com.google.android.material.R.style.TextAppearance_Material3_TitleLarge, 24)
        label("Risoluzione (lato corto)")
        chips(listOf("Nativa" to 0, "1440p" to 1440, "1080p" to 1080, "720p" to 720), Prefs.resolution) { Prefs.resolution = it }
        label("Fotogrammi al secondo")
        chips(listOf("30 fps" to 30, "60 fps" to 60), Prefs.fps) { Prefs.fps = it }
        slider({ "Qualità (bitrate): $it Mbit/s" }, 2, 50, 1, Prefs.bitrateMbps) { Prefs.bitrateMbps = it }

        label("Sorgente audio")
        chips(
            listOf("Nessuno" to 0, "Microfono" to 1, "Dispositivo" to 2, "Entrambi" to 3), Prefs.audioMode
        ) { Prefs.audioMode = it }
        val touches = MaterialSwitch(this).apply {
            text = getString(R.string.show_touches)
            isChecked = Prefs.showTouches
            setOnCheckedChangeListener { _, c -> Prefs.showTouches = c }
        }
        column.addView(touches, params().apply { topMargin = dp(12) })

        // ---------------- Scia a scomparsa
        title("Scia a scomparsa", com.google.android.material.R.style.TextAppearance_Material3_TitleLarge, 32)
        val temp = MaterialSwitch(this).apply {
            text = "Modalità temporanea di default"
            isChecked = Prefs.temporaryDefault
            setOnCheckedChangeListener { _, c -> Prefs.temporaryDefault = c }
        }
        column.addView(temp, params().apply { topMargin = dp(8) })
        slider({ "Resta visibile: ${it} ms prima di svanire" }, 0, 8000, 100, Prefs.holdMs) { Prefs.holdMs = it }
        slider({ "Durata dissolvenza: ${it} ms" }, 50, 4000, 50, Prefs.fadeMs) { Prefs.fadeMs = it }
        label("Tipo di dissolvenza")
        chips(listOf("Intero disegno" to 0, "Scia progressiva" to 1), Prefs.fadeMode) { Prefs.fadeMode = it }

        // ---------------- Strumenti
        title("Strumenti", com.google.android.material.R.style.TextAppearance_Material3_TitleLarge, 32)
        val tools = listOf(
            Tool.PEN to "Penna", Tool.PENCIL to "Matita", Tool.HIGHLIGHTER to "Evidenziatore",
            Tool.SHAPE to "Forme", Tool.TEXT to "Testo",
        )
        for ((tool, name) in tools) {
            label(name)
            swatches(Prefs.color(tool)) { Prefs.setColor(tool, it) }
            val (mn, mx) = when (tool) {
                Tool.HIGHLIGHTER -> Pair(6, 60)
                Tool.TEXT -> Pair(12, 72)
                else -> Pair(1, 30)
            }
            val what = if (tool == Tool.TEXT) "Dimensione" else "Spessore"
            slider({ "$what: $it" }, mn, mx, 1, Prefs.width(tool)) { Prefs.setWidth(tool, it) }
            slider({ "Opacità: $it%" }, 10, 100, 5, Prefs.opacity(tool)) { Prefs.setOpacity(tool, it) }
        }

        // ---------------- Barra flottante
        title("Barra flottante", com.google.android.material.R.style.TextAppearance_Material3_TitleLarge, 32)
        label("Posizione iniziale")
        chips(
            listOf("Alto sx" to 0, "Alto dx" to 1, "Basso sx" to 2, "Basso dx" to 3), Prefs.toolbarCorner
        ) { Prefs.toolbarCorner = it }
        slider({ "Dimensione: $it%" }, 70, 150, 5, Prefs.toolbarScale) { Prefs.toolbarScale = it }
    }

    // ------------------------------------------------------------------ Componenti

    private fun params() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun title(text: String, style: Int, topDp: Int) {
        column.addView(TextView(this).apply {
            this.text = text
            setTextAppearance(style)
        }, params().apply { topMargin = dp(topDp) })
    }

    private fun label(text: String) {
        column.addView(TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        }, params().apply { topMargin = dp(14) })
    }

    private fun chips(options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit) {
        val group = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        for ((name, value) in options) {
            group.addView(Chip(this).apply {
                id = View.generateViewId()
                text = name
                isCheckable = true
                isChecked = value == current
                setOnClickListener { onPick(value) }
            })
        }
        column.addView(group, params())
    }

    private fun slider(label: (Int) -> String, from: Int, to: Int, step: Int, value: Int, onChange: (Int) -> Unit) {
        val tv = TextView(this).apply {
            text = label(value)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        // Lo Slider richiede un valore allineato al passo: lo riallineo se serve
        val snapped = (from + Math.round((value.coerceIn(from, to) - from).toFloat() / step) * step).coerceIn(from, to)
        val s = Slider(this).apply {
            valueFrom = from.toFloat()
            valueTo = to.toFloat()
            stepSize = step.toFloat()
            this.value = snapped.toFloat()
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    tv.text = label(v.toInt())
                    onChange(v.toInt())
                }
            }
        }
        column.addView(tv, params().apply { topMargin = dp(10) })
        column.addView(s, params())
    }

    private fun swatches(current: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<View>()

        fun restyle(selected: Int) {
            for (v in views) {
                val c = v.tag as Int
                val on = c == selected
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(if (on) dp(3) else dp(1), if (on) 0xFFD97757.toInt() else 0x66888888)
                }
            }
        }

        for (c in palette) {
            val v = View(this).apply {
                tag = c
                setOnClickListener {
                    onPick(c)
                    restyle(c)
                }
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
        }
        restyle(current)
        column.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, params())
    }
}
