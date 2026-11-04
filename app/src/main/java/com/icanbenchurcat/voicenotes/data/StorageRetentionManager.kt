// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Manages storage metrics and audio retention policy settings.
 *
 * Enforces Spec Decision 4:
 * Keep audio until the user deletes it, show storage in Settings,
 * and offer optional automatic deletion of audio files (retaining transcripts)
 * older than 90 days, off by default.
 */
class StorageRetentionManager(
    private val dao: NoteDao,
    private val audioDir: File,
    private val dbFile: File? = null,
    private val dataStore: DataStore<Preferences>
) {

    val isAutoDeleteOldAudioEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PREF_AUTO_DELETE_OLD_AUDIO] ?: false
    }

    suspend fun setAutoDeleteOldAudioEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PREF_AUTO_DELETE_OLD_AUDIO] = enabled
        }
    }

    suspend fun isAutoDeleteEnabled(): Boolean =
        dataStore.data.first()[PREF_AUTO_DELETE_OLD_AUDIO] ?: false

    /**
     * Calculates storage stats:
     * - [audioSizeBytes]: Total bytes used by audio files tracked or stored in [audioDir].
     * - [databaseSizeBytes]: File size of the SQLite database and its WAL/journal files.
     * - [noteCount]: Total count of notes in the database.
     */
    suspend fun getStorageStats(): StorageStats {
        val notes = dao.allAudioPaths()
        var audioBytes = 0L

        // Track seen files to avoid double-counting if paths overlap with directory list
        val seenCanonicalPaths = mutableSetOf<String>()

        for (path in notes) {
            if (path.isNotBlank()) {
                val f = File(path)
                if (f.exists() && f.isFile) {
                    seenCanonicalPaths.add(f.canonicalPath)
                    audioBytes += f.length()
                }
            }
        }

        if (audioDir.exists() && audioDir.isDirectory) {
            audioDir.listFiles()?.forEach { file ->
                val ext = file.extension.lowercase()
                if (ext == "m4a" || ext == "pcm" || ext == "wav") {
                    if (seenCanonicalPaths.add(file.canonicalPath)) {
                        audioBytes += file.length()
                    }
                }
            }
        }

        var dbBytes = 0L
        dbFile?.let { db ->
            if (db.exists() && db.isFile) {
                dbBytes += db.length()
            }
            // Include SQLite WAL and SHM if present
            val wal = File("${db.path}-wal")
            if (wal.exists() && wal.isFile) dbBytes += wal.length()
            val shm = File("${db.path}-shm")
            if (shm.exists() && shm.isFile) dbBytes += shm.length()
        }

        return StorageStats(
            audioSizeBytes = audioBytes,
            databaseSizeBytes = dbBytes,
            noteCount = notes.size
        )
    }

    /**
     * Executes retention purge:
     * Deletes audio files for notes whose [createdAtEpochMs] is older than [retentionThresholdMs]
     * (default: 90 days), while strictly preserving notes, transcripts, actions, tags, and FTS rows.
     * Updates note audioPath to blank once the file is safely deleted.
     *
     * @return Number of audio files deleted.
     */
    suspend fun purgeAudioOlderThan(
        retentionThresholdMs: Long,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Int {
        val cutoffTime = currentTimeMs - retentionThresholdMs
        val oldNotesWithAudio = dao.findNotesWithAudioOlderThan(cutoffTime)
        var deletedCount = 0

        for (note in oldNotesWithAudio) {
            val file = File(note.audioPath)
            if (file.exists() && file.isFile) {
                try {
                    if (file.delete()) {
                        deletedCount++
                    }
                } catch (_: SecurityException) {
                }
            }
            // Clear audioPath on the note to maintain referential honesty
            dao.clearAudioPath(note.id)
        }

        return deletedCount
    }

    suspend fun runRetentionPolicyIfEnabled(
        currentTimeMs: Long = System.currentTimeMillis()
    ): Int {
        if (!isAutoDeleteEnabled()) {
            return 0
        }
        return purgeAudioOlderThan(
            retentionThresholdMs = NINETY_DAYS_MS,
            currentTimeMs = currentTimeMs
        )
    }

    companion object {
        val PREF_AUTO_DELETE_OLD_AUDIO = booleanPreferencesKey("auto_delete_old_audio_90_days")
        const val NINETY_DAYS_MS = 90L * 24 * 60 * 60 * 1000L // 7,776,000,000 ms

        @Volatile
        private var defaultDataStore: DataStore<Preferences>? = null

        private fun getOrCreateDataStore(context: Context): DataStore<Preferences> {
            return defaultDataStore ?: synchronized(this) {
                defaultDataStore ?: PreferenceDataStoreFactory.create(
                    produceFile = { context.applicationContext.preferencesDataStoreFile("storage_retention_prefs") }
                ).also { defaultDataStore = it }
            }
        }

        fun fromContext(
            context: Context,
            dataStore: DataStore<Preferences>? = null
        ): StorageRetentionManager {
            val appContext = context.applicationContext
            val db = AppDatabase.get(appContext)
            val audioDir = appContext.filesDir
            val dbFile = appContext.getDatabasePath("voice-notes.db")
            val actualDataStore = dataStore ?: getOrCreateDataStore(appContext)
            return StorageRetentionManager(
                dao = db.noteDao(),
                audioDir = audioDir,
                dbFile = dbFile,
                dataStore = actualDataStore
            )
        }
    }
}

data class StorageStats(
    val audioSizeBytes: Long,
    val databaseSizeBytes: Long,
    val noteCount: Int
)
