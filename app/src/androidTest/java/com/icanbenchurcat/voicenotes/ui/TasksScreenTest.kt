// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.data.ActionItemWithNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
