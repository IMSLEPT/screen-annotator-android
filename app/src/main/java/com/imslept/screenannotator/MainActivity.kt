package com.imslept.screenannotator

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        private const val ADB_COMMAND = "adb shell pm grant com.imslept.screenannotator android.permission.WRITE_SECURE_SETTINGS"
    }

    private val ui by lazy { Ui(this) }

    private lateinit var scroll: ScrollView
    private lateinit var ring: View
    private lateinit var heroButton: FrameLayout
    private lateinit var heroDisc: FrameLayout
    private lateinit var heroIcon: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var summaryText: TextView
    private lateinit var setupCard: View
    private lateinit var overlayDone: View
    private lateinit var overlayAction: View
    private lateinit var overlayHelp: View
    private lateinit var micRow: View
    private lateinit var micDone: View
    private lateinit var micAction: View
    private lateinit var secureNote: View
    private lateinit var secureNoteText: TextView
    private lateinit var secureButtons: View
    private var pulse: ObjectAnimator? = null

    /** true mentre e' aperta la richiesta di registrazione: evita doppie finestre con doppio tocco. */
    private var requesting = false

    private val stateListener: () -> Unit = { runOnUiThread { refresh() } }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    // negato per sempre: Android non mostra piu' la richiesta, si attiva dalle impostazioni
                    Toast.makeText(this, "Attiva il microfono in Autorizzazioni → Microfono", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this, "Senza il permesso il microfono non si può registrare", Toast.LENGTH_LONG).show()
                }
            }
            refresh()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Anche senza microfono o notifiche si puo' registrare: l'audio viene semplicemente escluso
            requesting = false
            requestProjection()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            requesting = false
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this, RecorderService.startIntent(this, res.resultCode, data)
                )
                moveTaskToBack(true) // torna alla schermata precedente: si e' pronti a registrare
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            intent.removeExtra(EXTRA_AUTO_START)
            window.decorView.post { onStartClick() }
        }
    }

    override fun onResume() {
        super.onResume()
        RecorderService.addListener(stateListener)
        refresh()
    }

    override fun onPause() {
        RecorderService.removeListener(stateListener)
        pulse?.cancel()
        super.onPause()
    }

    // ------------------------------------------------------------------ Mattoncini

    private fun doneBadge(): FrameLayout = FrameLayout(this).apply {
        background = ui.oval(ui.c(R.color.brand_green))
        addView(ui.icon(R.drawable.ic_check, R.color.bg, 20).apply {
            layoutParams = FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER)
        })
        layoutParams = LinearLayout.LayoutParams(ui.dp(32), ui.dp(32))
    }

    /** Riga della checklist: icona, titolo, descrizione e a destra spunta verde oppure pulsante. */
    private fun permRow(iconRes: Int, title: String, subtitle: String, done: View, action: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                background = ui.oval(ui.c(R.color.primary_container))
                addView(ui.icon(iconRes, R.color.on_primary_container, 22).apply {
                    layoutParams = FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER)
                })
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text(title, 16f, bold = true))
                addView(ui.text(subtitle, 13f, R.color.ink_muted))
            }, ui.weight().apply { marginStart = ui.dp(12); marginEnd = ui.dp(8) })
            addView(done)
            addView(action)
        }

    private fun stepRow(n: Int, text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            this.text = n.toString()
            gravity = Gravity.CENTER
            setTextColor(ui.c(R.color.ink_on_orange))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.oval(ui.c(R.color.brand_orange))
        }, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)))
        addView(ui.text(text, 15f), ui.weight().apply { marginStart = ui.dp(14) })
    }

    // ------------------------------------------------------------------ Interfaccia

    private fun buildUi() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(32))
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(ui.c(R.color.bg))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // --- Intestazione
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text(getString(R.string.app_name), 26f, bold = true))
                addView(ui.text("Registra lo schermo e disegna in diretta", 14f, R.color.ink_muted))
            }, ui.weight())
            addView(
                ui.roundIconButton(R.drawable.ic_settings, getString(R.string.settings)) {
                    startActivity(Intent(context, SettingsActivity::class.java))
                },
                LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))
            )
        }, ui.lp())

        // --- Pulsante di registrazione
        ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke(ui.dp(3), ui.c(R.color.brand_orange))
                alpha = 90
            }
        }
        heroIcon = ui.icon(R.drawable.ic_record, R.color.ink_on_orange, 64)
        heroDisc = FrameLayout(this).apply {
            background = ui.oval(ui.c(R.color.brand_orange))
            elevation = ui.dp(6).toFloat()
            addView(heroIcon.apply { layoutParams = FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.CENTER) })
        }
        heroButton = FrameLayout(this).apply {
            isClickable = true
            contentDescription = getString(R.string.start_recording)
            setOnClickListener { onStartClick() }
            addView(ring, FrameLayout.LayoutParams(ui.dp(176), ui.dp(176), Gravity.CENTER))
            addView(heroDisc, FrameLayout.LayoutParams(ui.dp(140), ui.dp(140), Gravity.CENTER))
        }
        heroTitle = ui.text("", 22f, bold = true).apply { gravity = Gravity.CENTER }
        heroSubtitle = ui.text("", 15f, R.color.ink_muted).apply { gravity = Gravity.CENTER }

        // Riepilogo delle impostazioni attive: si tocca per cambiarle
        summaryText = ui.text("", 13f, R.color.on_primary_container, bold = true)
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.ripple(ui.bg(ui.c(R.color.primary_container), 20), 20)
            setPadding(ui.dp(16), ui.dp(9), ui.dp(10), ui.dp(9))
            isClickable = true
            setOnClickListener { startActivity(Intent(context, SettingsActivity::class.java)) }
            addView(summaryText)
            addView(ui.icon(R.drawable.ic_chevron_right, R.color.on_primary_container, 18).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginStart = ui.dp(4) }
            })
        }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(heroButton, LinearLayout.LayoutParams(ui.dp(176), ui.dp(176)))
            addView(heroTitle, ui.lp(14))
            addView(heroSubtitle, ui.lp(2))
            addView(summary, ui.lp(14, w = ViewGroup.LayoutParams.WRAP_CONTENT))
        }, ui.lp(24))

        // --- Preparazione (sparisce quando tutto e' a posto)
        overlayDone = doneBadge()
        overlayAction = ui.button("Attiva") { openOverlaySettings() }
        micDone = doneBadge()
        micAction = ui.button("Attiva") { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        micRow = permRow(R.drawable.ic_mic, "Microfono e audio", "Per registrare voce e suoni", micDone, micAction)

        overlayHelp = buildOverlayHelp()
        setupCard = ui.card(
            ui.text("Prepara l'app", 18f, bold = true),
            ui.text("Pochi passaggi, una volta sola", 13f, R.color.ink_muted),
            permRow(R.drawable.ic_overlay, "Mostra sopra altre app", "Serve per disegnare sopra le altre app", overlayDone, overlayAction)
                .apply { layoutParams = ui.lp(16) },
            overlayHelp.apply { layoutParams = ui.lp(12) },
            micRow.apply { layoutParams = ui.lp(16) },
        )
        column.addView(setupCard, ui.lp(24))

        // --- Cosa registrare
        val audioTiles = listOf(
            Tile(intArrayOf(R.drawable.ic_volume_off), "Nessun audio", 0),
            Tile(intArrayOf(R.drawable.ic_mic), "Microfono", 1),
            Tile(intArrayOf(R.drawable.ic_speaker), "Dispositivo", 2),
            Tile(intArrayOf(R.drawable.ic_mic, R.drawable.ic_speaker), "Entrambi", 3),
        )
        val noteView = ui.note("")
        secureNoteText = noteView.getChildAt(1) as TextView
        secureButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.button("Apri Opzioni sviluppatore", filled = true) { openDeveloperOptions() }, ui.lp(10))
            addView(ui.button("Copia il comando per il computer", filled = false, iconRes = R.drawable.ic_copy) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", ADB_COMMAND))
                Toast.makeText(this@MainActivity, "Comando copiato", Toast.LENGTH_SHORT).show()
            }, ui.lp(8))
        }
        secureNote = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(noteView)
            addView(secureButtons)
        }
        column.addView(
            ui.card(
                ui.sectionHeader(R.drawable.ic_speaker, "Cosa registrare", "Scegli l'audio del video"),
                ui.tiles(audioTiles, columns = 2, current = Prefs.audioMode) {
                    Prefs.audioMode = it
                    refresh()
                }.apply { layoutParams = ui.lp(16) },
                ui.divider(16),
                ui.switchRow("Mostra i tocchi", "Un cerchio sotto il dito, anche senza disegnare", Prefs.showTouches) {
                    Prefs.showTouches = it
                    refresh()
                }.apply { layoutParams = ui.lp(14) },
                secureNote.apply { layoutParams = ui.lp(12) },
            ),
            ui.lp(18)
        )

        // --- Come funziona
        column.addView(
            ui.card(
                ui.sectionHeader(R.drawable.ic_info, "Come funziona", "Quattro passi"),
                stepRow(1, "Tocca il cerchio e scegli «Intero schermo» nella finestra di sistema.")
                    .apply { layoutParams = ui.lp(16) },
                stepRow(2, "Apri qualsiasi app. Con la freccia della barra la riduci a una linguetta quasi invisibile al bordo; toccala per riaprire la barra (o usa «Barra» nella notifica).")
                    .apply { layoutParams = ui.lp(14) },
                stepRow(3, "Temporanei: il tratto svanisce da solo. Permanenti: restano finché non li cancelli.")
                    .apply { layoutParams = ui.lp(14) },
                stepRow(4, "Tocca il tasto rosso nella barra o nella notifica per fermare: il video è già in galleria.")
                    .apply { layoutParams = ui.lp(14) },
            ),
            ui.lp(18)
        )

        // --- Limiti (comprimibile)
        val limitsBody = ui.text(getString(R.string.limits_body), 13f, R.color.ink_muted)
            .apply { visibility = View.GONE }
        val chevron = ui.icon(R.drawable.ic_expand, R.color.ink_muted, 24)
        val limitsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(getString(R.string.limits_title), 15f, bold = true), ui.weight())
            addView(chevron)
        }
        val limitsCard = ui.card(limitsHeader, limitsBody.apply { layoutParams = ui.lp(10) })
        limitsCard.isClickable = true
        limitsCard.setOnClickListener {
            val open = limitsBody.visibility != View.VISIBLE
            limitsBody.visibility = if (open) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 180f else 0f
        }
        column.addView(limitsCard, ui.lp(18))
    }

    /** Aiuto per l'interruttore grigio (impostazioni con restrizioni): chiuso finche' non serve. */
    private fun buildOverlayHelp(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(
                ui.text(
                    "Android blocca questo permesso alle app installate da un file APK. Per sbloccarlo, in quest'ordine:\n" +
                        "1. Premi «Attiva» qui sopra\n" +
                        "2. Nella pagina tocca l'interruttore grigio e poi OK: senza questo passaggio il menu giusto non compare\n" +
                        "3. Torna indietro e tocca «Apri info app» qui sotto\n" +
                        "4. Tocca i tre puntini in alto a destra (non l'icona «apri app»)\n" +
                        "5. Scegli «Consenti impostazioni con restrizioni» e conferma\n" +
                        "6. Torna qui e premi «Attiva» di nuovo",
                    13f, R.color.on_primary_container
                )
            )
            addView(ui.button("Apri info app", filled = true) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, ui.lp(12, w = ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val chevron = ui.icon(R.drawable.ic_expand, R.color.on_primary_container, 22)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            addView(ui.icon(R.drawable.ic_info, R.color.on_primary_container, 20).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(10) }
            })
            addView(ui.text("L'interruttore è grigio? Tocca qui", 14f, R.color.on_primary_container, bold = true), ui.weight())
            addView(chevron)
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                chevron.rotation = if (open) 180f else 0f
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.bg(ui.c(R.color.primary_container), 18)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            addView(header)
            addView(body, ui.lp(10))
        }
    }

    // ------------------------------------------------------------------ Stato

    private fun audioLabel(): String = when (Prefs.audioMode) {
        0 -> "Senza audio"
        1 -> "Microfono"
        2 -> "Audio del telefono"
        else -> "Microfono e telefono"
    }

    private fun refresh() {
        val rec = RecorderService.recording
        val overlayOk = Settings.canDrawOverlays(this)
        val micWanted = Prefs.audioMode != 0
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val secureOk = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        // Pulsante principale
        heroDisc.background = ui.oval(ui.c(if (rec) R.color.rec_red else R.color.brand_orange))
        heroIcon.setImageResource(if (rec) R.drawable.ic_stop else R.drawable.ic_record)
        heroIcon.imageTintList = ColorStateList.valueOf(ui.c(if (rec) R.color.bg else R.color.ink_on_orange))
        heroButton.contentDescription = getString(if (rec) R.string.stop_recording else R.string.start_recording)
        (ring.background as GradientDrawable).setStroke(ui.dp(3), ui.c(if (rec) R.color.rec_red else R.color.brand_orange))
        heroTitle.text = when {
            rec -> "Registrazione in corso"
            overlayOk -> "Pronto a registrare"
            else -> "Quasi pronto"
        }
        heroSubtitle.text = when {
            rec -> "Tocca per fermare e salvare in galleria"
            overlayOk -> "Tocca il cerchio per iniziare"
            else -> "Completa la preparazione qui sotto"
        }
        val res = if (Prefs.resolution == 0) "Nativa" else "${Prefs.resolution}p"
        summaryText.text = "$res · ${Prefs.fps} fps · ${audioLabel()}"

        pulse?.cancel()
        ring.scaleX = 1f
        ring.scaleY = 1f
        if (rec) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f),
            ).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                start()
            }
        }

        // Preparazione
        overlayDone.visibility = if (overlayOk) View.VISIBLE else View.GONE
        overlayAction.visibility = if (overlayOk) View.GONE else View.VISIBLE
        overlayHelp.visibility = if (overlayOk) View.GONE else View.VISIBLE
        val showMic = micWanted && !micOk
        micRow.visibility = if (micWanted) View.VISIBLE else View.GONE
        micDone.visibility = if (micOk) View.VISIBLE else View.GONE
        micAction.visibility = if (showMic) View.VISIBLE else View.GONE
        setupCard.visibility = if (overlayOk && !showMic) View.GONE else View.VISIBLE
        // Tocchi nel video: il sistema li disegna solo se "Mostra tocchi" e' attivo
        val systemTouches = try { Settings.System.getInt(contentResolver, "show_touches", 0) == 1 } catch (_: Exception) { false }
        secureNote.visibility = if (Prefs.showTouches && !secureOk) View.VISIBLE else View.GONE
        secureButtons.visibility = if (systemTouches) View.GONE else View.VISIBLE
        secureNoteText.text = if (systemTouches) {
            "«Mostra tocchi» di Android è attivo: i cerchi compariranno nel video."
        } else {
            "Android disegna i cerchi nel video solo se «Mostra tocchi» è attivo nelle Opzioni sviluppatore. " +
                "Attivalo lì prima di registrare (pulsante qui sotto). Se invece dai all'app un permesso, una sola volta, dal computer, lo accende e lo spegne da sola."
        }
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Opzioni sviluppatore spente: in Info telefono tocca 7 volte «Numero build»",
                Toast.LENGTH_LONG
            ).show()
            try { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ Avvio registrazione

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun onStartClick() {
        if (RecorderService.recording) {
            startService(RecorderService.stopIntent(this))
            return
        }
        if (requesting) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Prima attiva «Mostra sopra altre app»", Toast.LENGTH_LONG).show()
            scroll.post { scroll.smoothScrollTo(0, setupCard.top) }
            return
        }
        val needed = ArrayList<String>()
        if (Prefs.audioMode != 0 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)

        if (needed.isEmpty()) {
            requestProjection()
        } else {
            requesting = true
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Da Android 14 si forza "Intero schermo": con "Una singola app" gli overlay non verrebbero registrati
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        requesting = true
        try {
            projectionLauncher.launch(intent)
        } catch (_: Exception) {
            requesting = false
            Toast.makeText(this, "Impossibile avviare la registrazione", Toast.LENGTH_LONG).show()
        }
    }
}
