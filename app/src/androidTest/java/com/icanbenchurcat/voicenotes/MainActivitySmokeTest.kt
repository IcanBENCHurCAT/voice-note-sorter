// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.data.StorageRetentionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.get(context)
        db.clearAllTables()
        runBlocking {
            StorageRetentionManager.fromContext(context).setDeveloperModeEnabled(false)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("Developer options")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun mainActivity_rendersList_recordFab_andOpensNoteDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.get(context).noteDao()

        // 1. Assert the extended record FAB is present with both content description and visible 'Record' label
        composeRule.onNodeWithContentDescription("Record a note").assertIsDisplayed()
        composeRule.onNodeWithText("Record").assertIsDisplayed()

        // 2. Insert a note and assert the notes list renders it
        runBlocking {
            dao.insert(
                Note(
                    createdAtEpochMs = 1_700_000_000_000L,
                    audioPath = "/data/local/tmp/smoke.m4a",
                    transcript = "Pick up coffee beans and oat milk on the way home.",
                    title = "Grocery pickup reminder",
                    summary = "Coffee beans and oat milk.",
                    bucketKind = "TASK",
                    tierUsed = "CORE",
                    stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:OK"
                )
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Grocery pickup reminder")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Grocery pickup reminder").assertIsDisplayed()
        composeRule.onNodeWithText("Coffee beans and oat milk.").assertIsDisplayed()

        // 3. Tap the note in the list and assert the detail screen opens
        composeRule.onNodeWithText("Grocery pickup reminder").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick up coffee beans and oat milk on the way home.")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Pick up coffee beans and oat milk on the way home.").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Processing status").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Transcribe: OK").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Bucket: OK").fetchSemanticsNodes().size)
    }

    @Test
    fun needsReviewBadgeFilterAndConfirmBucket_clearsNeedsReviewState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.get(context).noteDao()

        var reviewNoteId = 0L
        runBlocking {
            dao.insert(
                Note(
                    createdAtEpochMs = 1_700_000_010_000L,
                    audioPath = "/data/local/tmp/sorted.m4a",
                    transcript = "Remind me to call the electrician.",
                    title = "Call electrician",
                    summary = "Call the electrician.",
                    bucketKind = "TASK",
                    tierUsed = "CORE",
                    stageStatus = "transcribe:OK,summarize:OK,actions:OK,bucket:OK",
                    status = NoteStatus.SORTED.name
                )
            )
            reviewNoteId = dao.insert(
                Note(
                    createdAtEpochMs = 1_700_000_020_000L,
                    audioPath = "/data/local/tmp/uncertain.m4a",
                    transcript = "Walked through the courtyard after lunch.",
                    title = "Courtyard observation",
                    summary = "Walked through the courtyard after lunch.",
                    bucketKind = "INBOX",
                    tierUsed = "CORE",
                    stageStatus = "transcribe:OK,summarize:OK,actions:OK,bucket:OK",
                    status = NoteStatus.NEEDS_REVIEW.name
                )
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Courtyard observation")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Verify Needs review badge and filter chip are displayed
        composeRule.onNodeWithContentDescription("Needs review badge").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Filter Needs Review notes").performClick()

        // Filtered list shows only the Needs Review note
        composeRule.onNodeWithText("Courtyard observation").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Call electrician").fetchSemanticsNodes().size
        )

        // Open the Needs Review note and change its bucket to NOTE (clearing NEEDS_REVIEW)
        composeRule.onNodeWithText("Courtyard observation").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Confirm filing in Inbox")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("File in Notes").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { dao.byId(reviewNoteId)?.status == NoteStatus.SORTED.name }
        }
        val updated = runBlocking { dao.byId(reviewNoteId)!! }
        assertEquals("NOTE", updated.bucketKind)
        assertEquals(NoteStatus.SORTED.name, updated.status)
    }

    @Test
    fun rootTabs_shareTopAppBarWithAccessibleSettingsButton_andOpenSettings() {
        // On Notes tab: shared TopAppBar, >= 48dp Settings IconButton, and plain-language empty state
        composeRule.onNodeWithText("Open & Shut").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Device capability:", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("No voice notes yet").assertIsDisplayed()
        composeRule.onNodeWithText("Record").assertIsDisplayed()

        // Open Settings from Notes tab and return
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Offline AI & Storage").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        composeRule.onNodeWithText("No voice notes yet").assertIsDisplayed()

        // Switch to Tasks tab: shared TopAppBar Settings IconButton and Record ExtendedFAB remain visible
        composeRule.onNodeWithText("Tasks").performClick()
        composeRule.onNodeWithText("Open & Shut").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Record").assertIsDisplayed()

        // Open Settings from Tasks tab and return to Tasks tab
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Offline AI & Storage").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        composeRule.onNodeWithText("No tasks yet. Action items extracted from voice notes will appear here.")
            .assertIsDisplayed()
    }

    @Test
    fun topAppBarOverflow_opensManageBucketsDialog() {
        composeRule.onNodeWithText("Manage buckets").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("More options").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Manage buckets").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Manage Buckets").assertIsDisplayed()
    }
}
