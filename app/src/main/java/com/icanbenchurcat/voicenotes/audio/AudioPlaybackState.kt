// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.audio

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class PlaybackStatus {
    IDLE,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR
}

fun formatDuration(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

interface AudioPlayerClient {
    fun setDataSource(path: String)
    fun prepare()
    fun start()
    fun pause()
    fun stop()
    fun release()
    fun seekTo(msec: Int)
    val isPlaying: Boolean
    val currentPosition: Int
    val duration: Int
    fun setOnCompletionListener(listener: (() -> Unit)?)
    fun setOnErrorListener(listener: ((what: Int, extra: Int) -> Boolean)?)
}

class AndroidMediaPlayerClient : AudioPlayerClient {
    private var mediaPlayer: MediaPlayer? = null

    private fun getOrCreatePlayer(): MediaPlayer {
        return mediaPlayer ?: MediaPlayer().also { mediaPlayer = it }
    }

    override fun setDataSource(path: String) {
        getOrCreatePlayer().setDataSource(path)
    }

    override fun prepare() {
        getOrCreatePlayer().prepare()
    }

    override fun start() {
        mediaPlayer?.start()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun stop() {
        mediaPlayer?.stop()
    }

    override fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun seekTo(msec: Int) {
        mediaPlayer?.seekTo(msec)
    }

    override val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    override val currentPosition: Int
        get() = mediaPlayer?.currentPosition ?: 0

    override val duration: Int
        get() = mediaPlayer?.duration ?: 0

    override fun setOnCompletionListener(listener: (() -> Unit)?) {
        mediaPlayer?.setOnCompletionListener { listener?.invoke() }
    }

    override fun setOnErrorListener(listener: ((what: Int, extra: Int) -> Boolean)?) {
        mediaPlayer?.setOnErrorListener { _, what, extra ->
            listener?.invoke(what, extra) ?: false
        }
    }
}

@Stable
class AudioPlaybackState(
    val audioPath: String,
    private val coroutineScope: CoroutineScope,
    private val playerFactory: () -> AudioPlayerClient = { AndroidMediaPlayerClient() },
    private val fileExistsChecker: (String) -> Boolean = { path ->
        path.isNotBlank() && File(path).let { it.exists() && it.isFile && it.length() > 0L }
    }
) {
    var status by mutableStateOf(PlaybackStatus.IDLE)
        private set

    var currentPositionMs by mutableIntStateOf(0)
        private set

    var durationMs by mutableIntStateOf(0)
        private set

    var isFileAvailable by mutableStateOf(true)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    val progress: Float
        get() = if (durationMs > 0) {
            (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val elapsedFormatted: String
        get() = formatDuration(currentPositionMs)

    val durationFormatted: String
        get() = formatDuration(durationMs)

    private var player: AudioPlayerClient? = null
    private var progressJob: Job? = null

    init {
        checkFileAvailability()
    }

    fun checkFileAvailability(): Boolean {
        val exists = fileExistsChecker(audioPath)
        isFileAvailable = exists
        if (!exists) {
            errorMessage = "Audio recording not available on device"
            status = PlaybackStatus.ERROR
        } else if (status == PlaybackStatus.ERROR && errorMessage == "Audio recording not available on device") {
            errorMessage = null
            status = PlaybackStatus.IDLE
        }
        return exists
    }

    private fun initPlayerIfNeeded(): Boolean {
        if (!checkFileAvailability()) {
            return false
        }

        if (player == null) {
            try {
                val client = playerFactory()
                client.setDataSource(audioPath)
                client.prepare()
                durationMs = client.duration
                client.setOnCompletionListener {
                    status = PlaybackStatus.COMPLETED
                    currentPositionMs = durationMs
                    stopProgressTracking()
                }
                client.setOnErrorListener { _, _ ->
                    status = PlaybackStatus.ERROR
                    errorMessage = "Audio playback failed"
                    stopProgressTracking()
                    true
                }
                player = client
            } catch (e: Exception) {
                status = PlaybackStatus.ERROR
                errorMessage = "Audio recording not available on device"
                release()
                return false
            }
        }
        return true
    }

    fun play() {
        if (!initPlayerIfNeeded()) {
            return
        }

        val activePlayer = player ?: return

        try {
            if (status == PlaybackStatus.COMPLETED) {
                activePlayer.seekTo(0)
                currentPositionMs = 0
            }
            activePlayer.start()
            status = PlaybackStatus.PLAYING
            startProgressTracking()
        } catch (e: Exception) {
            status = PlaybackStatus.ERROR
            errorMessage = "Playback failed"
            stopProgressTracking()
        }
    }

    fun pause() {
        val activePlayer = player ?: return
        try {
            if (activePlayer.isPlaying) {
                activePlayer.pause()
            }
            currentPositionMs = activePlayer.currentPosition
            status = PlaybackStatus.PAUSED
            stopProgressTracking()
        } catch (e: Exception) {
            status = PlaybackStatus.ERROR
            errorMessage = "Pause failed"
            stopProgressTracking()
        }
    }

    fun togglePlayPause() {
        if (status == PlaybackStatus.PLAYING) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(fraction: Float) {
        val targetMs = (fraction.coerceIn(0f, 1f) * durationMs).toInt()
        currentPositionMs = targetMs
        player?.seekTo(targetMs)
    }

    fun stop() {
        stopProgressTracking()
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        status = PlaybackStatus.IDLE
        currentPositionMs = 0
    }

    fun release() {
        stopProgressTracking()
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        if (status != PlaybackStatus.ERROR) {
            status = PlaybackStatus.IDLE
        }
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = coroutineScope.launch {
            while (isActive && status == PlaybackStatus.PLAYING) {
                player?.let { p ->
                    if (p.isPlaying) {
                        currentPositionMs = p.currentPosition
                    }
                }
                delay(100L)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }
}

@Composable
fun rememberAudioPlayerState(
    audioPath: String,
    playerFactory: () -> AudioPlayerClient = { AndroidMediaPlayerClient() },
    fileExistsChecker: (String) -> Boolean = { path ->
        path.isNotBlank() && File(path).let { it.exists() && it.isFile && it.length() > 0L }
    }
): AudioPlaybackState {
    val coroutineScope = rememberCoroutineScope()
    val state = remember(audioPath) {
        AudioPlaybackState(
            audioPath = audioPath,
            coroutineScope = coroutineScope,
            playerFactory = playerFactory,
            fileExistsChecker = fileExistsChecker
        )
    }

    DisposableEffect(audioPath) {
        onDispose {
            state.release()
        }
    }

    return state
}
