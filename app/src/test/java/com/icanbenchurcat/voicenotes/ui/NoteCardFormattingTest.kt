// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.NoteTag
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

class NoteCardFormattingTest {

    @Test
    fun deduplicateSummary_identicalTitleAndSummary_returnsEmpty() {
        val result = NoteCardFormatting.deduplicateSummary(
            title = "Pick up dry cleaning",
            summary = "Pick up dry cleaning."
        )
        assertEquals("", result)
    }

    @Test
    fun deduplicateSummary_summaryStartsWithTitle_stripsTitlePrefix() {
        val result = NoteCardFormatting.deduplicateSummary(
            title = "Pick up dry cleaning",
            summary = "Pick up dry cleaning. Remember ticket 452 on the counter."
        )
        assertEquals("Remember ticket 452 on the counter.", result)
    }

    @Test
    fun deduplicateSummary_distinctSummary_returnsFullSummary() {
        val result = NoteCardFormatting.deduplicateSummary(
            title = "Team sync meeting",
            summary = "Discuss quarterly roadmap milestones and budget allocation."
        )
        assertEquals("Discuss quarterly roadmap milestones and budget allocation.", result)
    }

    @Test
    fun deduplicateSummary_emptySummary_returnsEmpty() {
        val result = NoteCardFormatting.deduplicateSummary(
            title = "Some Title",
            summary = ""
        )
        assertEquals("", result)
    }

    @Test
    fun formatTimestamp_relativeTimes() {
        val now = 1000000000000L
        assertEquals("Just now", NoteCardFormatting.formatTimestamp(now - 30 * 1000, now))
        assertEquals("5m ago", NoteCardFormatting.formatTimestamp(now - 5 * 60 * 1000, now))
        assertEquals("2h ago", NoteCardFormatting.formatTimestamp(now - 2 * 3600 * 1000, now))
        assertEquals("Yesterday", NoteCardFormatting.formatTimestamp(now - 25 * 3600 * 1000, now))
        assertEquals("3d ago", NoteCardFormatting.formatTimestamp(now - 3 * 86400 * 1000, now))
    }

    @Test
    fun formatPipelineStages_parsesRawDebugString() {
        val stages = NoteCardFormatting.formatPipelineStages("transcribe:OK,title:OK,actions:OK,bucket:OK")
        assertEquals(4, stages.size)
        assertEquals(Pair("Transcribe", "OK"), stages[0])
        assertEquals(Pair("Title", "OK"), stages[1])
        assertEquals(Pair("Actions", "OK"), stages[2])
        assertEquals(Pair("Bucket", "OK"), stages[3])
    }

    @Test
    fun collapseTags_lessThanOrEqualToMax_returnsAllTagsAndZeroOverflow() {
        val (emptyTags, emptyOverflow) = NoteCardFormatting.collapseTags(emptyList(), maxVisible = 3)
        assertEquals(emptyList<String>(), emptyTags)
        assertEquals(0, emptyOverflow)

        val twoTags = listOf("work", "urgent")
        val (visible2, overflow2) = NoteCardFormatting.collapseTags(twoTags, maxVisible = 3)
        assertEquals(listOf("work", "urgent"), visible2)
        assertEquals(0, overflow2)

        val threeTags = listOf("tag1", "tag2", "tag3")
        val (visible3, overflow3) = NoteCardFormatting.collapseTags(threeTags, maxVisible = 3)
        assertEquals(listOf("tag1", "tag2", "tag3"), visible3)
        assertEquals(0, overflow3)
    }

    @Test
    fun collapseTags_moreThanMax_collapsesOverflow() {
        val fiveTags = listOf("one", "two", "three", "four", "five")
        val (visible5, overflow5) = NoteCardFormatting.collapseTags(fiveTags, maxVisible = 3)
        assertEquals(listOf("one", "two", "three"), visible5)
        assertEquals(2, overflow5)

        val fourTags = listOf("a", "b", "c", "d")
        val (visible4, overflow4) = NoteCardFormatting.collapseTags(fourTags, maxVisible = 3)
        assertEquals(listOf("a", "b", "c"), visible4)
        assertEquals(1, overflow4)
    }

    @Test
    fun formatBucketDisplayName_resolvesDefaultsCustomNamesAndFallbacks() {
        assertEquals("Ideas", NoteCardFormatting.formatBucketDisplayName("IDEA"))
        assertEquals("Tasks", NoteCardFormatting.formatBucketDisplayName("TASK"))
        assertEquals("Lists", NoteCardFormatting.formatBucketDisplayName("LIST"))
        assertEquals("Notes", NoteCardFormatting.formatBucketDisplayName("NOTE"))
        assertEquals("Inbox", NoteCardFormatting.formatBucketDisplayName("INBOX"))

        val renamedBuckets = listOf(
            Bucket("TASK", "Action Items"),
            Bucket("CUSTOM_PROJ", "Client Projects")
        )
        assertEquals("Action Items", NoteCardFormatting.formatBucketDisplayName("TASK", renamedBuckets))
        assertEquals("Client Projects", NoteCardFormatting.formatBucketDisplayName("CUSTOM_PROJ", renamedBuckets))
        assertEquals("Work Projects", NoteCardFormatting.formatBucketDisplayName("WORK_PROJECTS"))
    }

    @Test
    fun formatTierLabel_mapsKnownTiersCaseInsensitiveAndTitleCasesUnknown() {
        assertEquals("Full on-device AI", NoteCardFormatting.formatTierLabel("FULL"))
        assertEquals("Full on-device AI", NoteCardFormatting.formatTierLabel(" full "))
        assertEquals("Core sorting", NoteCardFormatting.formatTierLabel("CORE"))
        assertEquals("Core sorting", NoteCardFormatting.formatTierLabel("core"))
        assertEquals("Basic mode", NoteCardFormatting.formatTierLabel("MINIMAL"))
        assertEquals("Basic mode", NoteCardFormatting.formatTierLabel(" minimal "))
        assertEquals("Basic mode", NoteCardFormatting.formatTierLabel(""))
        assertEquals("Basic mode", NoteCardFormatting.formatTierLabel("   "))
        assertEquals("Custom Tier", NoteCardFormatting.formatTierLabel("CUSTOM_TIER"))
    }

    @Test
    fun formatDueDate_formatsMonthDayAndTimeConsistently() {
        val tz = TimeZone.getTimeZone("America/New_York")
        val zone = tz.toZoneId()
        val friday5pmEpochMs = LocalDateTime.of(2026, 11, 20, 17, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        assertEquals(
            "Nov 20, 5:00 PM",
            NoteCardFormatting.formatDueDate(friday5pmEpochMs, Locale.US, tz)
        )
        assertEquals(
            "Nov 20, 2026",
            NoteCardFormatting.formatDueDateOnly(friday5pmEpochMs, Locale.US, tz)
        )
        assertEquals(
            "5:00 PM",
            NoteCardFormatting.formatDueTimeOnly(friday5pmEpochMs, Locale.US, tz)
        )
        assertEquals("", NoteCardFormatting.formatDueDate(0L, Locale.US, tz))
        assertEquals("", NoteCardFormatting.formatDueDate(-100L, Locale.US, tz))
    }

    @Test
    fun mergePickedDateAndTime_handlesNegativeUtcOffsetWithoutOffByOneDay() {
        val nyZone = ZoneId.of("America/New_York")
        val initialFriday5pm = LocalDateTime.of(2026, 11, 20, 17, 0)
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()

        // Material 3 DatePicker returns UTC midnight for Nov 23, 2026
        val nov23UtcMidnight = LocalDate.of(2026, 11, 23)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        // Updating date preserves existing 5:00 PM local time and lands on Nov 23 (not Nov 22!)
        val updatedDate = NoteCardFormatting.mergePickedDate(
            existingDueAtEpochMs = initialFriday5pm,
            utcSelectedDateMillis = nov23UtcMidnight,
            zone = nyZone
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 23, 17, 0),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(updatedDate), nyZone)
        )

        // Updating time preserves Nov 23 date
        val clock = Clock.fixed(Instant.ofEpochMilli(updatedDate), nyZone)
        val updatedTime = NoteCardFormatting.mergePickedTime(
            existingDueAtEpochMs = updatedDate,
            hour = 14,
            minute = 30,
            clock = clock
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 23, 14, 30),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(updatedTime), nyZone)
        )

        // Converting back to DatePicker initial UTC millis gives UTC midnight of Nov 23
        assertEquals(
            nov23UtcMidnight,
            NoteCardFormatting.toDatePickerInitialUtcMillis(updatedTime, clock)
        )
    }

    @Test
    fun scopeVisibleTags_filtersToTagsPresentOnVisibleNotes() {
        val allTags = listOf("errands", "groceries", "product", "renovation")
        val noteTags = listOf(
            NoteTag(noteId = 1L, tagName = "product"),
            NoteTag(noteId = 2L, tagName = "errands"),
            NoteTag(noteId = 2L, tagName = "renovation"),
            NoteTag(noteId = 3L, tagName = "groceries")
        )

        val scoped = NoteCardFormatting.scopeVisibleTags(
            allTags = allTags,
            visibleNoteIds = setOf(2L),
            noteTags = noteTags
        )

        assertEquals(listOf("errands", "renovation"), scoped)
    }

    @Test
    fun scopeVisibleTags_preservesSelectedTagWhenVisibleNotesEmpty() {
        val allTags = listOf("errands", "groceries")
        val noteTags = listOf(NoteTag(noteId = 1L, tagName = "groceries"))

        val scoped = NoteCardFormatting.scopeVisibleTags(
            allTags = allTags,
            visibleNoteIds = emptySet(),
            noteTags = noteTags,
            selectedTag = "errands"
        )

        assertEquals(listOf("errands"), scoped)
    }

    @Test
    fun scopeVisibleTags_emptyAllTags_returnsEmpty() {
        val scoped = NoteCardFormatting.scopeVisibleTags(
            allTags = emptyList(),
            visibleNoteIds = setOf(1L),
            noteTags = listOf(NoteTag(noteId = 1L, tagName = "work"))
        )
        assertEquals(emptyList<String>(), scoped)
    }
}
