// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
