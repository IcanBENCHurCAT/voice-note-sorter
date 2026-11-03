// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import android.content.Context
import java.io.File

/**
 * Coordinates atomic database cascades with file-system audio cleanup.
 *
 * Enforces Constitution Article IV.4:
 * "Export and delete MUST be complete: deleting a note deletes its
 * audio, transcript, tags, actions, search index rows, and embeddings."
 */
class NoteDeletionCoordinator(
    private val dao: NoteDao,
    private val audioDir: File? = null
) {

    /**
     * Deletes a single note by ID:
     * 1. Retrieves the note to identify its associated audio file path.
     * 2. Deletes DB rows atomically (actions, note_tags, note, and synced FTS rows).
     * 3. Deletes the physical audio file from disk if present.
     */
    suspend fun deleteNote(noteId: Long): Boolean {
        val note = dao.byId(noteId) ?: return false
        dao.deleteNoteCascade(noteId)
        deleteAudioFile(note.audioPath)
        return true
    }

    /**
     * Deletes all notes:
     * 1. Collects all known audio file paths from the notes table.
     * 2. Deletes all DB rows atomically (actions, note_tags, optional tags, notes, and FTS).
     * 3. Deletes all tracked audio files from disk.
     * 4. Deletes any remaining orphaned audio files (.m4a, .pcm, .wav) in the private audio directory.
     */
    suspend fun deleteAllNotes(deleteTags: Boolean = true) {
        val paths = dao.allAudioPaths()
        dao.deleteAllNotesCascade(deleteTags = deleteTags)

        for (path in paths) {
            deleteAudioFile(path)
        }

        // Clean up any remaining audio files in private directory if specified
        audioDir?.let { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    val ext = file.extension.lowercase()
                    if (ext == "m4a" || ext == "pcm" || ext == "wav") {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun deleteAudioFile(path: String) {
        if (path.isBlank()) return
        try {
            val file = File(path)
            if (file.exists() && file.isFile) {
                file.delete()
            }
        } catch (_: SecurityException) {
            // Ignore security exception if file cannot be accessed
        }
    }

    companion object {
        fun fromContext(context: Context): NoteDeletionCoordinator {
            val db = AppDatabase.get(context)
            val audioDir = context.filesDir
            return NoteDeletionCoordinator(db.noteDao(), audioDir)
        }
    }
}
