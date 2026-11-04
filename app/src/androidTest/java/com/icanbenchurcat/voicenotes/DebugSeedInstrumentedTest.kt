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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugSeedInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.get(context).clearAllTables()
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.get(context).clearAllTables()
    }

    @Test
    fun debugSeed_populatesAllBucketsTiersActionsTags_andCanClearAndReseed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.get(context).noteDao()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("No voice notes yet. Tap Record to capture a thought.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("No voice notes yet. Tap Record to capture a thought.")
            .assertIsDisplayed()

        // 1. Tap 'Seed sample data' button
        composeRule.onNodeWithContentDescription("Seed sample data").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Renovation follow-ups for today")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Verify DB contents cover all 5 default buckets, Tier 1 no-transcript, actions (done & not done), and tags on >= 2 notes
        runBlocking {
            val notes = dao.observeAll().first()
            val bucketsCovered = notes.map { it.bucketKind }.toSet()
            assertEquals(setOf("IDEA", "TASK", "LIST", "NOTE", "INBOX"), bucketsCovered)

            val tier1Notes = notes.filter { it.tierUsed == "MINIMAL" && it.transcript.isEmpty() }
            assertEquals(1, tier1Notes.size)

            val taskNote = notes.first { it.bucketKind == "TASK" }
            val actions = dao.actionsFor(taskNote.id)
            assertTrue(actions.any { it.done })
            assertTrue(actions.any { !it.done })

            val notesWithTags = notes.count { dao.tagsFor(it.id).isNotEmpty() }
            assertTrue("Expected tags on at least 2 notes, got $notesWithTags", notesWithTags >= 2)
        }

        // 3. Open the TASK note detail screen and verify tags & action items render
        composeRule.onNodeWithText("Renovation follow-ups for today").assertIsDisplayed()
        composeRule.onNodeWithText("Renovation follow-ups for today").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Tags: #errands, #renovation")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Tags: #errands, #renovation").assertIsDisplayed()
        composeRule.onNodeWithText("[x] Call the electrician before noon").assertIsDisplayed()
        composeRule.onNodeWithText("[ ] Send the updated floor plan PDF to Dana").assertIsDisplayed()

        // 4. Clear data and re-seed without reinstalling
        composeRule.onNodeWithContentDescription("Clear seed data").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { dao.observeAll().first().isEmpty() }
        }

        composeRule.onNodeWithContentDescription("Seed sample data").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { dao.observeAll().first().size == 5 }
        }
    }
}
