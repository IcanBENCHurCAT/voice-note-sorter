// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotesViewModelTest {

    private lateinit var dao: InMemoryFakeNoteDao
    private lateinit var viewModel: NotesViewModel

    @Before
    fun setUp() {
        dao = InMemoryFakeNoteDao()
    }

    @Test
    fun notes_initiallyObservesAllNotesInDao() = runTest {
        val note1 = Note(id = 1L, createdAtEpochMs = 1000L, audioPath = "/fake/1.m4a", title = "First note", summary = "Summary 1", transcript = "Transcript 1")
        val note2 = Note(id = 2L, createdAtEpochMs = 2000L, audioPath = "/fake/2.m4a", title = "Second note", summary = "Summary 2", transcript = "Transcript 2")
        dao.insert(note1)
        dao.insert(note2)

        viewModel = NotesViewModel(dao)

        val initialNotes = viewModel.notes.first()
        assertEquals(2, initialNotes.size)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun onSearchQueryChange_withMatchingQuery_returnsFilteredResults() = runTest {
        val note1 = Note(id = 1L, createdAtEpochMs = 1000L, audioPath = "/fake/1.m4a", title = "Buy groceries", summary = "Apples and milk", transcript = "Need groceries")
        val note2 = Note(id = 2L, createdAtEpochMs = 2000L, audioPath = "/fake/2.m4a", title = "Project ideas", summary = "Kotlin multiplatform", transcript = "Coding thoughts")
        dao.insert(note1)
        dao.insert(note2)

        viewModel = NotesViewModel(dao)

        viewModel.onSearchQueryChange("groceries")
        val results = viewModel.notes.first()
        assertEquals(1, results.size)
        assertEquals("Buy groceries", results[0].title)
    }

    @Test
    fun onSearchQueryChange_cleared_restoresAllNotes() = runTest {
        val note1 = Note(id = 1L, createdAtEpochMs = 1000L, audioPath = "/fake/1.m4a", title = "Buy groceries", summary = "Apples and milk", transcript = "Need groceries")
        val note2 = Note(id = 2L, createdAtEpochMs = 2000L, audioPath = "/fake/2.m4a", title = "Project ideas", summary = "Kotlin multiplatform", transcript = "Coding thoughts")
        dao.insert(note1)
        dao.insert(note2)

        viewModel = NotesViewModel(dao)

        viewModel.onSearchQueryChange("groceries")
        assertEquals(1, viewModel.notes.first().size)

        viewModel.onSearchQueryChange("")
        val restored = viewModel.notes.first()
        assertEquals(2, restored.size)
    }

    @Test
    fun onSearchQueryChange_noMatches_returnsEmptyList() = runTest {
        val note1 = Note(id = 1L, createdAtEpochMs = 1000L, audioPath = "/fake/1.m4a", title = "Buy groceries", summary = "Apples and milk", transcript = "Need groceries")
        dao.insert(note1)

        viewModel = NotesViewModel(dao)

        viewModel.onSearchQueryChange("dentist appointment")
        val results = viewModel.notes.first()
        assertTrue(results.isEmpty())
    }

    @Test
    fun onSearchQueryChange_malformedQuery_doesNotCrashAndHandlesSafely() = runTest {
        val note1 = Note(id = 1L, createdAtEpochMs = 1000L, audioPath = "/fake/1.m4a", title = "Buy groceries", summary = "Apples and milk", transcript = "Need groceries")
        dao.insert(note1)

        viewModel = NotesViewModel(dao)

        // Stray quotes, asterisks, punctuation
        viewModel.onSearchQueryChange("\"\"***!!!")
        val results = viewModel.notes.first()
        assertTrue(results.isEmpty())

        // Keyword query with punctuation
        viewModel.onSearchQueryChange("\"groceries\"***")
        val matchingResults = viewModel.notes.first()
        assertEquals(1, matchingResults.size)
        assertEquals("Buy groceries", matchingResults[0].title)
    }
}
