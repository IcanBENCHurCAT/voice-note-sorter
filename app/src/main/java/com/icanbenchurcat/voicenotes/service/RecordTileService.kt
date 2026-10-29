package com.icanbenchurcat.voicenotes.service

import android.content.Intent
import android.service.quicksettings.TileService
import com.icanbenchurcat.voicenotes.MainActivity

/** Quick Settings tile: one tap from anywhere into capture. The tile is
 *  the second capture surface after the Glance widget; both exist
 *  because getting to the recorder must cost under two seconds. */
class RecordTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(MainActivity.EXTRA_START_RECORDING, true)
        }
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
