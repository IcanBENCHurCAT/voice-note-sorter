// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.icanbenchurcat.voicenotes.MainActivity
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.service.SortingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service managing active audio recording sessions.
 * Holds microphone foreground service type, shows a persistent notification
 * with elapsed time, pause/resume, and stop controls.
 * Implements a 4-minute warning and 5-minute hard stop limit.
 * Upon stop, persists a Note entity and enqueues SortingWorker.
 */
class RecordingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtEpochMs: Long = 0L
    private var elapsedSeconds: Long = 0L
    private var isPaused: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> handleStart()
            ACTION_PAUSE_RECORDING -> handlePause()
            ACTION_RESUME_RECORDING -> handleResume()
            ACTION_STOP_RECORDING -> handleStop()
            ACTION_CANCEL_RECORDING -> handleCancel()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        if (_sessionState.value is CaptureSessionState.Recording) return

        try {
            outputFile = AudioFileManager.createAudioOutputFile(this)
            startedAtEpochMs = System.currentTimeMillis()
            elapsedSeconds = 0L
            isPaused = false

            val fixture = fixtureAudioBytesOverride
            if (fixture != null) {
                outputFile?.writeBytes(fixture)
            } else {
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                    setOutputFile(outputFile?.absolutePath)
                    prepare()
                    start()
                }
            }

            val notification = buildNotification(isPaused = false, elapsedSec = 0L, isWarning = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            _sessionState.value = CaptureSessionState.Recording(
                audioPath = outputFile?.absolutePath ?: "",
                startedAtEpochMs = startedAtEpochMs,
                elapsedSeconds = 0L,
                isPaused = false,
                showWarning4Min = false
            )

            startTimer()
        } catch (e: Exception) {
            _sessionState.value = CaptureSessionState.Error("Failed to start recording: ${e.message}")
            cleanupRecorder()
            stopSelf()
        }
    }

    private fun handlePause() {
        val current = _sessionState.value as? CaptureSessionState.Recording ?: return
        if (current.isPaused) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                recorder?.pause()
            }
            isPaused = true
            timerJob?.cancel()

            _sessionState.value = current.copy(isPaused = true)
            updateNotification(isPaused = true, elapsedSec = elapsedSeconds, isWarning = current.showWarning4Min)
        } catch (e: Exception) {
            // Log or handle pause error
        }
    }

    private fun handleResume() {
        val current = _sessionState.value as? CaptureSessionState.Recording ?: return
        if (!current.isPaused) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                recorder?.resume()
            }
            isPaused = false
            _sessionState.value = current.copy(isPaused = false)
            updateNotification(isPaused = false, elapsedSec = elapsedSeconds, isWarning = current.showWarning4Min)
            startTimer()
        } catch (e: Exception) {
            // Log or handle resume error
        }
    }

    private fun handleStop() {
        val current = _sessionState.value as? CaptureSessionState.Recording
        timerJob?.cancel()

        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Stop failed if recording was too brief
        } finally {
            cleanupRecorder()
        }

        val path = outputFile?.absolutePath
        val recordedFile = outputFile
        if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0 && path != null) {
            serviceScope.launch(Dispatchers.IO) {
                val db = AppDatabase.get(applicationContext)
                val note = Note(
                    createdAtEpochMs = startedAtEpochMs,
                    audioPath = path,
                    title = "",
                    transcript = "",
                    summary = "",
                    bucketKind = "INBOX",
                    status = NoteStatus.RECORDED.name,
                    tierUsed = "CORE",
                    stageStatus = ""
                )
                val noteId = db.noteDao().insert(note)

                // Enqueue SortingWorker
                val workRequest = OneTimeWorkRequestBuilder<SortingWorker>()
                    .setInputData(workDataOf(SortingWorker.KEY_NOTE_ID to noteId))
                    .build()
                WorkManager.getInstance(applicationContext).enqueue(workRequest)

                _sessionState.value = CaptureSessionState.Completed(
                    noteId = noteId,
                    audioPath = path
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            _sessionState.value = CaptureSessionState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleCancel() {
        timerJob?.cancel()
        try {
            recorder?.stop()
        } catch (e: Exception) {
        } finally {
            cleanupRecorder()
        }

        // Clean up output file
        outputFile?.let {
            if (it.exists()) it.delete()
        }
        outputFile = null

        _sessionState.value = CaptureSessionState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                elapsedSeconds++
                val isWarning = elapsedSeconds >= WARNING_LIMIT_SECONDS
                val current = _sessionState.value as? CaptureSessionState.Recording
                if (current != null) {
                    _sessionState.value = current.copy(
                        elapsedSeconds = elapsedSeconds,
                        showWarning4Min = isWarning
                    )
                }

                updateNotification(isPaused = isPaused, elapsedSec = elapsedSeconds, isWarning = isWarning)

                // 5-minute hard stop
                if (elapsedSeconds >= HARD_LIMIT_SECONDS) {
                    handleStop()
                    break
                }
            }
        }
    }

    private fun cleanupRecorder() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
        } finally {
            recorder = null
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_recording_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_recording_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(isPaused: Boolean, elapsedSec: Long, isWarning: Boolean): Notification {
        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        val timeString = String.format("%02d:%02d", minutes, seconds)

        val title = if (isWarning) {
            getString(R.string.notif_recording_warning_title)
        } else if (isPaused) {
            getString(R.string.notif_recording_paused_title)
        } else {
            getString(R.string.notif_recording_active_title)
        }

        val contentText = if (isWarning) {
            getString(R.string.notif_recording_warning_desc, timeString)
        } else {
            getString(R.string.notif_recording_elapsed, timeString)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = if (isPaused) {
            val resumeIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, RecordingService::class.java).apply { action = ACTION_RESUME_RECORDING },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                getString(R.string.action_resume_recording),
                resumeIntent
            ).build()
        } else {
            val pauseIntent = PendingIntent.getService(
                this,
                3,
                Intent(this, RecordingService::class.java).apply { action = ACTION_PAUSE_RECORDING },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_pause_recording),
                pauseIntent
            ).build()
        }

        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_notification_clear_all,
            getString(R.string.action_stop_recording),
            stopIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_tile_record)
            .setContentIntent(contentIntent)
            .setOngoing(!isPaused)
            .addAction(pauseResumeAction)
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification(isPaused: Boolean, elapsedSec: Long, isWarning: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isPaused, elapsedSec, isWarning))
    }

    companion object {
        const val CHANNEL_ID = "recording_session_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START_RECORDING = "com.icanbenchurcat.voicenotes.action.START_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.icanbenchurcat.voicenotes.action.PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.icanbenchurcat.voicenotes.action.RESUME_RECORDING"
        const val ACTION_STOP_RECORDING = "com.icanbenchurcat.voicenotes.action.STOP_RECORDING"
        const val ACTION_CANCEL_RECORDING = "com.icanbenchurcat.voicenotes.action.CANCEL_RECORDING"

        const val WARNING_LIMIT_SECONDS = 240L // 4 minutes
        const val HARD_LIMIT_SECONDS = 300L // 5 minutes

        private val _sessionState = MutableStateFlow<CaptureSessionState>(CaptureSessionState.Idle)
        val sessionState: StateFlow<CaptureSessionState> = _sessionState.asStateFlow()

        /**
         * Optional fixture audio bytes for deterministic end-to-end instrumentation tests
         * so tests do not depend on a live hardware/emulator microphone.
         */
        @Volatile
        var fixtureAudioBytesOverride: ByteArray? = null
    }
}
