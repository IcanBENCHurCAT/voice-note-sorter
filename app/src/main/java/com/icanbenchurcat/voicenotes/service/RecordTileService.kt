// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.icanbenchurcat.voicenotes.MainActivity
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.capture.CaptureSessionState
import com.icanbenchurcat.voicenotes.capture.RecordingService
import com.icanbenchurcat.voicenotes.capture.StartCaptureUseCase

/** Quick Settings tile: one tap from anywhere into capture. The tile is
 *  the second capture surface after the Glance widget; both exist
 *  because getting to the recorder must cost under two seconds. */
class RecordTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        if (RecordingService.sessionState.value is CaptureSessionState.Recording) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.notif_recording_active_title)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_label)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (RecordingService.sessionState.value is CaptureSessionState.Recording) {
            StartCaptureUseCase(this).stopRecording()
            qsTile?.let {
                it.state = Tile.STATE_INACTIVE
                it.label = getString(R.string.tile_label)
                it.updateTile()
            }
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_START_RECORDING, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
