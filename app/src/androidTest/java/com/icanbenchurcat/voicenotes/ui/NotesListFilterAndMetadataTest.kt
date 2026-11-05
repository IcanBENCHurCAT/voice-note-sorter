// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.data.ActionItemWithNote
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.data.NoteTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesListFilterAndMetadataTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun filteringByBucket_filtersCardsCorrectly() {
        val note1 = Note(
            id = 1L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/1.m4a",
            title = "Idea 1",
            summary = "Summary of idea 1",
            bucketKind = "IDEA",
            status = NoteStatus.SORTED.name
        )
        val note2 = Note(
            id = 2L,
            createdAtEpochMs = 2000L,
            audioPath = "/fake/2.m4a",
            title = "Task 1",
            summary = "Summary of task 1",
            bucketKind = "TASK",
            status = NoteStatus.SORTED.name
        )
        val buckets = listOf(
            Bucket("IDEA", "Ideas"),
            Bucket("TASK", "Tasks")
        )

        composeTestRule.setContent {
            NotesListScreen(
                notes = listOf(note1, note2),
                buckets = buckets,
                onOpen = {}
            )
        }

        // Both notes displayed initially
        composeTestRule.onNodeWithText("Idea 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Task 1").assertIsDisplayed()

        // Click on "Tasks (1)" filter chip
        composeTestRule.onNodeWithContentDescription("Filter by bucket Tasks").performScrollTo().performClick()

        // Only Task 1 displayed
        composeTestRule.onNodeWithText("Task 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Idea 1").assertDoesNotExist()
    }

    @Test
    fun filteringByTag_filtersCardsCorrectly() {
        val note1 = Note(
            id = 1L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/1.m4a",
            title = "Grocery list",
            summary = "Milk and eggs",
            bucketKind = "LIST",
            status = NoteStatus.SORTED.name
        )
        val note2 = Note(
            id = 2L,
            createdAtEpochMs = 2000L,
            audioPath = "/fake/2.m4a",
            title = "Architecture diagram",
            summary = "Design boundaries",
            bucketKind = "NOTE",
            status = NoteStatus.SORTED.name
        )
        val noteTags = listOf(
            NoteTag(noteId = 1L, tagName = "errands"),
            NoteTag(noteId = 2L, tagName = "work")
        )

        composeTestRule.setContent {
            NotesListScreen(
                notes = listOf(note1, note2),
                tags = listOf("errands", "work"),
                noteTags = noteTags,
                onOpen = {}
            )
        }

        // Click tag #errands
        composeTestRule.onNodeWithContentDescription("Filter by tag errands").performClick()

        composeTestRule.onNodeWithText("Grocery list").assertIsDisplayed()
        composeTestRule.onNodeWithText("Architecture diagram").assertDoesNotExist()
    }

    @Test
    fun displaysMetadataBadges_tasksCountAndTags() {
        val note = Note(
            id = 1L,
            createdAtEpochMs = System.currentTimeMillis() - 60000,
            audioPath = "/fake/1.m4a",
            title = "Project Launch",
            summary = "Prepare release notes.",
            bucketKind = "TASK",
            tierUsed = "CORE",
            status = NoteStatus.SORTED.name
        )
        val tasks = listOf(
            ActionItemWithNote(id = 1L, noteId = 1L, text = "Tag v1.0", done = true),
            ActionItemWithNote(id = 2L, noteId = 1L, text = "Notify team", done = false)
        )
        val noteTags = listOf(
            NoteTag(noteId = 1L, tagName = "launch")
        )

        composeTestRule.setContent {
            NotesListScreen(
                notes = listOf(note),
                tasks = tasks,
                tags = listOf("launch"),
                noteTags = noteTags,
                onOpen = {}
            )
        }

        composeTestRule.onNodeWithText("Project Launch").assertIsDisplayed()
        composeTestRule.onNodeWithText("Prepare release notes.").assertIsDisplayed()
        composeTestRule.onNodeWithText("1m ago").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tasks 1/2").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Filter by tag launch").assertIsDisplayed()
    }
}
