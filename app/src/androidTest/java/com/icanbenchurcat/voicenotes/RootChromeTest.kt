// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.ui.theme.VoiceNoteSorterTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootChromeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rootTopAppBar_exposesAccessibleSettingsButton() {
        var openedSettings = false
        composeRule.setContent {
            VoiceNoteSorterTheme(dynamicColor = false) {
                RootTopAppBar(onOpenSettings = { openedSettings = true })
            }
        }

        composeRule.onNodeWithText("Voice Note Sorter").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertTrue(openedSettings)
    }

    @Test
    fun rootRecordFab_showsRecordAndTransitionsToStopWhenRecording() {
        var isRecording by mutableStateOf(false)
        var startCount = 0
        var stopCount = 0

        composeRule.setContent {
            VoiceNoteSorterTheme(dynamicColor = false) {
                RootRecordFab(
                    isRecording = isRecording,
                    onStartRecording = { startCount++ },
                    onStopRecording = { stopCount++ }
                )
            }
        }

        composeRule.onNodeWithText("Record").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Record a note").assertIsDisplayed().performClick()
        assertTrue(startCount == 1)

        isRecording = true
        composeRule.onNodeWithText("Stop").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop active recording").assertIsDisplayed().performClick()
        assertTrue(stopCount == 1)
    }

    @Test
    fun rootRecordFab_colorsMeetBoundaryAndTextContrastInLightAndDarkThemes() {
        assertThemeFabContrast(darkTheme = false)
        assertThemeFabContrast(darkTheme = true)
    }

    private fun assertThemeFabContrast(darkTheme: Boolean) {
        var primary = Color.Unspecified
        var onPrimary = Color.Unspecified
        var error = Color.Unspecified
        var onError = Color.Unspecified
        var surface = Color.Unspecified

        composeRule.setContent {
            VoiceNoteSorterTheme(darkTheme = darkTheme, dynamicColor = false) {
                primary = MaterialTheme.colorScheme.primary
                onPrimary = MaterialTheme.colorScheme.onPrimary
                error = MaterialTheme.colorScheme.error
                onError = MaterialTheme.colorScheme.onError
                surface = MaterialTheme.colorScheme.surface
            }
        }
        composeRule.waitForIdle()

        assertTrue("Primary FAB boundary contrast < 3:1 (dark=$darkTheme)", contrast(primary, surface) >= 3.0)
        assertTrue("Primary FAB text contrast < 4.5:1 (dark=$darkTheme)", contrast(onPrimary, primary) >= 4.5)
        assertTrue("Stop FAB boundary contrast < 3:1 (dark=$darkTheme)", contrast(error, surface) >= 3.0)
        assertTrue("Stop FAB text contrast < 4.5:1 (dark=$darkTheme)", contrast(onError, error) >= 4.5)
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}
