// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageRetentionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dao: InMemoryFakeNoteDao
    private lateinit var audioDir: File
    private lateinit var dbFile: File
    private lateinit var dataStoreFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var manager: StorageRetentionManager

    @Before
    fun setUp() {
        dao = InMemoryFakeNoteDao()
        audioDir = tempFolder.newFolder("audio_files")
        dbFile = tempFolder.newFile("test_voice_notes.db")
        dbFile.writeBytes(ByteArray(1024)) // 1 KB simulated database

        dataStoreFile = tempFolder.newFile("test_prefs.preferences_pb")
        dataStoreFile.delete()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dataStoreFile }
        )

        manager = StorageRetentionManager(
            dao = dao,
            audioDir = audioDir,
            dbFile = dbFile,
            dataStore = dataStore
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun defaultAutoDeleteIsDisabled() = runBlocking {
        assertFalse(manager.isAutoDeleteEnabled())
        assertFalse(manager.isAutoDeleteOldAudioEnabled.first())
    }

    @Test
    fun toggleAutoDeletePersists() = runBlocking {
        manager.setAutoDeleteOldAudioEnabled(true)
        assertTrue(manager.isAutoDeleteEnabled())
        assertTrue(manager.isAutoDeleteOldAudioEnabled.first())

        manager.setAutoDeleteOldAudioEnabled(false)
        assertFalse(manager.isAutoDeleteEnabled())
    }

    @Test
    fun storageStatsReportsAccurateByteSizesAndNoteCounts() = runBlocking {
        // Create 2 audio files
        val file1 = File(audioDir, "audio_1.m4a").apply { writeBytes(ByteArray(2048)) }
        val file2 = File(audioDir, "audio_2.m4a").apply { writeBytes(ByteArray(4096)) }

        dao.insert(
            Note(
                createdAtEpochMs = 1000L,
                audioPath = file1.absolutePath,
                title = "Note 1",
                transcript = "Transcript 1"
            )
        )
        dao.insert(
            Note(
                createdAtEpochMs = 2000L,
                audioPath = file2.absolutePath,
                title = "Note 2",
                transcript = "Transcript 2"
            )
        )

        val stats = manager.getStorageStats()
        assertEquals(2, stats.noteCount)
        assertEquals(6144L, stats.audioSizeBytes) // 2048 + 4096
        assertEquals(1024L, stats.databaseSizeBytes)
    }

    @Test
    fun retentionPurgeDeletesOldAudioFilesWhilePreservingTranscriptsAndMetadata() = runBlocking {
        val now = 100_000_000_000L
        val olderThan90Days = now - (91L * 24 * 60 * 60 * 1000L)
        val newerThan90Days = now - (10L * 24 * 60 * 60 * 1000L)

        val oldAudioFile = File(audioDir, "old_recording.m4a").apply { writeBytes(ByteArray(5000)) }
        val newAudioFile = File(audioDir, "new_recording.m4a").apply { writeBytes(ByteArray(3000)) }

        val oldNoteId = dao.insert(
            Note(
                createdAtEpochMs = olderThan90Days,
                audioPath = oldAudioFile.absolutePath,
                title = "Old Note",
                transcript = "Vital old transcript that must be preserved",
                summary = "Old summary",
                bucketKind = "IDEA",
                status = NoteStatus.SORTED.name
            )
        )
        dao.insertActions(
            listOf(
                ActionItem(noteId = oldNoteId, text = "Action on old note", done = false)
            )
        )

        val newNoteId = dao.insert(
            Note(
                createdAtEpochMs = newerThan90Days,
                audioPath = newAudioFile.absolutePath,
                title = "New Note",
                transcript = "Fresh new transcript",
                bucketKind = "TASK",
                status = NoteStatus.SORTED.name
            )
        )

        // Verify files exist prior to purge
        assertTrue(oldAudioFile.exists())
        assertTrue(newAudioFile.exists())

        // Execute purge for files older than 90 days
        val deletedCount = manager.purgeAudioOlderThan(
            retentionThresholdMs = StorageRetentionManager.NINETY_DAYS_MS,
            currentTimeMs = now
        )

        assertEquals(1, deletedCount)

        // Old audio file must be deleted from disk
        assertFalse("Old audio file should be removed from disk", oldAudioFile.exists())
        // New audio file must still exist on disk
        assertTrue("New audio file should still exist on disk", newAudioFile.exists())

        // Verify Old Note is strictly preserved in database
        val oldNoteAfter = dao.byId(oldNoteId)
        assertNotNull(oldNoteAfter)
        assertEquals("Vital old transcript that must be preserved", oldNoteAfter?.transcript)
        assertEquals("Old Note", oldNoteAfter?.title)
        assertEquals("Old summary", oldNoteAfter?.summary)
        assertEquals("IDEA", oldNoteAfter?.bucketKind)
        assertEquals("", oldNoteAfter?.audioPath) // audioPath cleared

        // Verify associated actions are preserved
        val actions = dao.actionsFor(oldNoteId)
        assertEquals(1, actions.size)
        assertEquals("Action on old note", actions[0].text)

        // Verify New Note is untouched
        val newNoteAfter = dao.byId(newNoteId)
        assertNotNull(newNoteAfter)
        assertEquals(newAudioFile.absolutePath, newNoteAfter?.audioPath)
    }

    @Test
    fun runRetentionPolicyRespectsToggle() = runBlocking {
        val now = 100_000_000_000L
        val olderThan90Days = now - (100L * 24 * 60 * 60 * 1000L)
        val oldAudioFile = File(audioDir, "old.m4a").apply { writeBytes(ByteArray(1000)) }

        dao.insert(
            Note(
                createdAtEpochMs = olderThan90Days,
                audioPath = oldAudioFile.absolutePath,
                title = "Old Note",
                transcript = "Keep this"
            )
        )

        // When disabled (default)
        val countWhenDisabled = manager.runRetentionPolicyIfEnabled(currentTimeMs = now)
        assertEquals(0, countWhenDisabled)
        assertTrue("Audio file must remain when auto-delete is disabled", oldAudioFile.exists())

        // When enabled
        manager.setAutoDeleteOldAudioEnabled(true)
        val countWhenEnabled = manager.runRetentionPolicyIfEnabled(currentTimeMs = now)
        assertEquals(1, countWhenEnabled)
        assertFalse("Audio file should be purged when auto-delete is enabled", oldAudioFile.exists())
    }
}
