// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAudioPlayerClient : AudioPlayerClient {
    var prepared = false
    var started = false
    var paused = false
    var stopped = false
    var released = false
    var configuredDataSource: String? = null
    var position: Int = 0
    var playerDuration: Int = 10000

    private var completionListener: (() -> Unit)? = null
    private var errorListener: ((Int, Int) -> Boolean)? = null

    override fun setDataSource(path: String) {
        configuredDataSource = path
    }

    override fun prepare() {
        prepared = true
    }

    override fun start() {
        started = true
        paused = false
        stopped = false
    }

    override fun pause() {
        started = false
        paused = true
    }

    override fun stop() {
        started = false
        stopped = true
    }

    override fun release() {
        released = true
        started = false
    }

    override fun seekTo(msec: Int) {
        position = msec
    }

    override val isPlaying: Boolean
        get() = started && !paused && !stopped && !released

    override val currentPosition: Int
        get() = position

    override val duration: Int
        get() = playerDuration

    override fun setOnCompletionListener(listener: (() -> Unit)?) {
        completionListener = listener
    }

    override fun setOnErrorListener(listener: ((what: Int, extra: Int) -> Boolean)?) {
        errorListener = listener
    }

    fun triggerCompletion() {
        started = false
        completionListener?.invoke()
    }

    fun triggerError(what: Int = 1, extra: Int = 0): Boolean {
        return errorListener?.invoke(what, extra) ?: false
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlaybackStateTest {

    @Test
    fun formatDuration_formatsCorrectly() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(-500))
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("0:59", formatDuration(59_000))
        assertEquals("1:00", formatDuration(60_000))
        assertEquals("1:23", formatDuration(83_000))
        assertEquals("10:05", formatDuration(605_000))
    }

    @Test
    fun missingAudioFile_setsErrorStateAndFileUnavailable() {
        val testScope = TestScope()
        val fakePlayer = FakeAudioPlayerClient()
        val state = AudioPlaybackState(
            audioPath = "/non/existent/audio.m4a",
            coroutineScope = testScope,
            playerFactory = { fakePlayer },
            fileExistsChecker = { false }
        )

        assertFalse(state.isFileAvailable)
        assertEquals(PlaybackStatus.ERROR, state.status)
        assertEquals("Audio recording not available on device", state.errorMessage)
        assertFalse(fakePlayer.prepared)

        // Attempting to play does nothing and remains in error
        state.play()
        assertEquals(PlaybackStatus.ERROR, state.status)
        assertFalse(fakePlayer.started)
    }

    @Test
    fun validAudioFile_initializesIdleAndTransitionsThroughStates() = runTest {
        val fakePlayer = FakeAudioPlayerClient().apply {
            playerDuration = 120_000
        }
        val state = AudioPlaybackState(
            audioPath = "/valid/path/note.m4a",
            coroutineScope = backgroundScope,
            playerFactory = { fakePlayer },
            fileExistsChecker = { true }
        )

        assertTrue(state.isFileAvailable)
        assertEquals(PlaybackStatus.IDLE, state.status)
        assertNull(state.errorMessage)
        assertEquals("0:00", state.elapsedFormatted)

        // Play
        state.play()
        assertEquals(PlaybackStatus.PLAYING, state.status)
        assertTrue(fakePlayer.prepared)
        assertTrue(fakePlayer.started)
        assertEquals(120_000, state.durationMs)
        assertEquals("2:00", state.durationFormatted)

        // Advance progress
        fakePlayer.position = 30_000
        testScheduler.advanceTimeBy(150L)
        assertEquals(30_000, state.currentPositionMs)
        assertEquals("0:30", state.elapsedFormatted)
        assertEquals(0.25f, state.progress, 0.01f)

        // Pause
        state.pause()
        assertEquals(PlaybackStatus.PAUSED, state.status)
        assertTrue(fakePlayer.paused)
        assertEquals(30_000, state.currentPositionMs)

        // Resume with togglePlayPause
        state.togglePlayPause()
        assertEquals(PlaybackStatus.PLAYING, state.status)
        assertTrue(fakePlayer.started)

        // Stop
        state.stop()
        assertEquals(PlaybackStatus.IDLE, state.status)
        assertEquals(0, state.currentPositionMs)
        assertTrue(fakePlayer.stopped)

        // Release
        state.release()
        assertTrue(fakePlayer.released)
    }

    @Test
    fun playbackCompletion_updatesStatusAndProgress() = runTest {
        val fakePlayer = FakeAudioPlayerClient().apply {
            playerDuration = 60_000
        }
        val state = AudioPlaybackState(
            audioPath = "/valid/path/note.m4a",
            coroutineScope = backgroundScope,
            playerFactory = { fakePlayer },
            fileExistsChecker = { true }
        )

        state.play()
        assertEquals(PlaybackStatus.PLAYING, state.status)

        fakePlayer.triggerCompletion()
        assertEquals(PlaybackStatus.COMPLETED, state.status)
        assertEquals(60_000, state.currentPositionMs)
        assertEquals(1.0f, state.progress, 0.01f)

        // Replaying after completion seeks to 0 and starts playing
        state.play()
        assertEquals(PlaybackStatus.PLAYING, state.status)
        assertEquals(0, fakePlayer.position)
    }

    @Test
    fun playerError_triggersErrorState() = runTest {
        val fakePlayer = FakeAudioPlayerClient()
        val state = AudioPlaybackState(
            audioPath = "/valid/path/note.m4a",
            coroutineScope = backgroundScope,
            playerFactory = { fakePlayer },
            fileExistsChecker = { true }
        )

        state.play()
        assertEquals(PlaybackStatus.PLAYING, state.status)

        fakePlayer.triggerError()
        assertEquals(PlaybackStatus.ERROR, state.status)
        assertEquals("Audio playback failed", state.errorMessage)
    }

    @Test
    fun seekTo_updatesPosition() = runTest {
        val fakePlayer = FakeAudioPlayerClient().apply {
            playerDuration = 100_000
        }
        val state = AudioPlaybackState(
            audioPath = "/valid/path/note.m4a",
            coroutineScope = backgroundScope,
            playerFactory = { fakePlayer },
            fileExistsChecker = { true }
        )

        state.play()
        state.seekTo(0.5f)
        assertEquals(50_000, state.currentPositionMs)
        assertEquals(50_000, fakePlayer.position)
        assertEquals("0:50", state.elapsedFormatted)
    }
}
