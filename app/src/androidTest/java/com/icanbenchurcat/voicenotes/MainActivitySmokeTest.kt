// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
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
    }

    @Test
    fun mainActivity_rendersList_recordFab_andOpensNoteDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.get(context).noteDao()

        // 1. Assert the record FAB is present with content description 'Record a note'
        composeRule.onNodeWithContentDescription("Record a note").assertIsDisplayed()

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
        composeRule.onNodeWithText("Pipeline: transcribe:OK,title:OK,actions:OK,bucket:OK").assertIsDisplayed()
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
            composeRule.onAllNodesWithText("Confirm filing (INBOX)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("File in NOTE").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { dao.byId(reviewNoteId)?.status == NoteStatus.SORTED.name }
        }
        val updated = runBlocking { dao.byId(reviewNoteId)!! }
        assertEquals("NOTE", updated.bucketKind)
        assertEquals(NoteStatus.SORTED.name, updated.status)
    }
}
