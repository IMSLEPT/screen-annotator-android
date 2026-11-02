package com.imslept.screenannotator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

    private lateinit var startButton: MaterialButton
    private lateinit var overlayCard: View
    private val stateListener: () -> Unit = { runOnUiThread { refresh() } }

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
        super.onPause()
    }

    // ------------------------------------------------------------------ Interfaccia

    private fun text(content: String, style: Int): TextView = TextView(this).apply {
        text = content
        setTextAppearance(style)
    }

    private fun buildUi() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        column.addView(text(getString(R.string.app_name), com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium))

        // Pulsante principale
        startButton = MaterialButton(this).apply {
            textSize = 18f
            setOnClickListener { onStartClick() }
        }
        column.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(64)
        ).apply { topMargin = dp(20) })

        // Permesso overlay
        overlayCard = MaterialCardView(this).apply {
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(text(getString(R.string.perm_overlay_title), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium))
                addView(text(getString(R.string.perm_overlay_body), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium))
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.perm_grant)
                    setOnClickListener { openOverlaySettings() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            addView(inner)
        }
        column.addView(overlayCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        // Sorgente audio
        column.addView(text(getString(R.string.audio_source), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium).apply {
            setPadding(0, dp(24), 0, dp(8))
        })
        val chips = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val options = listOf(
            0 to R.string.audio_none, 1 to R.string.audio_mic,
            2 to R.string.audio_device, 3 to R.string.audio_both,
        )
        for ((mode, label) in options) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = getString(label)
                isCheckable = true
                isChecked = Prefs.audioMode == mode
                setOnClickListener { Prefs.audioMode = mode }
            }
            chips.addView(chip)
        }
        column.addView(chips)

        // Tocchi
        val touches = MaterialSwitch(this).apply {
            text = getString(R.string.show_touches)
            isChecked = Prefs.showTouches
            setOnCheckedChangeListener { _, checked -> Prefs.showTouches = checked }
        }
        column.addView(touches, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        column.addView(text(getString(R.string.show_touches_hint), com.google.android.material.R.style.TextAppearance_Material3_BodySmall))

        // Impostazioni
        column.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.settings)
            setOnClickListener { startActivity(Intent(context, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        // Limiti tecnici
        column.addView(text(getString(R.string.limits_title), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium).apply {
            setPadding(0, dp(28), 0, dp(6))
        })
        column.addView(text(getString(R.string.limits_body), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium))
    }

    private fun refresh() {
        val rec = RecorderService.recording
        startButton.text = getString(if (rec) R.string.stop_recording else R.string.start_recording)
        overlayCard.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
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
            openOverlaySettings()
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
