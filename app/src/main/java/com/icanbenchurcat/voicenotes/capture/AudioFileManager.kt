// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages creation and directory isolation for app-private recorded audio files.
 * Conforms to the local-only guarantee (no external or public storage leaks).
 */
object AudioFileManager {

    private const val AUDIO_DIR_NAME = "recordings"
    private const val AUDIO_EXT = ".m4a"

    fun getRecordingsDir(context: Context): File {
        val dir = File(context.filesDir, AUDIO_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun createAudioOutputFile(context: Context, timestampEpochMs: Long = System.currentTimeMillis()): File {
        val dir = getRecordingsDir(context)
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timeString = dateFormat.format(Date(timestampEpochMs))
        val filename = "recording_${timeString}_$timestampEpochMs$AUDIO_EXT"
        return File(dir, filename)
    }
}
