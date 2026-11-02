package com.imslept.screenannotator

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
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
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }

    private lateinit var scroll: ScrollView
    private lateinit var ring: View
    private lateinit var heroButton: FrameLayout
    private lateinit var heroIcon: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var setupCard: View
    private lateinit var overlayDone: ImageView
    private lateinit var overlayAction: MaterialButton
    private lateinit var overlayHelp: View
    private lateinit var micRow: View
    private lateinit var micDone: ImageView
    private lateinit var micAction: MaterialButton
    private var pulse: ObjectAnimator? = null

    private val stateListener: () -> Unit = { runOnUiThread { refresh() } }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Anche senza microfono si puo' registrare: l'audio viene semplicemente escluso
            requestProjection()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this, RecorderService.startIntent(this, res.resultCode, data)
                )
                moveTaskToBack(true) // torna alla schermata precedente: si e' pronti a registrare
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

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

    // ------------------------------------------------------------------ Mattoncini grafici

    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun label(text: String, style: Int, muted: Boolean = false, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextAppearance(style)
            setTextColor(color(if (muted) R.color.ink_muted else R.color.ink))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(vararg children: View, pad: Int = 18, tint: Int = R.color.card): MaterialCardView =
        MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(tint))
            strokeWidth = dp(1)
            strokeColor = color(R.color.card_variant)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
                for (c in children) addView(c)
            })
        }

    private fun sectionTitle(text: String) =
        label(text, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, bold = true)
            .apply { setPadding(dp(4), 0, 0, dp(8)) }

    private fun icon(@DrawableRes res: Int, tint: Int, size: Int = 24) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun circleBg(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    /** Riga della checklist: icona, titolo, descrizione e a destra spunta o pulsante. */
    private fun setupRow(
        @DrawableRes iconRes: Int, title: String, subtitle: String, done: ImageView, action: MaterialButton,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(FrameLayout(context).apply {
            background = circleBg(color(R.color.primary_container))
            addView(icon(iconRes, color(R.color.on_primary_container), 22).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            })
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, bold = true))
            addView(label(subtitle, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
        addView(done)
        addView(action)
    }

    private fun stepRow(n: Int, text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            this.text = n.toString()
            gravity = Gravity.CENTER
            setTextColor(color(R.color.ink_on_orange))
            setTypeface(typeface, Typeface.BOLD)
            background = circleBg(color(R.color.brand_orange))
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(label(text, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }

    // ------------------------------------------------------------------ Interfaccia

    private fun buildUi() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg))
            isFillViewport = true
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
                addView(label(getString(R.string.app_name), com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium, bold = true))
                addView(label("Registra lo schermo e disegna in diretta", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, muted = true))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(FrameLayout(context).apply {
                background = circleBg(color(R.color.card_variant))
                isClickable = true
                setOnClickListener { startActivity(Intent(context, SettingsActivity::class.java)) }
                contentDescription = getString(R.string.settings)
                addView(icon(R.drawable.ic_settings, color(R.color.ink), 24).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
                })
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }, lp())

        // --- Pulsante di registrazione
        ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke(dp(3), color(R.color.brand_orange))
                alpha = 90
            }
        }
        heroIcon = icon(R.drawable.ic_record, color(R.color.ink_on_orange), 64)
        val disc = FrameLayout(this).apply {
            background = circleBg(color(R.color.brand_orange))
            elevation = dp(6).toFloat()
            addView(heroIcon.apply { layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER) })
        }
        heroButton = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { onStartClick() }
            addView(ring, FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER))
            addView(disc, FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER))
        }
        heroTitle = label("", com.google.android.material.R.style.TextAppearance_Material3_TitleLarge, bold = true)
            .apply { gravity = Gravity.CENTER }
        heroSubtitle = label("", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, muted = true)
            .apply { gravity = Gravity.CENTER }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(heroButton, LinearLayout.LayoutParams(dp(176), dp(176)))
            addView(heroTitle, lp(14))
            addView(heroSubtitle, lp(2))
        }, lp(24))

        // --- Preparazione (sparisce quando tutto e' a posto)
        overlayDone = icon(R.drawable.ic_check, color(R.color.brand_green), 28)
        overlayAction = MaterialButton(this).apply {
            text = "Attiva"
            setOnClickListener { openOverlaySettings() }
        }
        micDone = icon(R.drawable.ic_check, color(R.color.brand_green), 28)
        micAction = MaterialButton(this).apply {
            text = "Attiva"
            setOnClickListener { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        }
        micRow = setupRow(R.drawable.ic_mic, "Microfono", "Per registrare la tua voce", micDone, micAction)

        overlayHelp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(color(R.color.primary_container))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label("Interruttore grigio e non si attiva?", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, bold = true)
                .apply { setTextColor(color(R.color.on_primary_container)) })
            addView(label(
                "Android blocca questo permesso alle app installate da fuori dal Play Store. Per sbloccarlo:\n" +
                    "1. Tocca «Apri info app» qui sotto\n" +
                    "2. Tocca i tre puntini in alto a destra\n" +
                    "3. Scegli «Consenti impostazioni con restrizioni» e conferma\n" +
                    "4. Torna qui e premi «Attiva»",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall
            ).apply { setTextColor(color(R.color.on_primary_container)) }, lp(4))
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Apri info app"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }, lp(8, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setupCard = card(
            label("Prepara l'app", com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, bold = true),
            setupRow(R.drawable.ic_overlay, "Mostra sopra altre app", "Serve per disegnare sopra le altre app", overlayDone, overlayAction)
                .apply { layoutParams = lp(12) },
            overlayHelp.apply { layoutParams = lp(10) },
            micRow.apply { layoutParams = lp(14) },
        )
        column.addView(setupCard, lp(8))

        // --- Cosa registrare
        column.addView(sectionTitle("Cosa registrare"), lp(24))
        val chips = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        for ((mode, name) in listOf(0 to R.string.audio_none, 1 to R.string.audio_mic, 2 to R.string.audio_device, 3 to R.string.audio_both)) {
            chips.addView(Chip(this).apply {
                id = View.generateViewId()
                text = getString(name)
                isCheckable = true
                isChecked = Prefs.audioMode == mode
                setOnClickListener {
                    Prefs.audioMode = mode
                    refresh()
                }
            })
        }
        val touches = MaterialSwitch(this).apply {
            text = getString(R.string.show_touches)
            isChecked = Prefs.showTouches
            setOnCheckedChangeListener { _, c -> Prefs.showTouches = c }
        }
        column.addView(card(
            label(getString(R.string.audio_source), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, muted = true),
            chips,
            touches.apply { layoutParams = lp(8) },
            label(getString(R.string.show_touches_hint), com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true),
        ), lp())

        // --- Come funziona
        column.addView(sectionTitle("Come funziona"), lp(24))
        column.addView(card(
            stepRow(1, "Tocca il cerchio e scegli «Intero schermo» nella finestra di sistema."),
            stepRow(2, "Apri qualsiasi app: la barra a lato ti fa disegnare, scrivere e cerchiare.").apply { layoutParams = lp(14) },
            stepRow(3, "In modalità temporanea il tratto svanisce da solo; in quella permanente resta finché non lo cancelli.").apply { layoutParams = lp(14) },
            stepRow(4, "Tocca ■ nella barra o nella notifica per fermare: il video è già in galleria.").apply { layoutParams = lp(14) },
        ), lp())

        // --- Limiti (comprimibile)
        val limitsBody = label(getString(R.string.limits_body), com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true)
            .apply { visibility = View.GONE }
        val chevron = icon(R.drawable.ic_expand, color(R.color.ink_muted), 24)
        val limitsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(getString(R.string.limits_title), com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron)
        }
        val limitsCard = card(limitsHeader, limitsBody.apply { layoutParams = lp(10) })
        limitsCard.isClickable = true
        limitsCard.setOnClickListener {
            val open = limitsBody.visibility != View.VISIBLE
            limitsBody.visibility = if (open) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 180f else 0f
        }
        column.addView(limitsCard, lp(24))
    }

    private fun refresh() {
        val rec = RecorderService.recording
        val overlayOk = Settings.canDrawOverlays(this)
        val micWanted = Prefs.audioMode == 1 || Prefs.audioMode == 3
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        // Pulsante principale
        val disc = (heroButton.getChildAt(1) as FrameLayout)
        disc.background = circleBg(color(if (rec) R.color.rec_red else R.color.brand_orange))
        heroIcon.setImageResource(if (rec) R.drawable.ic_stop else R.drawable.ic_record)
        heroIcon.imageTintList = ColorStateList.valueOf(color(if (rec) R.color.bg else R.color.ink_on_orange))
        (ring.background as GradientDrawable).setStroke(dp(3), color(if (rec) R.color.rec_red else R.color.brand_orange))
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

        if (needed.isEmpty()) requestProjection() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Da Android 14 si forza "Intero schermo": con "Una singola app" gli overlay non verrebbero registrati
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
}
