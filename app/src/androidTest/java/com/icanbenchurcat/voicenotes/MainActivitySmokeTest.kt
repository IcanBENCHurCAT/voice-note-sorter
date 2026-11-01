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
import kotlinx.coroutines.runBlocking
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
}
