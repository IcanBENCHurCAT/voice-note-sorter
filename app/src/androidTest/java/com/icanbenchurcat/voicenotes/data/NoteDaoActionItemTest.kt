// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDaoActionItemTest {

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
    fun insertUpdateAndDeleteActionItem_roomRoundTrip() = runBlocking {
        val noteId = dao.insert(
            Note(
                createdAtEpochMs = 1_000L,
                audioPath = "/audio/1.m4a",
                title = "Kitchen electrical work"
            )
        )

        val initialDue = 1_795_298_800_000L
        val actionId = dao.insertAction(
            ActionItem(
                noteId = noteId,
                text = "Call electrician",
                dueAtEpochMs = initialDue,
                done = false
            )
        )
        assertTrue(actionId > 0L)

        val inserted = dao.actionById(actionId)
        assertNotNull(inserted)
        assertEquals("Call electrician", inserted?.text)
        assertEquals(initialDue, inserted?.dueAtEpochMs)

        dao.updateActionDone(actionId, true)
        val updatedDue = initialDue + 7_200_000L
        dao.updateAction(actionId, "Call electrician before weekend", updatedDue)

        val updated = dao.actionById(actionId)
        assertEquals("Call electrician before weekend", updated?.text)
        assertEquals(updatedDue, updated?.dueAtEpochMs)
        assertTrue(updated!!.done)

        dao.updateAction(actionId, "Call electrician before weekend", null)
        assertNull(dao.actionById(actionId)?.dueAtEpochMs)

        dao.deleteActionById(actionId)
        assertNull(dao.actionById(actionId))
        assertTrue(dao.actionsFor(noteId).isEmpty())
        assertTrue(dao.observeAllActions().first().isEmpty())
    }
}
