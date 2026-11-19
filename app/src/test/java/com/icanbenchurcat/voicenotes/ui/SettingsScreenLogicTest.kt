// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.data.StorageStats
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenLogicTest {

    @Test
    fun shouldShowSharedFoundationModelBanner_trueOnlyWhenBothDownloadable() {
        assertTrue(
            shouldShowSharedFoundationModelBanner(
                CapabilitySettingsUiState(
                    summarizerStatus = CapabilityStatus.DOWNLOADABLE,
                    actionExtractorStatus = CapabilityStatus.DOWNLOADABLE
                )
            )
        )

        assertFalse(
            shouldShowSharedFoundationModelBanner(
                CapabilitySettingsUiState(
                    summarizerStatus = CapabilityStatus.DOWNLOADABLE,
                    actionExtractorStatus = CapabilityStatus.AVAILABLE
                )
            )
        )

        assertFalse(
            shouldShowSharedFoundationModelBanner(
                CapabilitySettingsUiState(
                    summarizerStatus = CapabilityStatus.UNAVAILABLE,
                    actionExtractorStatus = CapabilityStatus.DOWNLOADABLE
                )
            )
        )

        assertFalse(
            shouldShowSharedFoundationModelBanner(
                CapabilitySettingsUiState(
                    summarizerStatus = CapabilityStatus.AVAILABLE,
                    actionExtractorStatus = CapabilityStatus.AVAILABLE
                )
            )
        )
    }

    @Test
    fun canDeleteAllNotes_falseOnlyWhenZeroNotesAndZeroAudioBytes() {
        assertFalse(canDeleteAllNotes(StorageStats(audioSizeBytes = 0L, databaseSizeBytes = 0L, noteCount = 0)))
        assertFalse(canDeleteAllNotes(StorageStats(audioSizeBytes = 0L, databaseSizeBytes = 4096L, noteCount = 0)))
        assertTrue(canDeleteAllNotes(StorageStats(audioSizeBytes = 0L, databaseSizeBytes = 4096L, noteCount = 1)))
        assertTrue(canDeleteAllNotes(StorageStats(audioSizeBytes = 1024L, databaseSizeBytes = 0L, noteCount = 0)))
    }
}
