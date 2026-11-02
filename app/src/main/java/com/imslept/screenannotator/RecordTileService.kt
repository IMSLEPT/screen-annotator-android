package com.imslept.screenannotator

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tile nelle impostazioni rapide: avvia o interrompe la registrazione con un tocco. */
class RecordTileService : TileService() {

    private val listener: () -> Unit = { update() }

    override fun onStartListening() {
        RecorderService.addListener(listener)
        update()
    }

    override fun onStopListening() {
        RecorderService.removeListener(listener)
    }

    override fun onClick() {
        if (RecorderService.recording) {
            startService(RecorderService.stopIntent(this))
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_AUTO_START, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        tile.state = if (RecorderService.recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }
}
