// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.data.ActionItemWithNote
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.data.NoteTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        composeTestRule.onNodeWithText("TASK").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Filed as TASK, CORE").assertIsDisplayed()
    }

    @Test
    fun noteCard_withMoreThanThreeTags_showsCollapsedPlusIndicator() {
        val note = Note(
            id = 1L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/1.m4a",
            title = "Tag Overflow Note",
            summary = "Testing hashtag collapsing",
            bucketKind = "IDEA",
            tierUsed = "CORE",
            status = NoteStatus.SORTED.name
        )
        val noteTags = listOf(
            NoteTag(noteId = 1L, tagName = "t1"),
            NoteTag(noteId = 1L, tagName = "t2"),
            NoteTag(noteId = 1L, tagName = "t3"),
            NoteTag(noteId = 1L, tagName = "t4"),
            NoteTag(noteId = 1L, tagName = "t5")
        )

        composeTestRule.setContent {
            NotesListScreen(
                notes = listOf(note),
                noteTags = noteTags,
                onOpen = {}
            )
        }

        // First 3 tags are displayed
        composeTestRule.onNodeWithText("#t1").assertIsDisplayed()
        composeTestRule.onNodeWithText("#t2").assertIsDisplayed()
        composeTestRule.onNodeWithText("#t3").assertIsDisplayed()

        // Remaining tags are collapsed
        composeTestRule.onNodeWithText("#t4").assertDoesNotExist()
        composeTestRule.onNodeWithText("#t5").assertDoesNotExist()

        // Collapsed indicator "+2" is displayed
        composeTestRule.onNodeWithText("+2").assertIsDisplayed()
    }

    @Test
    fun emptyState_clearedNeedsReview_showsAllCaughtUpAndSwitchToAll() {
        val note = Note(
            id = 1L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/1.m4a",
            title = "Sorted Note",
            summary = "Summary of sorted note",
            bucketKind = "TASK",
            status = NoteStatus.SORTED.name
        )

        composeTestRule.setContent {
            NotesListScreen(
                notes = listOf(note),
                onOpen = {}
            )
        }

        // Initially sorted note is visible
        composeTestRule.onNodeWithText("Sorted Note").assertIsDisplayed()

        // Filter by Needs review
        composeTestRule.onNodeWithContentDescription("Filter Needs Review notes").performClick()

        // 0 notes in Needs review -> "All caught up" headline, graphic, and "View all notes" button
        composeTestRule.onNodeWithText("All caught up").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("All caught up").assertIsDisplayed()
        composeTestRule.onNodeWithText("No notes need review right now.").assertIsDisplayed()
        composeTestRule.onNodeWithText("View all notes").assertIsDisplayed()

        // Clicking "View all notes" resets the filter and shows notes
        composeTestRule.onNodeWithText("View all notes").performClick()
        composeTestRule.onNodeWithText("Sorted Note").assertIsDisplayed()
    }

    @Test
    fun tappingNeedsReviewBadgeAndTasksChip_opensNoteDetail() {
        val reviewNote = Note(
            id = 42L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/42.m4a",
            title = "Ambiguous Note",
            summary = "Needs bucket confirmation",
            bucketKind = "INBOX",
            status = NoteStatus.NEEDS_REVIEW.name
        )
        val tasks = listOf(
            ActionItemWithNote(id = 1L, noteId = 42L, text = "Confirm bucket", done = false)
        )
        val openedNotes = mutableListOf<Long>()

        composeTestRule.setContent {
            NotesListScreen(
                notes = listOf(reviewNote),
                tasks = tasks,
                onOpen = { openedNotes.add(it.id) }
            )
        }

        // Tapping the "Needs review" badge opens the note
        composeTestRule.onNodeWithContentDescription("Needs review badge").performClick()
        assertEquals(listOf(42L), openedNotes)

        // Tapping the "Tasks 0/1" chip opens the note
        composeTestRule.onNodeWithText("Tasks 0/1").performClick()
        assertEquals(listOf(42L, 42L), openedNotes)

        // Tapping elsewhere on the card still opens the note
        composeTestRule.onNodeWithText("Ambiguous Note").performClick()
        assertEquals(listOf(42L, 42L, 42L), openedNotes)

        // Top "Needs review (1)" filter chip still toggles the filter
        composeTestRule.onNodeWithContentDescription("Filter Needs Review notes").performClick()
        composeTestRule.onNodeWithText("Ambiguous Note").assertIsDisplayed()
    }

    @Test
    fun emptyLibrary_displaysStructuredPlainLanguageEmptyState() {
        composeTestRule.setContent {
            NotesListScreen(
                notes = emptyList(),
                onOpen = {}
            )
        }

        composeTestRule.onNodeWithText("No voice notes yet").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Tap Record to capture a thought. Everything stays private on this device and sorts automatically into Notes and Tasks."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Device capability:", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    fun filterChips_meet48dpTouchTarget_showLeadingCheckmark_andNeedsReviewUnclipped() {
        val notes = listOf(
            Note(id = 1L, createdAtEpochMs = 1000L, audioPath = "/1.m4a", title = "Idea", bucketKind = "IDEA", status = NoteStatus.SORTED.name),
            Note(id = 2L, createdAtEpochMs = 2000L, audioPath = "/2.m4a", title = "Task", bucketKind = "TASK", status = NoteStatus.NEEDS_REVIEW.name),
            Note(id = 3L, createdAtEpochMs = 3000L, audioPath = "/3.m4a", title = "List", bucketKind = "LIST", status = NoteStatus.SORTED.name),
            Note(id = 4L, createdAtEpochMs = 4000L, audioPath = "/4.m4a", title = "Note", bucketKind = "NOTE", status = NoteStatus.SORTED.name),
            Note(id = 5L, createdAtEpochMs = 5000L, audioPath = "/5.m4a", title = "Inbox 1", bucketKind = "INBOX", status = NoteStatus.SORTED.name),
            Note(id = 6L, createdAtEpochMs = 6000L, audioPath = "/6.m4a", title = "Inbox 2", bucketKind = "INBOX", status = NoteStatus.NEEDS_REVIEW.name)
        )
        val buckets = listOf(
            Bucket("IDEA", "Ideas"),
            Bucket("TASK", "Tasks"),
            Bucket("LIST", "Lists"),
            Bucket("NOTE", "Notes"),
            Bucket("INBOX", "Inbox")
        )
        var density: Density? = null

        composeTestRule.setContent {
            density = LocalDensity.current
            Box(modifier = Modifier.width(360.dp)) {
                NotesListScreen(
                    notes = notes,
                    buckets = buckets,
                    tags = listOf("errands"),
                    noteTags = listOf(NoteTag(noteId = 2L, tagName = "errands")),
                    onOpen = {}
                )
            }
        }

        // Manage buckets is no longer inside NotesListScreen's filter strip
        composeTestRule.onNodeWithText("Manage buckets").assertDoesNotExist()

        // All and Needs review chips show counts, meet >= 48dp touch height, and are unclipped at 360dp width
        val allChip = composeTestRule.onNodeWithContentDescription("Filter all notes")
        val needsReviewChip = composeTestRule.onNodeWithContentDescription("Filter Needs Review notes")
        composeTestRule.onNodeWithText("All (6)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Needs review (2)").assertIsDisplayed()
        allChip.assertHeightIsAtLeast(48.dp)
        needsReviewChip.assertHeightIsAtLeast(48.dp)

        val maxRightPx = with(density!!) { 360.dp.toPx() }
        val needsReviewBounds = needsReviewChip.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Needs review chip right edge (${needsReviewBounds.right}) exceeds 360dp ($maxRightPx)",
            needsReviewBounds.right <= maxRightPx + 0.5f
        )

        // Active All chip displays leading checkmark icon; inactive Needs review chip does not
        composeTestRule.onNode(
            hasTestTag("filter_chip_selected_icon") and hasAnyAncestor(hasContentDescription("Filter all notes")),
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeTestRule.onNode(
            hasTestTag("filter_chip_selected_icon") and hasAnyAncestor(hasContentDescription("Filter Needs Review notes")),
            useUnmergedTree = true
        ).assertDoesNotExist()

        // Selecting Needs review activates its leading checkmark and stays unclipped
        needsReviewChip.performClick()
        composeTestRule.onNode(
            hasTestTag("filter_chip_selected_icon") and hasAnyAncestor(hasContentDescription("Filter Needs Review notes")),
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeTestRule.onNode(
            hasTestTag("filter_chip_selected_icon") and hasAnyAncestor(hasContentDescription("Filter all notes")),
            useUnmergedTree = true
        ).assertDoesNotExist()
        val selectedNeedsReviewBounds = needsReviewChip.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Selected Needs review chip right edge (${selectedNeedsReviewBounds.right}) exceeds 360dp ($maxRightPx)",
            selectedNeedsReviewBounds.right <= maxRightPx + 0.5f
        )

        // Bucket chip meets >= 48dp and displays leading checkmark when selected
        val tasksChip = composeTestRule.onNodeWithContentDescription("Filter by bucket Tasks")
        tasksChip.performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeTestRule.onNode(
            hasTestTag("filter_chip_selected_icon") and hasAnyAncestor(hasContentDescription("Filter by bucket Tasks")),
            useUnmergedTree = true
        ).assertIsDisplayed()

        // Tag chip meets >= 48dp and displays leading checkmark when selected
        val tagChip = composeTestRule.onNodeWithContentDescription("Filter by tag errands")
        tagChip.assertHeightIsAtLeast(48.dp).performClick()
        composeTestRule.onNode(
            hasTestTag("filter_chip_selected_icon") and hasAnyAncestor(hasContentDescription("Filter by tag errands")),
            useUnmergedTree = true
        ).assertIsDisplayed()
    }

    @Test
    fun needsReviewBadge_usesTertiaryTonalTokensInsteadOfErrorRed() {
        var scheme: ColorScheme? = null
        val reviewNote = Note(
            id = 77L,
            createdAtEpochMs = 1000L,
            audioPath = "/77.m4a",
            title = "Needs Triage",
            bucketKind = "INBOX",
            status = NoteStatus.NEEDS_REVIEW.name
        )

        composeTestRule.setContent {
            scheme = MaterialTheme.colorScheme
            NotesListScreen(notes = listOf(reviewNote), onOpen = {})
        }

        val currentScheme = scheme!!
        val (containerColor, labelColor) = needsReviewBadgeColorTokens(currentScheme)
        assertEquals(currentScheme.tertiaryContainer, containerColor)
        assertEquals(currentScheme.onTertiaryContainer, labelColor)
        assertNotEquals(currentScheme.errorContainer, containerColor)
        composeTestRule.onNodeWithContentDescription("Needs review badge").assertIsDisplayed()
    }

    @Test
    fun rootTopAppBar_exposesManageBucketsInOverflowMenu() {
        var manageBucketsClicked = false

        composeTestRule.setContent {
            RootTopAppBar(onManageBuckets = { manageBucketsClicked = true })
        }

        val overflowButton = composeTestRule.onNodeWithContentDescription("More options")
        overflowButton.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()

        composeTestRule.onNodeWithText("Manage buckets").assertIsDisplayed().performClick()
        assertTrue(manageBucketsClicked)
    }

    @Test
    fun notesList_rendersSearchBarWithLeadingIcon_andScopedTagsDuringNeedsReviewFilter() {
        val reviewNote = Note(
            id = 1L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/1.m4a",
            title = "Kitchen electrical work",
            summary = "Call electrician",
            bucketKind = "TASK",
            status = NoteStatus.NEEDS_REVIEW.name
        )
        val sortedNote = Note(
            id = 2L,
            createdAtEpochMs = 2000L,
            audioPath = "/fake/2.m4a",
            title = "Grocery run",
            summary = "Buy milk and bread",
            bucketKind = "LIST",
            status = NoteStatus.SORTED.name
        )
        val noteTags = listOf(
            NoteTag(noteId = 1L, tagName = "errands"),
            NoteTag(noteId = 2L, tagName = "groceries"),
            NoteTag(noteId = 2L, tagName = "product")
        )

        composeTestRule.setContent {
            NotesListScreen(
                notes = listOf(reviewNote, sortedNote),
                tags = listOf("errands", "groceries", "product"),
                noteTags = noteTags,
                onOpen = {}
            )
        }

        // Rounded search bar with leading Search icon is displayed
        composeTestRule.onNodeWithTag("notes_search_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("notes_search_icon", useUnmergedTree = true).assertIsDisplayed()

        // Direct bucket filter chip is visible without Manage Buckets blocking it
        composeTestRule.onNodeWithContentDescription("Filter by bucket Ideas").assertIsDisplayed()

        // Initially all tags are visible
        composeTestRule.onNodeWithContentDescription("Filter by tag errands").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Filter by tag groceries").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Filter by tag product").assertIsDisplayed()

        // Filter to Needs review -> irrelevant tags from sorted notes disappear
        composeTestRule.onNodeWithContentDescription("Filter Needs Review notes").performClick()
        composeTestRule.onNodeWithContentDescription("Filter by tag errands").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Filter by tag groceries").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Filter by tag product").assertDoesNotExist()
    }

    @Test
    fun noteCard_longSummary_truncatesWithEllipsisAtTwoLines() {
        val longSummary = "Concept for a modular voice note sorting pipeline with an open core and extension seams for closed plugins across multiple local device capability tiers."
        val note = Note(
            id = 7L,
            createdAtEpochMs = 1000L,
            audioPath = "/fake/7.m4a",
            title = "Modular voice note sorting pipeline",
            summary = longSummary,
            bucketKind = "IDEA",
            status = NoteStatus.SORTED.name
        )

        composeTestRule.setContent {
            NotesListScreen(
                notes = listOf(note),
                onOpen = {}
            )
        }

        val node = composeTestRule.onNodeWithTag("notes_summary_7", useUnmergedTree = true).fetchSemanticsNode()
        val layoutResults = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layoutResults)
        assertTrue("Expected text layout result for summary", layoutResults.isNotEmpty())
        val layout = layoutResults.first()
        assertTrue("Expected summary to truncate at 2 lines", layout.lineCount <= 2)
        assertTrue("Expected truncated summary to report visual overflow/ellipsis", layout.isLineEllipsized(layout.lineCount - 1))
    }
}
