// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.icanbenchurcat.voicenotes.MainActivity
import com.icanbenchurcat.voicenotes.R

/**
 * Helper for building RecordingService notification adhering to Android 16/17 Live Updates specs.
 */
object RecordingNotificationBuilder {

    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun buildRecordingNotification(
        context: Context,
        isPaused: Boolean,
        elapsedSec: Long,
        isWarning: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Notification {
        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        val timeString = String.format("%02d:%02d", minutes, seconds)

        val title = if (isWarning) {
            context.getString(R.string.notif_recording_warning_title)
        } else if (isPaused) {
            context.getString(R.string.notif_recording_paused_title)
        } else {
            context.getString(R.string.notif_recording_active_title)
        }

        val contentText = if (isWarning) {
            context.getString(R.string.notif_recording_warning_desc, timeString)
        } else {
            context.getString(R.string.notif_recording_elapsed, timeString)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = PendingIntent.getService(
            context,
            if (isPaused) 2 else 3,
            Intent(context, RecordingService::class.java).apply {
                action = if (isPaused) RecordingService.ACTION_RESUME_RECORDING else RecordingService.ACTION_PAUSE_RECORDING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = NotificationCompat.Action.Builder(
            if (isPaused) R.drawable.ic_notif_play else R.drawable.ic_notif_pause,
            context.getString(if (isPaused) R.string.action_resume_recording else R.string.action_pause_recording),
            pauseResumeIntent
        ).build()

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notif_stop,
            context.getString(R.string.action_stop_recording),
            stopIntent
        ).build()

        val cancelIntent = PendingIntent.getService(
            context,
            4,
            Intent(context, RecordingService::class.java).apply { action = RecordingService.ACTION_CANCEL_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notif_cancel,
            context.getString(R.string.action_cancel_recording),
            cancelIntent
        ).build()

        val builder = NotificationCompat.Builder(context, RecordingService.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_tile_record)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setWhen(nowEpochMs - elapsedSec * 1000L)
            .setUsesChronometer(!isPaused)
            .setShortCriticalText(if (isPaused) context.getString(R.string.notif_chip_paused) else timeString)
            .addAction(pauseResumeAction)
            .addAction(stopAction)
            .addAction(cancelAction)

        try {
            val method = builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
            method.invoke(builder, true)
        } catch (e: Exception) {
            // Fallback or reflection if method not in current SDK stubs
        }

        builder.addExtras(android.os.Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        })

        return builder.build()
    }
}
