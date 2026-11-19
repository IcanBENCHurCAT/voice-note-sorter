// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.BucketManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BucketManagementDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun manageBuckets_hidesInternalKinds_usesDone_andExposesDistinctRenameControls() {
        var dismissed = false
        var renamedKind: String? = null
        var renamedName: String? = null

        composeTestRule.setContent {
            BucketManagementDialog(
                buckets = BucketManager.DEFAULT_BUCKETS,
                onDismissRequest = { dismissed = true },
                onRenameBucket = { kind, newName ->
                    renamedKind = kind
                    renamedName = newName
                },
                onAddBucket = { _, _ -> }
            )
        }

        // Human-facing bucket names are displayed
        listOf("Ideas", "Tasks", "Lists", "Notes", "Inbox").forEach { displayName ->
            composeTestRule.onNodeWithText(displayName).assertIsDisplayed()
        }

        // Raw uppercase internal slugs must not be shown
        listOf("IDEA", "TASK", "LIST", "NOTE", "INBOX").forEach { rawKind ->
            composeTestRule.onNodeWithText(rawKind).assertDoesNotExist()
        }

        // No generic repeated "Rename" text buttons; instead distinct per-row accessible controls
        composeTestRule.onNodeWithText("Rename").assertDoesNotExist()
        listOf("Ideas", "Tasks", "Lists", "Notes", "Inbox").forEach { displayName ->
            composeTestRule.onNodeWithContentDescription("Rename $displayName")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }

        // Dismiss button is labeled "Done", not "Cancel"
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()

        // Rename flow for "Ideas" works via its distinct accessible control
        composeTestRule.onNodeWithContentDescription("Rename Ideas").performClick()
        composeTestRule.onNodeWithContentDescription("Bucket rename field").performTextReplacement("Sparks")
        composeTestRule.onNodeWithContentDescription("Save bucket rename").performClick()
        assertEquals("IDEA", renamedKind)
        assertEquals("Sparks", renamedName)

        // Clicking Done invokes onDismissRequest
        composeTestRule.onNodeWithText("Done").performClick()
        assertTrue(dismissed)
    }
}
