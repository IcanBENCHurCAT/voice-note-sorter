// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.data.ActionItemWithNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TasksScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tasksScreen_usesSharedDueFormatter_andSupportsInlineEditAndDeleteWith48dpTargets() {
        val friday5pmEpochMs = LocalDateTime.of(2026, 11, 20, 17, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val task = ActionItemWithNote(
            id = 10L,
            noteId = 2L,
            text = "Call electrician about kitchen wiring",
            dueAtEpochMs = friday5pmEpochMs,
            done = false,
            noteTitle = "Kitchen electrical work"
        )

        var updatedId: Long? = null
        var updatedText: String? = null
        var updatedDue: Long? = -1L
        var deletedId: Long? = null

        composeTestRule.setContent {
            TasksScreen(
                tasks = listOf(task),
                onToggleDone = { _, _ -> },
                onOpenNote = {},
                onUpdateTask = { id, text, due ->
                    updatedId = id
                    updatedText = text
                    updatedDue = due
                },
                onDeleteTask = { id ->
                    deletedId = id
                }
            )
        }

        val expectedFormatted = NoteCardFormatting.formatDueDate(friday5pmEpochMs)
        composeTestRule.onNodeWithText("Due: $expectedFormatted").assertIsDisplayed()

        // Verify >= 48dp touch targets
        composeTestRule.onNodeWithContentDescription("Edit due date: $expectedFormatted")
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Edit task: Call electrician about kitchen wiring")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Delete task: Call electrician about kitchen wiring")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // Open inline editor via edit button, modify text and clear due date
        composeTestRule.onNodeWithContentDescription("Edit task: Call electrician about kitchen wiring")
            .performClick()
        composeTestRule.onNodeWithContentDescription("Action item text input").performTextClearance()
        composeTestRule.onNodeWithContentDescription("Action item text input")
            .performTextInput("Call master electrician")
        composeTestRule.onNodeWithContentDescription("Clear due date").performClick()
        composeTestRule.onNodeWithContentDescription("Save action item").performClick()

        assertEquals(10L, updatedId)
        assertEquals("Call master electrician", updatedText)
        assertNull(updatedDue)

        // Delete task
        composeTestRule.onNodeWithContentDescription("Delete task: Call electrician about kitchen wiring")
            .performClick()
        assertEquals(10L, deletedId)
    }

    @Test
    fun taskCard_rendersPrimaryTitleAtOnSurfaceAboveTonalDueBadge_andFromChevronNavigates() {
        val activeTask = ActionItemWithNote(
            id = 1L,
            noteId = 10L,
            text = "Call electrician about kitchen wiring",
            dueAtEpochMs = 1795046400000L,
            done = false,
            noteTitle = "Kitchen electrical work"
        )
        val completedTask = ActionItemWithNote(
            id = 2L,
            noteId = 11L,
            text = "Pick up 14-gauge copper wire",
            dueAtEpochMs = null,
            done = true,
            noteTitle = "Hardware store list"
        )

        var openedNoteId: Long? = null
        var onSurface = Color.Unspecified
        var onSecondaryContainer = Color.Unspecified
        var primary = Color.Unspecified

        composeTestRule.setContent {
            onSurface = MaterialTheme.colorScheme.onSurface
            onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer
            primary = MaterialTheme.colorScheme.primary
            TasksScreen(
                tasks = listOf(activeTask, completedTask),
                onToggleDone = { _, _ -> },
                onOpenNote = { openedNoteId = it }
            )
        }

        // Filter chips and open/completed summary clarify Tasks vs All counts
        composeTestRule.onNodeWithText("All (2)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Completed (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 open · 1 completed").assertIsDisplayed()

        // Active task title uses full onSurface contrast while due badge uses tonal onSecondaryContainer
        val titleNode = composeTestRule.onNodeWithTag("task_title_1", useUnmergedTree = true).fetchSemanticsNode()
        val titleLayouts = mutableListOf<TextLayoutResult>()
        titleNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(titleLayouts)
        assertTrue(titleLayouts.isNotEmpty())
        assertEquals(onSurface, titleLayouts.first().layoutInput.style.color)

        val expectedDue = "Due: ${NoteCardFormatting.formatDueDate(1795046400000L)}"
        val dueNode = composeTestRule.onNodeWithText(expectedDue, useUnmergedTree = true).fetchSemanticsNode()
        val dueLayouts = mutableListOf<TextLayoutResult>()
        dueNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(dueLayouts)
        assertTrue(dueLayouts.isNotEmpty())
        assertEquals(onSecondaryContainer, dueLayouts.first().layoutInput.style.color)
        assertTrue("Due badge should not use primary accent color", dueLayouts.first().layoutInput.style.color != primary)

        // Trailing chevron navigation cue on 'From: <title>' meets >= 48dp target and navigates
        composeTestRule.onNodeWithTag("task_source_chevron_1", useUnmergedTree = true).assertIsDisplayed()
        val fromButton = composeTestRule.onNodeWithContentDescription("From note Kitchen electrical work, open note")
        fromButton.assertIsDisplayed()
        fromButton.assertHeightIsAtLeast(48.dp)
        fromButton.performClick()

        assertEquals(10L, openedNoteId)
    }

    @Test
    fun tasksScreen_filterChipsSwitchDisplayedTasks() {
        val activeTask = ActionItemWithNote(
            id = 1L,
            noteId = 10L,
            text = "Call electrician",
            done = false,
            noteTitle = "Kitchen electrical work"
        )
        val completedTask = ActionItemWithNote(
            id = 2L,
            noteId = 10L,
            text = "Buy copper wire",
            done = true,
            noteTitle = "Kitchen electrical work"
        )

        composeTestRule.setContent {
            TasksScreen(
                tasks = listOf(activeTask, completedTask),
                onToggleDone = { _, _ -> },
                onOpenNote = {}
            )
        }

        composeTestRule.onNodeWithText("Call electrician").assertIsDisplayed()
        composeTestRule.onNodeWithText("Buy copper wire").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Filter active tasks").performClick()
        composeTestRule.onNodeWithText("Call electrician").assertIsDisplayed()
        composeTestRule.onNodeWithText("Buy copper wire").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Filter completed tasks").performClick()
        composeTestRule.onNodeWithText("Buy copper wire").assertIsDisplayed()
        composeTestRule.onNodeWithText("Call electrician").assertDoesNotExist()
    }
}
