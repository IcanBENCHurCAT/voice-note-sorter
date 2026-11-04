// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SearchRepositoryTest {

    private lateinit var dao: InMemoryFakeNoteDao
    private lateinit var searchRepo: DefaultSearchRepository

    @Before
    fun setUp() {
        dao = InMemoryFakeNoteDao()
        searchRepo = DefaultSearchRepository(dao)
    }

    @Test
    fun blankQueryReturnsAllNotes() = runBlocking {
        dao.insert(Note(createdAtEpochMs = 1000L, audioPath = "", title = "Note 1"))
        dao.insert(Note(createdAtEpochMs = 2000L, audioPath = "", title = "Note 2"))

        val results = searchRepo.search("").first()
        assertEquals(2, results.size)
    }

    @Test
    fun searchMatchesTermsInTitleOrTranscript() = runBlocking {
        dao.insert(Note(createdAtEpochMs = 1000L, audioPath = "", title = "Project Phoenix", transcript = "Launch plan"))
        dao.insert(Note(createdAtEpochMs = 2000L, audioPath = "", title = "Grocery shopping", transcript = "Milk and eggs"))

        val phoenixResults = searchRepo.search("Phoenix").first()
        assertEquals(1, phoenixResults.size)
        assertEquals("Project Phoenix", phoenixResults[0].title)

        val groceryResults = searchRepo.search("milk").first()
        assertEquals(1, groceryResults.size)
        assertEquals("Grocery shopping", groceryResults[0].title)
    }

    @Test
    fun searchWithNoMatchReturnsEmptyList() = runBlocking {
        dao.insert(Note(createdAtEpochMs = 1000L, audioPath = "", title = "Alpha"))

        val results = searchRepo.search("Beta").first()
        assertEquals(0, results.size)
    }
}
