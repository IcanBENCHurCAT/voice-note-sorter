// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteDeletionCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deleteNote_removesNoteRow_actions_tags_andAudioFile() = runBlocking {
        val dao = InMemoryFakeNoteDao()
        val audioDir = tempFolder.newFolder("audio")
        val audioFile = File(audioDir, "note_1.m4a").apply { writeText("audio-bytes") }
        assertTrue(audioFile.exists())

        val noteId = dao.insert(
            Note(
                id = 1L,
                createdAtEpochMs = 1000L,
                audioPath = audioFile.absolutePath,
                title = "Secret meeting",
                transcript = "Top secret project discussion",
                summary = "Project secrets",
                bucketKind = "NOTE",
                tierUsed = "FULL"
            )
        )

        dao.insertActions(
            listOf(
                ActionItem(noteId = noteId, text = "Follow up with team"),
                ActionItem(noteId = noteId, text = "Delete files")
            )
        )
        dao.insertTags(listOf(Tag("confidential"), Tag("work")))
        dao.insertNoteTags(
            listOf(
                NoteTag(noteId = noteId, tagName = "confidential"),
                NoteTag(noteId = noteId, tagName = "work")
            )
        )

        // Verify initial state
        assertNotNull(dao.byId(noteId))
        assertEquals(2, dao.actionsFor(noteId).size)
        assertEquals(listOf("confidential", "work"), dao.tagsFor(noteId))
        assertEquals(1, dao.search("secret").first().size)

        val coordinator = NoteDeletionCoordinator(dao, audioDir)
        val result = coordinator.deleteNote(noteId)

        assertTrue("Expected deleteNote to return true", result)

        // Verify complete cascade
        assertNull("Note row should be deleted", dao.byId(noteId))
        assertTrue("Linked actions should be deleted", dao.actionsFor(noteId).isEmpty())
        assertTrue("Linked tags should be deleted", dao.tagsFor(noteId).isEmpty())
        assertTrue("FTS search should return nothing", dao.search("secret").first().isEmpty())
        assertFalse("Physical audio file should be deleted", audioFile.exists())
    }

    @Test
    fun deleteNote_survivesNonExistentFile() = runBlocking {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(
            Note(
                id = 2L,
                createdAtEpochMs = 2000L,
                audioPath = "/path/does/not/exist/missing.m4a",
                title = "Missing audio",
                transcript = "Testing missing file deletion"
            )
        )

        val coordinator = NoteDeletionCoordinator(dao)
        val result = coordinator.deleteNote(noteId)

        assertTrue(result)
        assertNull(dao.byId(noteId))
    }

    @Test
    fun deleteAllNotes_removesAllNotes_actions_tags_andAudioFiles() = runBlocking {
        val dao = InMemoryFakeNoteDao()
        val audioDir = tempFolder.newFolder("private_audio")

        val file1 = File(audioDir, "note_10.m4a").apply { writeText("audio1") }
        val file2 = File(audioDir, "note_20.pcm").apply { writeText("audio2") }
        val orphanFile = File(audioDir, "orphan.wav").apply { writeText("orphan") }
        val unrelatedFile = File(audioDir, "keep_me.txt").apply { writeText("text document") }

        val id1 = dao.insert(
            Note(
                id = 10L,
                createdAtEpochMs = 1000L,
                audioPath = file1.absolutePath,
                title = "Shopping",
                transcript = "Milk and eggs"
            )
        )
        val id2 = dao.insert(
            Note(
                id = 20L,
                createdAtEpochMs = 2000L,
                audioPath = file2.absolutePath,
                title = "Work task",
                transcript = "File taxes"
            )
        )

        dao.insertActions(
            listOf(
                ActionItem(noteId = id1, text = "Buy milk"),
                ActionItem(noteId = id2, text = "Pay taxes")
            )
        )
        dao.insertTags(listOf(Tag("groceries"), Tag("finance")))
        dao.insertNoteTags(
            listOf(
                NoteTag(noteId = id1, tagName = "groceries"),
                NoteTag(noteId = id2, tagName = "finance")
            )
        )

        val coordinator = NoteDeletionCoordinator(dao, audioDir)
        coordinator.deleteAllNotes(deleteTags = true)

        // Verify everything is deleted
        assertTrue("All notes should be deleted", dao.observeAll().first().isEmpty())
        assertTrue("Actions for note 1 should be gone", dao.actionsFor(id1).isEmpty())
        assertTrue("Actions for note 2 should be gone", dao.actionsFor(id2).isEmpty())
        assertTrue("Tags for note 1 should be gone", dao.tagsFor(id1).isEmpty())
        assertTrue("Tags for note 2 should be gone", dao.tagsFor(id2).isEmpty())
        assertTrue("All tags should be cleared", dao.allTags().isEmpty())
        assertTrue("Search should return 0 results", dao.search("milk").first().isEmpty())

        // Physical audio files should be removed
        assertFalse("file1 should be deleted", file1.exists())
        assertFalse("file2 should be deleted", file2.exists())
        assertFalse("orphan audio should be deleted", orphanFile.exists())
        assertTrue("non-audio file should be untouched", unrelatedFile.exists())
    }
}
