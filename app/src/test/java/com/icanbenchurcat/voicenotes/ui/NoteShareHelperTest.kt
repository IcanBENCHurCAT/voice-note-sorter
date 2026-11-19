// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteShareHelperTest {

    @Test
    fun formatShareText_formatsTitleSummaryAndTranscript() {
        val note = Note(
            id = 1L,
            createdAtEpochMs = 123456789L,
            audioPath = "/fake/audio.m4a",
            title = "Weekly Standup Notes",
            summary = "Discussed Q4 roadmap and launch dates.",
            transcript = "We talked about the Q4 launch and everyone agreed on November 15."
        )

        val formatted = NoteShareHelper.formatShareText(note)

        val expected = """
            Weekly Standup Notes

            Summary: Discussed Q4 roadmap and launch dates.

            Transcript: We talked about the Q4 launch and everyone agreed on November 15.
        """.trimIndent()

        assertEquals(expected, formatted)
    }

    @Test
    fun formatShareText_omitsEmptyFieldsGracefully() {
        val noteWithoutSummary = Note(
            id = 2L,
            createdAtEpochMs = 123456789L,
            audioPath = "/fake/audio.m4a",
            title = "Quick Thought",
            summary = "",
            transcript = "Remember to buy milk."
        )

        val formatted = NoteShareHelper.formatShareText(noteWithoutSummary)

        val expected = """
            Quick Thought

            Transcript: Remember to buy milk.
        """.trimIndent()

        assertEquals(expected, formatted)
    }

    @Test
    fun formatShareText_handlesOnlyTranscript() {
        val noteTranscriptOnly = Note(
            id = 3L,
            createdAtEpochMs = 123456789L,
            audioPath = "/fake/audio.m4a",
            title = "",
            summary = "",
            transcript = "Raw voice memo without title or summary."
        )

        val formatted = NoteShareHelper.formatShareText(noteTranscriptOnly)
        assertEquals("Transcript: Raw voice memo without title or summary.", formatted)
    }

    @Test
    fun formatShareText_handlesBlankTitleAndTranscriptGracefully() {
        val emptyNote = Note(
            id = 4L,
            createdAtEpochMs = 123456789L,
            audioPath = "/fake/audio.m4a",
            title = "",
            summary = "",
            transcript = ""
        )

        val formatted = NoteShareHelper.formatShareText(emptyNote)
        assertEquals("", formatted)
    }

    @Test
    fun formatShareText_formatsImageNoteWithoutAudioPath() {
        val imageNote = Note(
            id = 5L,
            createdAtEpochMs = 123456789L,
            sourceType = NoteSourceType.IMAGE.name,
            imagePath = "/data/user/0/app/files/images/scan.jpg",
            title = "Whiteboard Architecture",
            summary = "Three-tier OCR pipeline.",
            transcript = "Tier 3 Nano ImagePart -> Tier 2 Bundled OCR -> Tier 1 Review"
        )

        val formatted = NoteShareHelper.formatShareText(imageNote)
        val expected = """
            Whiteboard Architecture

            Summary: Three-tier OCR pipeline.

            Transcript: Tier 3 Nano ImagePart -> Tier 2 Bundled OCR -> Tier 1 Review
        """.trimIndent()
        assertEquals(expected, formatted)
    }
}
