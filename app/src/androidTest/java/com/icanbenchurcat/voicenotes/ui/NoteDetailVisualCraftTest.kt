// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
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

    @Test
    fun noteDetail_bucketPills_have48dpTargets_8dpSeparation_andOutlineContrast() {
        val note = Note(
            id = 11L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/11.m4a",
            title = "Pill sizing note",
            transcript = "Check the pills",
            bucketKind = "TASK",
            tierUsed = "CORE",
            stageStatus = "",
            status = NoteStatus.SORTED.name
        )
        val buckets = listOf(
            Bucket("TASK", "Tasks"),
            Bucket("IDEA", "Ideas"),
            Bucket("LIST", "Lists")
        )
        var density: Density? = null
        var outline = Color.Unspecified
        var background = Color.Unspecified

        composeTestRule.setContent {
            density = LocalDensity.current
            outline = MaterialTheme.colorScheme.outline
            background = MaterialTheme.colorScheme.background
            NoteDetailScreen(note = note, buckets = buckets, onConfirmOrChangeBucket = {})
        }

        // Selected (Tasks) and unselected (Ideas, Lists) pills all meet 48dp.
        listOf("Tasks", "Ideas", "Lists").forEach {
            composeTestRule.onNodeWithText(it).assertHeightIsAtLeast(48.dp)
        }

        // At least 8dp lateral separation between neighbouring pills.
        val d = density!!
        val tasks = composeTestRule.onNodeWithText("Tasks").fetchSemanticsNode().boundsInRoot
        val ideas = composeTestRule.onNodeWithText("Ideas").fetchSemanticsNode().boundsInRoot
        val lists = composeTestRule.onNodeWithText("Lists").fetchSemanticsNode().boundsInRoot
        val minGapPx = with(d) { 8.dp.toPx() }
        assertTrue("Tasks/Ideas gap ${ideas.left - tasks.right}px < $minGapPx", ideas.left - tasks.right >= minGapPx - 0.5f)
        assertTrue("Ideas/Lists gap ${lists.left - ideas.right}px < $minGapPx", lists.left - ideas.right >= minGapPx - 0.5f)

        // Unselected pill outline keeps >= 3:1 contrast against the container (WCAG 1.4.11).
        assertTrue("Outline contrast ${contrast(outline, background)} < 3.0", contrast(outline, background) >= 3.0)
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}
