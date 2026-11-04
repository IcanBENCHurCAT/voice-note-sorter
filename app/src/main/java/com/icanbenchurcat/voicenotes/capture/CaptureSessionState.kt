// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

/**
 * State representing an active or completed recording session.
 */
sealed class CaptureSessionState {
    data object Idle : CaptureSessionState()

    data class Recording(
        val audioPath: String,
        val startedAtEpochMs: Long,
        val elapsedSeconds: Long = 0L,
        val isPaused: Boolean = false,
        val showWarning4Min: Boolean = false
    ) : CaptureSessionState()

    data class Completed(
        val noteId: Long,
        val audioPath: String
    ) : CaptureSessionState()

    data class Error(
        val message: String
    ) : CaptureSessionState()
}
