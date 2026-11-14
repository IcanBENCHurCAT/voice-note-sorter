// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
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
        composeTestRule.onNodeWithContentDescription("Share note").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("More options").assertHeightIsAtLeast(48.dp)

    }

    @Test
    fun noteDetail_topBarActions_shareAndOverflowDelete() {
        val note = Note(
            id = 20L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/20.m4a",
            title = "Top bar actions note",
            transcript = "Testing share and delete",
            bucketKind = "TASK",
            tierUsed = "CORE",
            stageStatus = "",
            status = NoteStatus.SORTED.name
        )
        var sharedText = false
        var sharedAudio = false
        var deletedNote = false

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                hasAudioFile = true,
                onShareText = { sharedText = true },
                onShareAudio = { sharedAudio = true },
                onDeleteNote = { deletedNote = true }
            )
        }

        // Top bar Share icon action
        composeTestRule.onNodeWithContentDescription("Share note").assertIsDisplayed().performClick()
        // Dropdown displays Share text and Share audio options
        composeTestRule.onNodeWithText("Share text").assertIsDisplayed().performClick()
        assertTrue("Share text callback should be invoked", sharedText)

        // Overflow menu for Delete
        composeTestRule.onNodeWithContentDescription("More options").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Delete note").assertIsDisplayed().performClick()

        // Confirmation dialog is shown
        composeTestRule.onNodeWithText("Delete note?").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Confirm delete note").performClick()
        assertTrue("onDeleteNote callback should be invoked", deletedNote)
    }

    @Test
    fun noteDetail_shareIcon_withoutAudio_invokesShareTextDirectly() {
        val note = Note(
            id = 21L,
            createdAtEpochMs = 1000L,
            audioPath = "",
            title = "Text only note",
            transcript = "Only text here",
            bucketKind = "NOTE",
            tierUsed = "CORE",
            stageStatus = "",
            status = NoteStatus.SORTED.name
        )
        var sharedText = false

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                hasAudioFile = false,
                onShareText = { sharedText = true }
            )
        }

        // Tapping Share note directly shares text when no audio file exists
        composeTestRule.onNodeWithContentDescription("Share note").assertIsDisplayed().performClick()
        assertTrue("Share text callback should be invoked directly", sharedText)
    }

    @Test
    fun noteDetail_needsReview_prominentConfirmFilingAction() {
        val note = Note(
            id = 22L,
            createdAtEpochMs = 1000L,
            audioPath = "",
            title = "Review note",
            transcript = "Needs confirmation",
            bucketKind = "TASK",
            tierUsed = "CORE",
            stageStatus = "",
            status = NoteStatus.NEEDS_REVIEW.name
        )
        var confirmedBucket: String? = null

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                onConfirmOrChangeBucket = { confirmedBucket = it }
            )
        }

        // The single prominent "Confirm filing" action
        composeTestRule.onNodeWithContentDescription("Confirm bucket filing")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals("TASK", confirmedBucket)
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

    @Test
    fun noteDetail_whenAudioFileMissing_surfacesPlaybackCardInErrorState() {
        val note = Note(
            id = 12L,
            createdAtEpochMs = 1000L,
            audioPath = "/missing/audio_file.m4a",
            title = "Missing audio note",
            transcript = "Transcript content",
            bucketKind = "IDEA",
            tierUsed = "CORE",
            stageStatus = "",
            status = NoteStatus.SORTED.name
        )

        composeTestRule.setContent {
            NoteDetailScreen(
                note = note,
                buckets = listOf(Bucket("IDEA", "Ideas")),
                hasAudioFile = false,
                onConfirmOrChangeBucket = {}
            )
        }

        // Playback card header is surfaced
        composeTestRule.onNodeWithText("Original audio").assertIsDisplayed()
        // Error message indicating audio recording is unavailable on device is surfaced
        composeTestRule.onNodeWithText("Audio recording not available on device").assertIsDisplayed()
    }

    @Test
    fun noteDetail_withValidAudio_showsDurationBeforePlayPressed() {
        val note = Note(
            id = 13L,
            createdAtEpochMs = 1000L,
            audioPath = "/valid/audio_file.m4a",
            title = "Recorded note",
            transcript = "I need to do something sweet for Sarah this week.",
            bucketKind = "TASK",
            tierUsed = "CORE",
            stageStatus = "",
            status = NoteStatus.SORTED.name
        )

        composeTestRule.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val playbackState = androidx.compose.runtime.remember {
                com.icanbenchurcat.voicenotes.audio.AudioPlaybackState(
                    audioPath = note.audioPath,
                    coroutineScope = scope,
                    fileExistsChecker = { true },
                    durationReader = { 65_000 }
                )
            }
            NoteDetailScreen(
                note = note,
                buckets = listOf(Bucket("TASK", "Tasks")),
                hasAudioFile = true,
                audioPlaybackState = playbackState,
                onConfirmOrChangeBucket = {}
            )
        }

        composeTestRule.onNodeWithText("Original audio").assertIsDisplayed()
        composeTestRule.onNodeWithText("Play").assertIsDisplayed()
        composeTestRule.onNodeWithText("0:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("1:05").assertIsDisplayed()
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}
