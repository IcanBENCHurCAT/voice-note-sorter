// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icanbenchurcat.voicenotes.capture.CaptureSessionState
import com.icanbenchurcat.voicenotes.capture.RecordingService
import com.icanbenchurcat.voicenotes.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CaptureStackIntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.get(context)
        db.clearAllTables()
    }

    @Test
    fun tappingRecordFab_whenMicGranted_showsRecordingBannerAndControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Grant RECORD_AUDIO and POST_NOTIFICATIONS permissions synchronously via UiAutomation
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }

        // Assert Record FAB is displayed
        composeRule.onNodeWithContentDescription("Record a note").assertIsDisplayed()

        // Tap FAB to start capture
        composeRule.onNodeWithContentDescription("Record a note").performClick()

        // Verify active recording banner appears with elapsed time and controls
        composeRule.waitUntil(timeoutMillis = 7_000) {
            composeRule.onAllNodesWithText("Recording note")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Recording note").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause recording").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop recording").assertIsDisplayed()

        // Tap Stop recording
        composeRule.onNodeWithContentDescription("Stop recording").performClick()

        // Verify banner disappears and a Note is persisted in Room
        composeRule.waitUntil(timeoutMillis = 7_000) {
            composeRule.onAllNodesWithText("Recording note")
                .fetchSemanticsNodes().isEmpty()
        }

        val dao = AppDatabase.get(context).noteDao()
        composeRule.waitUntil(timeoutMillis = 7_000) {
            runBlocking { dao.allAudioPaths().isNotEmpty() }
        }

        val paths = runBlocking { dao.allAudioPaths() }
        assertTrue("Expected recorded audio paths in database", paths.isNotEmpty())
        val recordedPath = paths.first()
        val audioFile = File(recordedPath)
        assertTrue("Audio file should exist in app-private storage: $recordedPath", audioFile.exists())
    }
}
