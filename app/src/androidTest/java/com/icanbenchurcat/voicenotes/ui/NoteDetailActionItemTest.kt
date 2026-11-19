// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class NoteDetailActionItemTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun actionItem_displaysCheckboxAndDueDateBadge_andTogglesDone() {
        val note = Note(
            id = 1L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/1.m4a",
            title = "Meeting follow-up",
            transcript = "Send contract by Friday 5pm",
            bucketKind = "TASK",
            status = NoteStatus.SORTED.name
        )
        // 1700000000000L = Nov 14, 2023 ~10:13 PM UTC
        val dueDateEpochMs = 1700000000000L
        val action = ActionItem(
            id = 42L,
            noteId = 1L,
            text = "Send contract to client",
            dueAtEpochMs = dueDateEpochMs,
            done = false
        )

        var toggledId: Long? = null
        var toggledDone: Boolean? = null

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                actions = listOf(action),
                onToggleActionDone = { id, done ->
                    toggledId = id
                    toggledDone = done
                }
            )
        }

        // Verify action item text and due date badge are rendered
        composeTestRule.onNodeWithText("Send contract to client").assertIsDisplayed()
        val expectedDueDatePrefix = "Due:"
        composeTestRule.onNodeWithText(expectedDueDatePrefix, substring = true).assertIsDisplayed()

        // Toggle checkbox
        composeTestRule.onNodeWithContentDescription("Toggle action item: Send contract to client").performClick()

        assertEquals(42L, toggledId)
        assertEquals(true, toggledDone)
    }

    @Test
    fun actionItem_whenDone_displaysCompletedItemAndCheckbox() {
        val note = Note(
            id = 2L,
            createdAtEpochMs = 2000L,
            audioPath = "/fake/2.m4a",
            title = "Completed action note",
            transcript = "Transcript discussing client contract details",
            bucketKind = "TASK",
            status = NoteStatus.SORTED.name
        )
        val action = ActionItem(
            id = 43L,
            noteId = 2L,
            text = "Review proposal and submit invoice",
            dueAtEpochMs = null,
            done = true
        )

        var toggledId: Long? = null
        var toggledDone: Boolean? = null

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                actions = listOf(action),
                onToggleActionDone = { id, done ->
                    toggledId = id
                    toggledDone = done
                }
            )
        }

        // Verify completed action item text and checkbox are rendered and interactive
        composeTestRule.onNodeWithText("Review proposal and submit invoice").assertIsDisplayed()
        val checkboxNode = composeTestRule.onNodeWithContentDescription("Toggle action item: Review proposal and submit invoice")
        checkboxNode.assertIsDisplayed()
        checkboxNode.performClick()

        assertEquals(43L, toggledId)
        assertEquals(false, toggledDone)
    }

    @Test
    fun actionItem_inlineAddEditDueClearAndDelete_respects48dpTargets() {
        val note = Note(
            id = 3L,
            createdAtEpochMs = 3000L,
            audioPath = "/fake/3.m4a",
            title = "Kitchen electrical work",
            transcript = "Call electrician by Friday at 5pm",
            bucketKind = "TASK",
            status = NoteStatus.SORTED.name
        )
        val friday5pmEpochMs = LocalDateTime.of(2026, 11, 20, 17, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val action = ActionItem(
            id = 44L,
            noteId = 3L,
            text = "Call electrician",
            dueAtEpochMs = friday5pmEpochMs,
            done = false
        )

        var addedText: String? = null
        var addedDue: Long? = -1L
        var updatedId: Long? = null
        var updatedText: String? = null
        var updatedDue: Long? = -1L
        var deletedId: Long? = null

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                actions = listOf(action),
                onAddAction = { text, due ->
                    addedText = text
                    addedDue = due
                },
                onUpdateAction = { id, text, due ->
                    updatedId = id
                    updatedText = text
                    updatedDue = due
                },
                onDeleteAction = { id ->
                    deletedId = id
                }
            )
        }

        // Verify exact shared due formatter output and >= 48dp touch targets
        val expectedDueLabel = "Due: ${NoteCardFormatting.formatDueDate(friday5pmEpochMs)}"
        composeTestRule.onNodeWithText(expectedDueLabel).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Add action item")
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Edit due date: ${NoteCardFormatting.formatDueDate(friday5pmEpochMs)}")
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Edit action item: Call electrician")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Delete action item: Call electrician")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // Add inline action item
        composeTestRule.onNodeWithContentDescription("Add action item").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Action item text input")
            .performScrollTo()
            .performTextInput("Order outlet covers")
        composeTestRule.onNodeWithContentDescription("Save action item").performScrollTo().performClick()
        assertEquals("Order outlet covers", addedText)
        assertNull(addedDue)

        // Tap due badge to open inline edit, update text, clear due date, and save
        composeTestRule.onNodeWithContentDescription("Edit due date: ${NoteCardFormatting.formatDueDate(friday5pmEpochMs)}")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithContentDescription("Pick due date").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Pick due time").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Action item text input")
            .performScrollTo()
            .performTextClearance()
        composeTestRule.onNodeWithContentDescription("Action item text input")
            .performTextInput("Call licensed electrician")
        composeTestRule.onNodeWithContentDescription("Clear due date").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Save action item").performScrollTo().performClick()

        assertEquals(44L, updatedId)
        assertEquals("Call licensed electrician", updatedText)
        assertNull(updatedDue)

        // Delete action item
        composeTestRule.onNodeWithContentDescription("Delete action item: Call electrician")
            .performScrollTo()
            .performClick()
        assertEquals(44L, deletedId)
    }
}
