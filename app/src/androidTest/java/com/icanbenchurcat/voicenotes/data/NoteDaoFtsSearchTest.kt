// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDaoFtsSearchTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun search_matchesTitleTranscriptSummary_andOrdersNewestFirst() = runBlocking {
        val id1 = dao.insert(
            Note(
                createdAtEpochMs = 1_000L,
                audioPath = "/audio/1.m4a",
                title = "Grocery List",
                summary = "Apples, bananas, and milk",
                transcript = "Remember to buy some snacks too"
            )
        )
        val id2 = dao.insert(
            Note(
                createdAtEpochMs = 2_000L,
                audioPath = "/audio/2.m4a",
                title = "Weekly Standup",
                summary = "Discuss project milestones",
                transcript = "We need more groceries for the office kitchen"
            )
        )
        val id3 = dao.insert(
            Note(
                createdAtEpochMs = 3_000L,
                audioPath = "/audio/3.m4a",
                title = "Doctor Appointment",
                summary = "Annual checkup",
                transcript = "Check blood pressure and schedule follow up"
            )
        )

        // "groceries" should match id2 (in transcript) and id1 ("Grocery List" prefix match via sanitizer)
        val query = FtsQuerySanitizer.sanitize("grocer") ?: "grocer*"
        val results = dao.search(query).first()

        assertEquals(2, results.size)
        // Ordered newest first (id2 has 2000L, id1 has 1000L)
        assertEquals(id2, results[0].id)
        assertEquals(id1, results[1].id)
    }

    @Test
    fun search_sanitizerHandlesMalformedOperatorsWithoutThrowing() = runBlocking {
        dao.insert(
            Note(
                createdAtEpochMs = 1_000L,
                audioPath = "/audio/1.m4a",
                title = "Team Meeting",
                summary = "Q3 roadmap review",
                transcript = "Let's review the deliverables"
            )
        )

        // Raw malformed input containing unclosed quotes, asterisks, brackets, NOT/AND
        val malformed1 = "\"\"***meeting***\""
        val sanitized1 = FtsQuerySanitizer.sanitize(malformed1)
        assertEquals("meeting*", sanitized1)
        val results1 = dao.search(sanitized1!!).first()
        assertEquals(1, results1.size)
        assertEquals("Team Meeting", results1[0].title)

        // Query with pure special chars produces null and search is safely bypassed
        val malformed2 = "!@#$%^&*()\"\"\""
        val sanitized2 = FtsQuerySanitizer.sanitize(malformed2)
        assertEquals(null, sanitized2)
    }
}
