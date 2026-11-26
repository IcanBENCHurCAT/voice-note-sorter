// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowInsets
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.data.StorageStats
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

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
            "Automatic summaries require built-in AI support on this phone, which isn't active right now."
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
        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Check again"))
        composeTestRule.onNodeWithText("Check again").performClick()
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

        composeTestRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Notes & text"))
        composeTestRule.onNodeWithText("Notes & text").assertIsDisplayed()
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
            .performScrollToNode(hasContentDescription("Download AI feature for Note Summarisation"))
        composeTestRule.onNodeWithContentDescription("Download AI feature for Note Summarisation")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(summarizerDownloadClicked)

        composeTestRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasContentDescription("Download AI feature for Action Item Extraction"))
        composeTestRule.onNodeWithContentDescription("Download AI feature for Action Item Extraction")
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

        composeTestRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasText("No voice notes or audio recordings to delete."))
        composeTestRule.onNodeWithContentDescription("Delete all notes and audio")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("No voice notes or audio recordings to delete.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun topBarActions_rendersInsideTopAppBarAlignedWithTitle() {
        var clicked = false

        composeTestRule.setContent {
            SettingsScreen(
                uiState = CapabilitySettingsUiState(
                    tier = Tier.CORE,
                    isDeveloperModeEnabled = true
                ),
                onBack = {},
                topBarActions = {
                    IconButton(
                        onClick = { clicked = true },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Developer options" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null
                        )
                    }
                }
            )
        }

        val devAction = composeTestRule.onNodeWithContentDescription("Developer options")
        devAction.assertIsDisplayed().assertHeightIsAtLeast(48.dp)

        val titleBounds = composeTestRule.onNodeWithText("Offline AI & Storage").fetchSemanticsNode().boundsInRoot
        val actionBounds = devAction.fetchSemanticsNode().boundsInRoot
        // Action icon shares the TopAppBar row with the title (vertical centers align)
        val titleCenterY = (titleBounds.top + titleBounds.bottom) / 2f
        val actionCenterY = (actionBounds.top + actionBounds.bottom) / 2f
        assertTrue(
            "Expected Developer options inside TopAppBar vertically aligned with title, diff=${abs(titleCenterY - actionCenterY)}",
            abs(titleCenterY - actionCenterY) <= actionBounds.height
        )

        devAction.performClick()
        assertTrue(clicked)
    }

    @Test
    fun bottomSettingsList_appliesNavigationBarInsetsAndDoesNotClipDeveloperOptionsSubtitle() {
        var density: Density? = null
        val navBarBottomDp = 48.dp

        composeTestRule.setContent {
            val currentDensity = LocalDensity.current
            density = currentDensity
            val navBarPx = with(currentDensity) { navBarBottomDp.roundToPx() }
            val windowInsets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, navBarPx)
                )
                .build()

            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowInsets(windowInsets)
            ) {
                Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                    SettingsScreen(
                        uiState = CapabilitySettingsUiState(
                            tier = Tier.FULL,
                            transcriberStatus = CapabilityStatus.AVAILABLE,
                            summarizerStatus = CapabilityStatus.AVAILABLE,
                            actionExtractorStatus = CapabilityStatus.AVAILABLE,
                            isDeveloperModeEnabled = true
                        ),
                        onBack = {}
                    )
                }
            }
        }

        val subtitleText = "Show sample data and reset controls on the home screen"
        composeTestRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasText(subtitleText))

        val subtitleNode = composeTestRule.onNodeWithText(subtitleText)
        subtitleNode.assertIsDisplayed()

        val listBounds = composeTestRule.onNodeWithTag("settings_list").fetchSemanticsNode().boundsInRoot
        val subtitleBounds = subtitleNode.fetchSemanticsNode().boundsInRoot
        val minClearancePx = with(density!!) { (navBarBottomDp + 24.dp).toPx() }
        val actualClearancePx = listBounds.bottom - subtitleBounds.bottom
        assertTrue(
            "Expected >= ${minClearancePx}px clearance below Developer options subtitle for nav bar + 24dp padding, got ${actualClearancePx}px",
            actualClearancePx >= minClearancePx - 1f
        )
    }
}
