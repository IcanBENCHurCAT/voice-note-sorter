// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDetailEditModeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun editMode_usesContextualEditTopAppBar_andHasNoInBodyCancelSaveRow() {
        val note = Note(
            id = 1L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/1.m4a",
            title = "Original Note Title",
            transcript = "Original transcript text",
            bucketKind = "TASK",
            status = NoteStatus.SORTED.name
        )

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                onBack = {}
            )
        }

        // Read mode displays read actions and title
        composeTestRule.onNodeWithText("Original Note Title").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit note").assertIsDisplayed().performClick()

        // Contextual edit TopAppBar replaces read-mode header & actions
        composeTestRule.onNodeWithText("Edit note").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Cancel editing note")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Save edited note")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        // Read-mode TopAppBar actions are hidden while editing
        composeTestRule.onNodeWithContentDescription("Edit note").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Share note").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("More options").assertDoesNotExist()

        // No duplicate in-body Cancel/Save row exists
        composeTestRule.onAllNodesWithContentDescription("Cancel editing note").assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription("Save edited note").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Save").assertCountEquals(1)
    }

    @Test
    fun editMode_hidesReadOnlyTranscript_soTranscriptRendersOnce() {
        val transcript = "Unique transcript body that must appear only once in edit mode."
        val note = Note(
            id = 2L,
            createdAtEpochMs = 2000L,
            audioPath = "/fake/2.m4a",
            title = "Transcript Note",
            transcript = transcript,
            bucketKind = "NOTE",
            status = NoteStatus.SORTED.name
        )

        composeTestRule.setContent {
            NoteDetailScreen(note = note)
        }

        // Read mode shows the read-only transcript once
        composeTestRule.onNodeWithTag("note_detail_transcript").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(transcript).assertCountEquals(1)

        // Enter edit mode
        composeTestRule.onNodeWithContentDescription("Edit note").performClick()

        // Read-only transcript is hidden; only the editable Transcript field holds the text
        composeTestRule.onNodeWithTag("note_detail_transcript").assertDoesNotExist()
        composeTestRule.onAllNodesWithText(transcript).assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription("Edit transcript input").assertIsDisplayed()
    }

    @Test
    fun editMode_whenBlankTranscript_hidesEmptyTranscriptCopy() {
        val note = Note(
            id = 3L,
            createdAtEpochMs = 3000L,
            audioPath = "",
            title = "Blank Transcript Note",
            transcript = "",
            bucketKind = "NOTE",
            status = NoteStatus.SORTED.name
        )

        composeTestRule.setContent {
            NoteDetailScreen(note = note)
        }

        composeTestRule.onNodeWithTag("note_detail_transcript_empty").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Edit note").performClick()

        composeTestRule.onNodeWithTag("note_detail_transcript_empty").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Edit transcript input").assertIsDisplayed()
    }

    @Test
    fun editMode_scrollsSmoothlyToRevealEditFields_whenScrolledDown() {
        val longTranscript = (1..40).joinToString("\n") { line ->
            "Line $line: detailed voice note discussion about architecture and milestones."
        }
        val note = Note(
            id = 4L,
            createdAtEpochMs = 4000L,
            audioPath = "",
            title = "Long Scrollable Note",
            transcript = longTranscript,
            bucketKind = "NOTE",
            stageStatus = "transcribe:OK,bucket:OK",
            status = NoteStatus.SORTED.name
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.height(360.dp)) {
                NoteDetailScreen(
                    note = note,
                    buckets = listOf(Bucket("NOTE", "Notes"), Bucket("TASK", "Tasks")),
                    isDeveloperModeEnabled = true
                )
            }
        }

        // Scroll down to bottom section so top of detail content is offscreen
        composeTestRule.onNodeWithText("Processing status").performScrollTo().assertIsDisplayed()

        // Activate Edit from the persistent contextual TopAppBar
        composeTestRule.onNodeWithContentDescription("Edit note").performClick()
        composeTestRule.waitForIdle()

        // Edit fields at the top are revealed automatically
        composeTestRule.onNodeWithContentDescription("Edit title input").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit transcript input").assertIsDisplayed()
    }

    @Test
    fun editMode_cancelRevertsChanges_andSavePersistsTitleTranscriptAndTags() {
        val note = Note(
            id = 5L,
            createdAtEpochMs = 5000L,
            audioPath = "",
            title = "Initial Title",
            transcript = "Initial transcript",
            bucketKind = "TASK",
            status = NoteStatus.SORTED.name
        )
        var saveInvoked = false
        var savedTitle = ""
        var savedTranscript = ""
        var savedTags: List<String> = emptyList()

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                tags = listOf("alpha", "beta"),
                onSaveEdit = { t, tr ->
                    saveInvoked = true
                    savedTitle = t
                    savedTranscript = tr
                },
                onSaveTags = { newTags ->
                    savedTags = newTags
                }
            )
        }

        // Cancel flow discards edits
        composeTestRule.onNodeWithContentDescription("Edit note").performClick()
        composeTestRule.onNodeWithContentDescription("Edit title input").performTextReplacement("Discarded Title")
        composeTestRule.onNodeWithContentDescription("Cancel editing note").performClick()

        assertFalse(saveInvoked)
        composeTestRule.onNodeWithText("Initial Title").assertIsDisplayed()

        // Edit & Save flow updates title, transcript, and tags inline
        composeTestRule.onNodeWithContentDescription("Edit note").performClick()
        composeTestRule.onNodeWithTag("note_detail_tags").assertDoesNotExist()
        composeTestRule.onNodeWithTag("note_detail_edit_tags").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Edit title input").performTextReplacement("Updated Title")
        composeTestRule.onNodeWithContentDescription("Edit transcript input").performTextReplacement("Updated transcript")
        composeTestRule.onNodeWithContentDescription("Remove tag beta").performClick()
        composeTestRule.onNodeWithContentDescription("Add tag input").performTextInput("#gamma")
        composeTestRule.onNodeWithContentDescription("Add tag").performClick()

        composeTestRule.onNodeWithContentDescription("Save edited note").performClick()

        assertEquals("Updated Title", savedTitle)
        assertEquals("Updated transcript", savedTranscript)
        assertEquals(listOf("alpha", "gamma"), savedTags)
    }
}
