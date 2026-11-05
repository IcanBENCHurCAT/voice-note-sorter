// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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

        // Tier badge in plain language
        composeTestRule.onNodeWithText("Core sorting").assertIsDisplayed()

        // Per-capability names and statuses
        composeTestRule.onNodeWithText("Voice Transcription").assertIsDisplayed()
        composeTestRule.onNodeWithText("Available").assertIsDisplayed()

        composeTestRule.onNodeWithText("Note Summarisation").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Action Item Extraction"))
        composeTestRule.onNodeWithText("Action Item Extraction").assertIsDisplayed()

        // Unavailable explanation is shown without upgrade pressure
        composeTestRule.onNodeWithText(
            "Requires on-device system AI features (AICore) to be present and active."
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
}
