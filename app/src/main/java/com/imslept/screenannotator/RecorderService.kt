package com.imslept.screenannotator

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/** Servizio in primo piano: tiene viva la registrazione e gli overlay anche quando usi altre app. */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.imslept.screenannotator.START"
        const val ACTION_STOP = "com.imslept.screenannotator.STOP"
        const val ACTION_TOGGLE_DRAW = "com.imslept.screenannotator.TOGGLE_DRAW"
        const val ACTION_TOGGLE_BAR = "com.imslept.screenannotator.TOGGLE_BAR"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 42

        @Volatile
        var recording = false
            private set

        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        /** Notifica sul thread principale quando lo stato cambia (usato da schermata e tile). */
        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyChanged() { for (l in listeners) l() }

        fun startIntent(ctx: Context, resultCode: Int, data: Intent): Intent =
            Intent(ctx, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP)
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var overlay: OverlayController? = null
    private var stopping = false
    private var previousShowTouches = -1
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            // dalla notifica: se la registrazione non c'e' piu' il servizio non deve restare acceso
            ACTION_TOGGLE_DRAW -> if (recording) overlay?.toggleDrawing() else if (!stopping) stopSelf()
            ACTION_TOGGLE_BAR -> if (recording) overlay?.toggleBar() else if (!stopping) stopSelf()
            else -> if (!recording) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ Avvio

    private fun startRecording(intent: Intent) {
        if (recording || stopping) return

        // Anche l'audio del dispositivo richiede il permesso di registrazione audio
        val mic = Prefs.audioMode != 0 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startedAt = System.currentTimeMillis()
        startForeground(NOTIF_ID, buildNotification(), type)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            fail("Autorizzazione di registrazione mancante")
            return
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
            // Obbligatorio da Android 14: il callback va registrato prima di creare il display virtuale
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopRecording() }
                }
            }, main)
            projection = proj

            setShowTouches(Prefs.showTouches)

            if (Settings.canDrawOverlays(this)) {
                overlay = OverlayController(
                    this,
                    onStopRequested = { stopRecording() },
                    onDrawingChanged = { main.post { refreshNotification() } },
                ).also { it.show() }
                refreshNotification()
            } else {
                Toast.makeText(this, R.string.perm_overlay_body, Toast.LENGTH_LONG).show()
            }

            val rec = ScreenRecorder(
                this, proj,
                onWarn = { msg -> main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } },
                onError = { msg ->
                    main.post {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        stopRecording()
                    }
                },
            )
            rec.start()
            recorder = rec
            recording = true
            notifyChanged()
        } catch (e: Exception) {
            fail(getString(R.string.error_generic) + ": " + e.message)
        }
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        cleanup()
        recording = false
        notifyChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ Stop

    private fun stopRecording() {
        if (stopping) return
        if (!recording) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        stopping = true
        recording = false
        overlay?.destroy()
        overlay = null
        restoreShowTouches()
        notifyChanged()

        val rec = recorder
        val proj = projection
        Thread {
            val uri = try { rec?.stop() } catch (_: Exception) { null }
            try { proj?.stop() } catch (_: Exception) {}
            main.post {
                if (uri != null) {
                    Toast.makeText(this, R.string.saved_to_gallery, Toast.LENGTH_LONG).show()
                }
                recorder = null
                projection = null
                stopping = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun cleanup() {
        overlay?.destroy()
        overlay = null
        restoreShowTouches()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        recorder = null
    }

    override fun onDestroy() {
        if (recording) {
            recording = false
            val rec = recorder
            Thread { try { rec?.stop() } catch (_: Exception) {} }.start()
        }
        cleanup()
        notifyChanged()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Tocchi di sistema

    /** Funziona solo dopo: adb shell pm grant <pacchetto> android.permission.WRITE_SECURE_SETTINGS */
    private fun setShowTouches(on: Boolean) {
        if (!on) return // se l'opzione e' spenta non tocco l'impostazione di sistema
        try {
            previousShowTouches = Settings.System.getInt(contentResolver, "show_touches", 0)
            Settings.System.putInt(contentResolver, "show_touches", if (on) 1 else 0)
        } catch (_: Exception) {
            previousShowTouches = -1
        }
    }

    private fun restoreShowTouches() {
        if (previousShowTouches < 0) return
        try {
            Settings.System.putInt(contentResolver, "show_touches", previousShowTouches)
        } catch (_: Exception) {
        }
        previousShowTouches = -1
    }

    // ------------------------------------------------------------------ Notifica

    private fun refreshNotification() {
        if (stopping || (!recording && overlay == null)) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        fun action(code: Int, name: String) = PendingIntent.getService(
            this, code, Intent(this, RecorderService::class.java).setAction(name), flags
        )
        val drawing = overlay?.isDrawing == true
        val side = if (Prefs.toolbarCorner % 2 == 1) "destro" else "sinistro"
        val status = buildString {
            append(if (drawing) "Disegno attivo" else "Tocco attivo")
            if (Prefs.hideBar) append(" · linguetta della barra sul bordo $side")
        }
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.recording_in_progress))
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(if (startedAt > 0) startedAt else System.currentTimeMillis())
            .setColor(0xFFD97757.toInt())
        if (overlay != null) {
            // l'azione dice cosa succede premendola: se sto disegnando, torno al tocco
            b.addAction(Notification.Action.Builder(null, if (drawing) "Tocco" else "Disegno", action(2, ACTION_TOGGLE_DRAW)).build())
            b.addAction(Notification.Action.Builder(null, "Barra", action(3, ACTION_TOGGLE_BAR)).build())
        }
        b.addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), action(1, ACTION_STOP)).build())
        return b.build()
    }
}
