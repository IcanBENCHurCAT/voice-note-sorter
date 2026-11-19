// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.data.StorageStats
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysPlainLanguageTierAndPerCapabilityStatus() {
        val state = CapabilitySettingsUiState(
            tier = Tier.CORE,
            transcriberStatus = CapabilityStatus.AVAILABLE,
            summarizerStatus = CapabilityStatus.UNAVAILABLE,
            actionExtractorStatus = CapabilityStatus.UNAVAILABLE,
            isLoading = false
        )

        composeTestRule.setContent {
            SettingsScreen(
                uiState = state,
                onBack = {},
                onRefreshProbe = {}
            )
        }

        // Tier badge and heading in plain language without technical jargon
        composeTestRule.onNodeWithText("Current mode").assertIsDisplayed()
        composeTestRule.onNodeWithText("Core sorting").assertIsDisplayed()
        composeTestRule.onNodeWithText("Active Tier").assertDoesNotExist()
        composeTestRule.onNodeWithText("deterministic keyword heuristics", substring = true).assertDoesNotExist()

        // Per-capability names and non-interactive passive status badge
        composeTestRule.onNodeWithText("Voice Transcription").assertIsDisplayed()
        composeTestRule.onNodeWithText("Available")
            .assertIsDisplayed()
            .assertHasNoClickAction()

        composeTestRule.onNodeWithText("Note Summarisation").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Action Item Extraction"))
        composeTestRule.onNodeWithText("Action Item Extraction").assertIsDisplayed()

        // Unavailable explanation is shown in plain language without upgrade pressure
        composeTestRule.onNodeWithText(
            "Automatic summaries require Android's built-in on-device AI service, which isn't active on this device."
        ).assertIsDisplayed()
    }

    @Test
    fun backButtonTriggersCallback() {
        var backPressed = false
        val state = CapabilitySettingsUiState(
            tier = Tier.MINIMAL,
            transcriberStatus = CapabilityStatus.UNAVAILABLE,
            summarizerStatus = CapabilityStatus.UNAVAILABLE,
            actionExtractorStatus = CapabilityStatus.UNAVAILABLE,
            isLoading = false
        )

        composeTestRule.setContent {
            SettingsScreen(
                uiState = state,
                onBack = { backPressed = true },
                onRefreshProbe = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Navigate back").performClick()
        assertTrue(backPressed)
    }

    @Test
    fun refreshButtonTriggersProbe() {
        var refreshed = false
        val state = CapabilitySettingsUiState(
            tier = Tier.FULL,
            transcriberStatus = CapabilityStatus.AVAILABLE,
            summarizerStatus = CapabilityStatus.AVAILABLE,
            actionExtractorStatus = CapabilityStatus.AVAILABLE,
            isLoading = false
        )

        composeTestRule.setContent {
            SettingsScreen(
                uiState = state,
                onBack = {},
                onRefreshProbe = { refreshed = true }
            )
        }

        composeTestRule.onNodeWithText("Full on-device AI").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Re-check capabilities"))
        composeTestRule.onNodeWithText("Re-check capabilities").performClick()
        assertTrue(refreshed)
    }

    @Test
    fun displaysStorageStatsAndHandlesAutoDeleteToggle() {
        var toggledValue: Boolean? = null
        val state = CapabilitySettingsUiState(
            tier = Tier.FULL,
            transcriberStatus = CapabilityStatus.AVAILABLE,
            summarizerStatus = CapabilityStatus.AVAILABLE,
            actionExtractorStatus = CapabilityStatus.AVAILABLE,
            isLoading = false,
            storageStats = StorageStats(
                audioSizeBytes = 15_728_640L, // 15 MB
                databaseSizeBytes = 122_880L, // 120 KB
                noteCount = 8
            ),
            isAutoDeleteOldAudioEnabled = false
        )

        composeTestRule.setContent {
            SettingsScreen(
                uiState = state,
                onBack = {},
                onRefreshProbe = {},
                onToggleAutoDeleteOldAudio = { toggledValue = it }
            )
        }

        // Section header and metrics
        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Storage & Data"))
        composeTestRule.onNodeWithText("Storage & Data").assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Audio recordings"))
        composeTestRule.onNodeWithText("Audio recordings").assertIsDisplayed()
        composeTestRule.onNodeWithText("15.0 MB").assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Database"))
        composeTestRule.onNodeWithText("Database").assertIsDisplayed()
        composeTestRule.onNodeWithText("120.0 KB (8 voice notes)").assertIsDisplayed()

        // Toggle exists and triggers confirmation dialog before enabling
        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasContentDescription("Toggle auto-delete audio older than 90 days"))
        val switchNode = composeTestRule.onNodeWithContentDescription("Toggle auto-delete audio older than 90 days")
        switchNode.assertIsDisplayed()
        switchNode.performClick()

        // Confirmation dialog is shown explaining retention policy
        composeTestRule.onNodeWithText("Enable 90-day audio auto-delete?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enable").performClick()

        assertTrue(toggledValue == true)
    }

    @Test
    fun downloadableCapabilities_showTonalButtonsWithScopedSemantics_andSharedModelBanner() {
        var summarizerDownloadClicked = false
        var actionDownloadClicked = false

        composeTestRule.setContent {
            SettingsScreen(
                uiState = CapabilitySettingsUiState(
                    tier = Tier.CORE,
                    transcriberStatus = CapabilityStatus.AVAILABLE,
                    summarizerStatus = CapabilityStatus.DOWNLOADABLE,
                    actionExtractorStatus = CapabilityStatus.DOWNLOADABLE
                ),
                onBack = {},
                onDownloadSummarizer = { summarizerDownloadClicked = true },
                onDownloadActionExtractor = { actionDownloadClicked = true }
            )
        }

        // Passive status badge stays non-interactive
        composeTestRule.onNodeWithText("Available")
            .assertIsDisplayed()
            .assertHasNoClickAction()
        composeTestRule.onNodeWithText("Downloadable").assertDoesNotExist()

        // Unified shared-model readiness banner clarifies one download enables both
        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("One download enables both features"))
        composeTestRule.onNodeWithText("One download enables both features").assertIsDisplayed()

        // Scoped accessible download buttons >= 48dp tall
        composeTestRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasContentDescription("Download model for Note Summarisation"))
        composeTestRule.onNodeWithContentDescription("Download model for Note Summarisation")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(summarizerDownloadClicked)

        composeTestRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasContentDescription("Download model for Action Item Extraction"))
        composeTestRule.onNodeWithContentDescription("Download model for Action Item Extraction")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(actionDownloadClicked)
    }

    @Test
    fun emptyStorage_disablesDeleteAllButton_andShowsReasonCaption() {
        composeTestRule.setContent {
            SettingsScreen(
                uiState = CapabilitySettingsUiState(
                    tier = Tier.MINIMAL,
                    storageStats = StorageStats(
                        audioSizeBytes = 0L,
                        databaseSizeBytes = 0L,
                        noteCount = 0
                    )
                ),
                onBack = {}
            )
        }

        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Delete all notes"))
        composeTestRule.onNodeWithContentDescription("Delete all notes and audio")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("No voice notes or audio recordings to delete.")
            .assertIsDisplayed()
    }
}
