// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private object AudioDurationCache {
    val map: MutableMap<String, Int> = ConcurrentHashMap()
}

internal fun readAudioDurationMs(path: String): Int? {
    if (path.isBlank()) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Throwable) {
        }
    }
}

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

interface AudioFocusController {
    fun requestFocus(onFocusLost: () -> Unit): Boolean
    fun abandonFocus()
}

object NoOpAudioFocusController : AudioFocusController {
    override fun requestFocus(onFocusLost: () -> Unit): Boolean = true
    override fun abandonFocus() {}
}

class AndroidAudioFocusController(context: Context) : AudioFocusController {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var activeRequest: AudioReferenceRequest? = null

    private class AudioReferenceRequest(val request: AudioFocusRequest)

    override fun requestFocus(onFocusLost: () -> Unit): Boolean {
        val am = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                    focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) {
                    onFocusLost()
                }
            }
            .build()

        val res = am.requestAudioFocus(request)
        val granted = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (granted) {
            activeRequest = AudioReferenceRequest(request)
        }
        return granted
    }

    override fun abandonFocus() {
        val am = audioManager ?: return
        activeRequest?.let {
            am.abandonAudioFocusRequest(it.request)
        }
        activeRequest = null
    }
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
    private val focusController: AudioFocusController = NoOpAudioFocusController,
    private val fileExistsChecker: (String) -> Boolean = { path ->
        path.isNotBlank() && File(path).let { it.exists() && it.isFile && it.length() > 0L }
    },
    private val durationReader: (String) -> Int? = { path -> readAudioDurationMs(path) },
    private val durationCache: MutableMap<String, Int>? = null
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
        if (checkFileAvailability()) {
            loadDurationIfNeeded()
        }
    }

    private fun loadDurationIfNeeded() {
        if (durationMs > 0 || !isFileAvailable) return
        val cached = durationCache?.get(audioPath)
        if (cached != null && cached > 0) {
            durationMs = cached
            return
        }
        val read = try {
            durationReader(audioPath)
        } catch (_: Throwable) {
            null
        }
        if (read != null && read > 0) {
            durationMs = read
            durationCache?.put(audioPath, read)
        }
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
                val preparedDuration = client.duration
                if (preparedDuration > 0) {
                    durationMs = preparedDuration
                    durationCache?.put(audioPath, preparedDuration)
                }
                client.setOnCompletionListener {
                    focusController.abandonFocus()
                    status = PlaybackStatus.COMPLETED
                    currentPositionMs = durationMs
                    stopProgressTracking()
                }
                client.setOnErrorListener { _, _ ->
                    focusController.abandonFocus()
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
            if (!focusController.requestFocus(onFocusLost = { pause() })) {
                status = PlaybackStatus.PAUSED
                return
            }

            if (status == PlaybackStatus.COMPLETED) {
                activePlayer.seekTo(0)
                currentPositionMs = 0
            }
            activePlayer.start()
            status = PlaybackStatus.PLAYING
            startProgressTracking()
        } catch (e: Exception) {
            focusController.abandonFocus()
            status = PlaybackStatus.ERROR
            errorMessage = "Playback failed"
            stopProgressTracking()
        }
    }

    fun pause() {
        focusController.abandonFocus()
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
        focusController.abandonFocus()
        stopProgressTracking()
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        status = PlaybackStatus.IDLE
        currentPositionMs = 0
    }

    fun release() {
        focusController.abandonFocus()
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
    val context = LocalContext.current
    val focusController = remember(context) { AndroidAudioFocusController(context) }
    val coroutineScope = rememberCoroutineScope()
    val state = remember(audioPath, focusController) {
        AudioPlaybackState(
            audioPath = audioPath,
            coroutineScope = coroutineScope,
            playerFactory = playerFactory,
            focusController = focusController,
            fileExistsChecker = fileExistsChecker,
            durationCache = AudioDurationCache.map
        )
    }

    val recordingState by com.icanbenchurcat.voicenotes.capture.RecordingService.sessionState.collectAsState()
    LaunchedEffect(recordingState) {
        if (recordingState is com.icanbenchurcat.voicenotes.capture.CaptureSessionState.Recording &&
            state.status == PlaybackStatus.PLAYING
        ) {
            state.pause()
        }
    }

    DisposableEffect(audioPath) {
        onDispose {
            state.release()
        }
    }

    return state
}
