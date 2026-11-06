// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDetailVisualCraftTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun noteDetail_displaysSuggestedBucketBadge_tagChips_andStagePills() {
        val note = Note(
            id = 10L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/10.m4a",
            title = "Hardware review note",
            transcript = "Order new USB cables",
            bucketKind = "TASK",
            tierUsed = "CORE",
            stageStatus = "transcribe:OK,bucket:OK,actions:OK",
            status = NoteStatus.NEEDS_REVIEW.name
        )
        val buckets = listOf(
            Bucket("TASK", "Tasks"),
            Bucket("IDEA", "Ideas")
        )

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                buckets = buckets,
                tags = listOf("hardware", "cables"),
                onConfirmOrChangeBucket = {}
            )
        }

        // Suggested bucket chip label
        composeTestRule.onNodeWithText("Tasks (Suggested)").assertIsDisplayed()

        // Tag chip components
        composeTestRule.onNodeWithText("#hardware").assertIsDisplayed()
        composeTestRule.onNodeWithText("#cables").assertIsDisplayed()

        // Formatted processing status stage pills
        composeTestRule.onNodeWithText("Processing status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transcribe: OK").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bucket: OK").assertIsDisplayed()
        composeTestRule.onNodeWithText("Actions: OK").assertIsDisplayed()

        // Assert 48dp minimum touch targets
        composeTestRule.onNodeWithText("Tasks (Suggested)").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("#hardware").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Edit note").assertHeightIsAtLeast(48.dp)

    }
}
