// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditPersistenceTest {

    @Test
    fun updateTitleAndTranscript_persistsNewValuesAndReflectsInSearch() = runBlocking {
        val dao = InMemoryFakeNoteDao()

        val noteId = dao.insert(
            Note(
                id = 1L,
                createdAtEpochMs = 1000L,
                audioPath = "/fake/sample.m4a",
                title = "UniqueOldTitleTerm",
                summary = "Brief summary",
                transcript = "Original raw transcript content",
                bucketKind = "NOTE",
                tierUsed = "CORE"
            )
        )

        // Verify initial state
        val originalNote = dao.byId(noteId)
        assertEquals("UniqueOldTitleTerm", originalNote?.title)
        assertEquals("Original raw transcript content", originalNote?.transcript)

        // Search for original transcript term
        val searchOriginal = dao.search("transcript").first()
        assertEquals(1, searchOriginal.size)
        assertEquals(noteId, searchOriginal.first().id)

        // Edit title and transcript
        dao.updateTitleAndTranscript(
            noteId = noteId,
            title = "Corrected Title: Project Falcon",
            transcript = "Corrected transcript mentioning supersonic speeds"
        )

        // Verify persisted note
        val updatedNote = dao.byId(noteId)
        assertEquals("Corrected Title: Project Falcon", updatedNote?.title)
        assertEquals("Corrected transcript mentioning supersonic speeds", updatedNote?.transcript)
        // Ensure other fields were preserved
        assertEquals("Brief summary", updatedNote?.summary)
        assertEquals("NOTE", updatedNote?.bucketKind)
        assertEquals("/fake/sample.m4a", updatedNote?.audioPath)

        // Search for new term
        val searchUpdated = dao.search("supersonic").first()
        assertEquals(1, searchUpdated.size)
        assertEquals(noteId, searchUpdated.first().id)

        // Search for old title term should yield nothing (since "UniqueOldTitleTerm" was replaced)
        val searchOldTitle = dao.search("UniqueOldTitleTerm").first()
        assertTrue(searchOldTitle.isEmpty())
    }

    @Test
    fun replaceTagsForNote_addsRemovesAndNormalizesTagsPerNote() = runBlocking {
        val dao = InMemoryFakeNoteDao()

        val noteId = dao.insert(
            Note(
                id = 1L,
                createdAtEpochMs = 1000L,
                audioPath = "/fake/1.m4a",
                title = "Note 1",
                transcript = "Content 1"
            )
        )
        val otherNoteId = dao.insert(
            Note(
                id = 2L,
                createdAtEpochMs = 2000L,
                audioPath = "/fake/2.m4a",
                title = "Note 2",
                transcript = "Content 2"
            )
        )

        dao.replaceTagsForNote(otherNoteId, listOf("keep-me"))
        dao.replaceTagsForNote(noteId, listOf(" #work ", "groceries", "work", "  ", "#"))

        assertEquals(listOf("groceries", "work"), dao.tagsFor(noteId))
        assertEquals(listOf("keep-me"), dao.tagsFor(otherNoteId))

        // Removing a tag and adding a new one replaces only the target note's tags
        dao.replaceTagsForNote(noteId, listOf("work", "urgent"))
        assertEquals(listOf("urgent", "work"), dao.tagsFor(noteId))
        assertEquals(listOf("keep-me"), dao.tagsFor(otherNoteId))

        // Clearing all tags on target note leaves other note untouched
        dao.replaceTagsForNote(noteId, emptyList())
        assertTrue(dao.tagsFor(noteId).isEmpty())
        assertEquals(listOf("keep-me"), dao.tagsFor(otherNoteId))
    }
}
