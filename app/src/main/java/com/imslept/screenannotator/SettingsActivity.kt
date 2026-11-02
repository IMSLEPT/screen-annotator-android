package com.imslept.screenannotator

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

/** Impostazioni: ogni modifica viene salvata subito e le anteprime mostrano l'effetto in diretta. */
class SettingsActivity : AppCompatActivity() {

    private val ui by lazy { Ui(this) }
    private lateinit var column: LinearLayout
    private lateinit var toolBody: LinearLayout
    private var selectedTool = Tool.PEN

    private val palette = intArrayOf(
        0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
        0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt(), 0xFF00ACC1.toInt(),
        0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt(), 0xFF212121.toInt(), 0xFF000000.toInt(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTool = if (Prefs.lastTool == Tool.ERASER) Tool.PEN else Prefs.lastTool

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.c(R.color.bg))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // --- Intestazione con freccia indietro
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.roundIconButton(R.drawable.ic_back, "Indietro") { finish() }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            addView(
                ui.text(getString(R.string.settings), 26f, bold = true),
                ui.weight().apply { marginStart = ui.dp(14) }
            )
        }, ui.lp())

        buildVideo()
        buildAudio()
        buildTrail()
        buildTools()
        buildToolbar()
    }

    private fun section(icon: Int, title: String, subtitle: String): LinearLayout {
        val card = ui.card()
        card.addView(ui.sectionHeader(icon, title, subtitle))
        column.addView(card, ui.lp(18))
        return card
    }

    private fun label(card: LinearLayout, text: String, top: Int = 18) {
        card.addView(ui.text(text, 15f, R.color.ink_muted, bold = true), ui.lp(top))
    }

    private fun seconds(ms: Int): String =
        if (ms < 1000) "$ms ms" else String.format(Locale.ITALY, "%.1f s", ms / 1000f)

    // ------------------------------------------------------------------ Video

    private fun buildVideo() {
        val card = section(R.drawable.ic_video, "Video", "Qualità del filmato")
        label(card, "Risoluzione")
        card.addView(
            ui.segmented(
                listOf("Nativa" to 0, "1440p" to 1440, "1080p" to 1080, "720p" to 720), Prefs.resolution
            ) { Prefs.resolution = it },
            ui.lp(8)
        )
        label(card, "Fluidità")
        card.addView(
            ui.segmented(listOf("30 fps" to 30, "60 fps" to 60), Prefs.fps) { Prefs.fps = it },
            ui.lp(8)
        )
        card.addView(
            ui.slider("Nitidezza", 2, 50, 1, Prefs.bitrateMbps, { "$it Mbit/s" }) { Prefs.bitrateMbps = it },
            ui.lp(20)
        )
        card.addView(ui.note("Più è alta, più il video è nitido ma pesa di più."), ui.lp(8))
    }

    // ------------------------------------------------------------------ Audio e tocchi

    private fun buildAudio() {
        val card = section(R.drawable.ic_speaker, "Audio e tocchi", "Cosa finisce nel video")
        card.addView(
            ui.tiles(
                audioTiles(), columns = 2, current = Prefs.audioMode
            ) { Prefs.audioMode = it },
            ui.lp(16)
        )
        card.addView(
            ui.switchRow("Mostra i tocchi", "Un cerchio sotto il dito mentre disegni", Prefs.showTouches) {
                Prefs.showTouches = it
            },
            ui.lp(18)
        )
    }

    private fun audioTiles() = listOf(
        Tile(intArrayOf(R.drawable.ic_volume_off), "Nessun audio", 0),
        Tile(intArrayOf(R.drawable.ic_mic), "Microfono", 1),
        Tile(intArrayOf(R.drawable.ic_speaker), "Dispositivo", 2),
        Tile(intArrayOf(R.drawable.ic_mic, R.drawable.ic_speaker), "Entrambi", 3),
    )

    // ------------------------------------------------------------------ Scia a scomparsa

    private fun buildTrail() {
        val card = section(R.drawable.ic_pen, "Scia a scomparsa", "Come svaniscono i disegni")
        card.addView(
            ui.switchRow("Disegni temporanei", "Se spento, restano finché non li cancelli", Prefs.temporaryDefault) {
                Prefs.temporaryDefault = it
            },
            ui.lp(16)
        )
        card.addView(TrailPreview(this), ui.lp(16, h = ui.dp(120)))
        card.addView(
            ui.slider("Resta visibile", 0, 8000, 100, Prefs.holdMs, { seconds(it) }) { Prefs.holdMs = it },
            ui.lp(18)
        )
        card.addView(
            ui.slider("Svanisce in", 50, 4000, 50, Prefs.fadeMs, { seconds(it) }) { Prefs.fadeMs = it },
            ui.lp(14)
        )
        label(card, "Come svanisce")
        val hint = ui.note(fadeHint(Prefs.fadeMode))
        card.addView(
            ui.segmented(listOf("Tutto insieme" to 0, "A scia" to 1), Prefs.fadeMode) {
                Prefs.fadeMode = it
                (hint.getChildAt(1) as android.widget.TextView).text = fadeHint(it)
            },
            ui.lp(8)
        )
        card.addView(hint, ui.lp(10))
    }

    private fun fadeHint(mode: Int) =
        if (mode == 0) "Il disegno intero svanisce tutto nello stesso momento."
        else "Il tratto si consuma dal punto più vecchio a quello più recente."

    // ------------------------------------------------------------------ Strumenti

    private fun widthRange(t: Tool): Pair<Int, Int> = when (t) {
        Tool.HIGHLIGHTER -> Pair(6, 60)
        Tool.TEXT -> Pair(12, 72)
        Tool.ERASER -> Pair(8, 64)
        else -> Pair(1, 30)
    }

    private fun buildTools() {
        val card = section(R.drawable.ic_pencil, "Strumenti", "Colore e spessore di ognuno")
        val tiles = listOf(
            Tile(intArrayOf(R.drawable.ic_pen), "Penna", Tool.PEN.ordinal),
            Tile(intArrayOf(R.drawable.ic_pencil), "Matita", Tool.PENCIL.ordinal),
            Tile(intArrayOf(R.drawable.ic_highlighter), "Evidenziatore", Tool.HIGHLIGHTER.ordinal),
            Tile(intArrayOf(R.drawable.ic_shape), "Forme", Tool.SHAPE.ordinal),
            Tile(intArrayOf(R.drawable.ic_text), "Testo", Tool.TEXT.ordinal),
            Tile(intArrayOf(R.drawable.ic_eraser), "Gomma", Tool.ERASER.ordinal),
        )
        card.addView(
            ui.tiles(tiles, columns = 3, current = selectedTool.ordinal, heightDp = 68, labelSp = 12f) {
                selectedTool = Tool.values()[it]
                renderTool()
            },
            ui.lp(16)
        )
        toolBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(toolBody, ui.lp(0))
        renderTool()
    }

    private fun renderTool() {
        val t = selectedTool
        toolBody.removeAllViews()
        val preview = ToolPreview(this).apply { tool = t }
        toolBody.addView(preview, ui.lp(16, h = ui.dp(84)))

        if (t != Tool.ERASER) {
            toolBody.addView(
                ui.swatchGrid(palette, Prefs.color(t)) {
                    Prefs.setColor(t, it)
                    preview.invalidate()
                },
                ui.lp(14)
            )
        }
        val (mn, mx) = widthRange(t)
        val title = when (t) {
            Tool.TEXT -> "Dimensione"
            Tool.ERASER -> "Dimensione gomma"
            else -> "Spessore"
        }
        toolBody.addView(
            ui.slider(title, mn, mx, 1, Prefs.width(t).coerceIn(mn, mx), { "$it" }) {
                Prefs.setWidth(t, it)
                preview.invalidate()
            },
            ui.lp(if (t == Tool.ERASER) 18 else 10)
        )
        if (t != Tool.ERASER) {
            toolBody.addView(
                ui.slider("Opacità", 10, 100, 5, Prefs.opacity(t).coerceIn(10, 100), { "$it%" }) {
                    Prefs.setOpacity(t, it)
                    preview.invalidate()
                },
                ui.lp(18)
            )
        }
    }

    // ------------------------------------------------------------------ Barra flottante

    private fun buildToolbar() {
        val card = section(R.drawable.ic_overlay, "Barra flottante", "Dove compare mentre registri")
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(CornerPicker(context, Prefs.toolbarCorner) { Prefs.toolbarCorner = it })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text("Posizione iniziale", 16f, bold = true))
                addView(ui.text("Tocca l'angolo del telefono dove vuoi la barra.", 13f, R.color.ink_muted), ui.lp(4))
                addView(
                    ui.text("Durante la registrazione puoi trascinarla ovunque o ridurla a una bolla.", 13f, R.color.ink_muted),
                    ui.lp(10)
                )
            }, ui.weight().apply { marginStart = ui.dp(18) })
        }, ui.lp(16))
        card.addView(
            ui.slider("Dimensione", 70, 150, 5, Prefs.toolbarScale, { "$it%" }) { Prefs.toolbarScale = it },
            ui.lp(20)
        )
        card.addView(ui.note("Le modifiche valgono dalla prossima registrazione."), ui.lp(8))
    }
}
