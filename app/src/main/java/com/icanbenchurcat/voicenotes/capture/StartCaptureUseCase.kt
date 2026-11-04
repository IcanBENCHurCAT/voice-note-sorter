// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Shared use case to initiate note capture from any surface (FAB, Widget, Quick Settings Tile).
 * Verifies or delegates permission checks, launches the recording foreground service,
 * and standardizes entry into the active recording flow.
 */
class StartCaptureUseCase(private val context: Context) {

    fun startRecording(): Boolean {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
        }
        try {
            ContextCompat.startForegroundService(context, intent)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun pauseRecording() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_PAUSE_RECORDING
        }
        context.startService(intent)
    }

    fun resumeRecording() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_RESUME_RECORDING
        }
        context.startService(intent)
    }

    fun stopRecording() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        context.startService(intent)
    }

    fun cancelRecording() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_CANCEL_RECORDING
        }
        context.startService(intent)
    }
}
