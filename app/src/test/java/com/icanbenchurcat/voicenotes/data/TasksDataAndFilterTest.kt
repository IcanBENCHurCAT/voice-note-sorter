// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import com.icanbenchurcat.voicenotes.ui.TaskFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TasksDataAndFilterTest {

    private lateinit var dao: InMemoryFakeNoteDao

    @Before
    fun setUp() {
        dao = InMemoryFakeNoteDao()
    }

    @Test
    fun observeAllActions_returnsTasksJoinedWithNoteTitle_orderedByDoneAndId() = runBlocking {
        val note1 = dao.insert(
            Note(
                id = 10L,
                createdAtEpochMs = 1000L,
                audioPath = "path/1.m4a",
                title = "Sprint Planning"
            )
        )
        val note2 = dao.insert(
            Note(
                id = 20L,
                createdAtEpochMs = 2000L,
                audioPath = "path/2.m4a",
                title = "Grocery Run"
            )
        )

        dao.insertActions(
            listOf(
                ActionItem(id = 1L, noteId = note1, text = "Buy coffee", done = true),
                ActionItem(id = 2L, noteId = note2, text = "Pick up milk", done = false, dueAtEpochMs = 1700000000000L),
                ActionItem(id = 3L, noteId = note1, text = "Draft agenda", done = false)
            )
        )

        val items = dao.observeAllActions().first()

        assertEquals(3, items.size)
        // Not done items first, ordered by id DESC
        assertEquals(3L, items[0].id)
        assertEquals("Draft agenda", items[0].text)
        assertFalse(items[0].done)
        assertEquals("Sprint Planning", items[0].noteTitle)

        assertEquals(2L, items[1].id)
        assertEquals("Pick up milk", items[1].text)
        assertFalse(items[1].done)
        assertEquals(1700000000000L, items[1].dueAtEpochMs)
        assertEquals("Grocery Run", items[1].noteTitle)

        // Done items last
        assertEquals(1L, items[2].id)
        assertEquals("Buy coffee", items[2].text)
        assertTrue(items[2].done)
        assertEquals("Sprint Planning", items[2].noteTitle)
    }

    @Test
    fun updateActionDone_togglesDoneState() = runBlocking {
        val noteId = dao.insert(
            Note(
                id = 1L,
                createdAtEpochMs = 1000L,
                audioPath = "path/1.m4a",
                title = "Review"
            )
        )
        dao.insertActions(
            listOf(
                ActionItem(id = 100L, noteId = noteId, text = "Write unit tests", done = false)
            )
        )

        var items = dao.observeAllActions().first()
        assertFalse(items.first().done)

        // Mark done
        dao.updateActionDone(100L, true)
        items = dao.observeAllActions().first()
        assertTrue(items.first().done)

        // Mark undone
        dao.updateActionDone(100L, false)
        items = dao.observeAllActions().first()
        assertFalse(items.first().done)
    }

    @Test
    fun taskFiltering_activeAndCompleted() {
        val tasks = listOf(
            ActionItemWithNote(id = 1L, noteId = 10L, text = "Task 1", done = false, noteTitle = "Note A"),
            ActionItemWithNote(id = 2L, noteId = 10L, text = "Task 2", done = true, noteTitle = "Note A"),
            ActionItemWithNote(id = 3L, noteId = 20L, text = "Task 3", done = false, noteTitle = "Note B")
        )

        val all = when (TaskFilter.ALL) {
            TaskFilter.ALL -> tasks
            TaskFilter.ACTIVE -> tasks.filter { !it.done }
            TaskFilter.COMPLETED -> tasks.filter { it.done }
        }
        assertEquals(3, all.size)

        val active = when (TaskFilter.ACTIVE) {
            TaskFilter.ALL -> tasks
            TaskFilter.ACTIVE -> tasks.filter { !it.done }
            TaskFilter.COMPLETED -> tasks.filter { it.done }
        }
        assertEquals(2, active.size)
        assertEquals(listOf(1L, 3L), active.map { it.id })

        val completed = when (TaskFilter.COMPLETED) {
            TaskFilter.ALL -> tasks
            TaskFilter.ACTIVE -> tasks.filter { !it.done }
            TaskFilter.COMPLETED -> tasks.filter { it.done }
        }
        assertEquals(1, completed.size)
        assertEquals(listOf(2L), completed.map { it.id })
    }
}
