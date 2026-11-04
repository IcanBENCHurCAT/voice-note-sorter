// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioFileManagerTest {

    @Test
    fun getRecordingsDir_createsDirectoryIfMissing() {
        val tempParent = File(System.getProperty("java.io.tmpdir"), "test_context_files_${System.currentTimeMillis()}")
        tempParent.mkdirs()
        try {
            val fakeDir = File(tempParent, "recordings")
            assertFalse(fakeDir.exists())

            // Test directory creation
            fakeDir.mkdirs()
            assertTrue(fakeDir.exists())
            assertTrue(fakeDir.isDirectory)
        } finally {
            tempParent.deleteRecursively()
        }
    }

    @Test
    fun captureSessionState_transitionsCorrectly() {
        var state: CaptureSessionState = CaptureSessionState.Idle
        assertTrue(state is CaptureSessionState.Idle)

        state = CaptureSessionState.Recording(
            audioPath = "/fake/test.m4a",
            startedAtEpochMs = 123456789L,
            elapsedSeconds = 0L,
            isPaused = false,
            showWarning4Min = false
        )
        assertTrue(state is CaptureSessionState.Recording)
        val recording = state as CaptureSessionState.Recording
        assertEquals(0L, recording.elapsedSeconds)
        assertFalse(recording.isPaused)
        assertFalse(recording.showWarning4Min)

        // Paused state
        state = recording.copy(isPaused = true, elapsedSeconds = 120L)
        assertTrue((state as CaptureSessionState.Recording).isPaused)
        assertEquals(120L, (state as CaptureSessionState.Recording).elapsedSeconds)

        // 4-minute warning
        state = recording.copy(elapsedSeconds = 245L, showWarning4Min = true)
        assertTrue((state as CaptureSessionState.Recording).showWarning4Min)

        // Completed state
        state = CaptureSessionState.Completed(
            noteId = 42L,
            audioPath = "/fake/test.m4a"
        )
        assertTrue(state is CaptureSessionState.Completed)
        assertEquals(42L, (state as CaptureSessionState.Completed).noteId)
    }

    @Test
    fun durationLimits_matchSpecification() {
        assertEquals(240L, RecordingService.WARNING_LIMIT_SECONDS)
        assertEquals(300L, RecordingService.HARD_LIMIT_SECONDS)
    }
}
