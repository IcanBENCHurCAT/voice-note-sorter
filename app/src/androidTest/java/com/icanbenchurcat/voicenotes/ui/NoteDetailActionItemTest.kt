// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
            transcript = "Review proposal and submit invoice",
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
}
