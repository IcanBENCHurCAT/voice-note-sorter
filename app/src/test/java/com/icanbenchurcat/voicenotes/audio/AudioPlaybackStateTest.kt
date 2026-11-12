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

class FakeAudioFocusController(
    private var grantFocus: Boolean = true
) : AudioFocusController {
    var requestCount = 0
    var abandonCount = 0
    var lastOnFocusLost: (() -> Unit)? = null

    override fun requestFocus(onFocusLost: () -> Unit): Boolean {
        requestCount++
        lastOnFocusLost = onFocusLost
        return grantFocus
    }

    override fun abandonFocus() {
        abandonCount++
    }

    fun simulateFocusLoss() {
        lastOnFocusLost?.invoke()
    }

    fun setGrantFocus(grant: Boolean) {
        grantFocus = grant
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
    fun play_requestsAudioFocus_andOnFocusLostPauses() = runTest {
        val fakePlayer = FakeAudioPlayerClient()
        val fakeFocus = FakeAudioFocusController(grantFocus = true)
        val state = AudioPlaybackState(
            audioPath = "/valid/path/note.m4a",
            coroutineScope = backgroundScope,
            playerFactory = { fakePlayer },
            focusController = fakeFocus,
            fileExistsChecker = { true }
        )

        state.play()
        assertEquals(1, fakeFocus.requestCount)
        assertEquals(PlaybackStatus.PLAYING, state.status)

        fakeFocus.simulateFocusLoss()
        assertEquals(PlaybackStatus.PAUSED, state.status)
        assertEquals(1, fakeFocus.abandonCount)
    }

    @Test
    fun play_whenFocusDenied_doesNotStart() = runTest {
        val fakePlayer = FakeAudioPlayerClient()
        val fakeFocus = FakeAudioFocusController(grantFocus = false)
        val state = AudioPlaybackState(
            audioPath = "/valid/path/note.m4a",
            coroutineScope = backgroundScope,
            playerFactory = { fakePlayer },
            focusController = fakeFocus,
            fileExistsChecker = { true }
        )

        state.play()
        assertEquals(1, fakeFocus.requestCount)
        assertEquals(PlaybackStatus.PAUSED, state.status)
        assertFalse(fakePlayer.started)
    }

    @Test
    fun pauseStopReleaseCompletion_callAbandonFocus() = runTest {
        val fakePlayer = FakeAudioPlayerClient()
        val fakeFocus = FakeAudioFocusController(grantFocus = true)
        val state = AudioPlaybackState(
            audioPath = "/valid/path/note.m4a",
            coroutineScope = backgroundScope,
            playerFactory = { fakePlayer },
            focusController = fakeFocus,
            fileExistsChecker = { true }
        )

        state.play()
        assertEquals(PlaybackStatus.PLAYING, state.status)

        state.pause()
        assertEquals(PlaybackStatus.PAUSED, state.status)
        assertEquals(1, fakeFocus.abandonCount)

        state.play()
        state.stop()
        assertEquals(PlaybackStatus.IDLE, state.status)
        assertEquals(2, fakeFocus.abandonCount)

        state.play()
        fakePlayer.triggerCompletion()
        assertEquals(PlaybackStatus.COMPLETED, state.status)
        assertEquals(3, fakeFocus.abandonCount)

        state.release()
        assertEquals(4, fakeFocus.abandonCount)
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
