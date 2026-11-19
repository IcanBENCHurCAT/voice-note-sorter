// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import android.content.Context
import com.icanbenchurcat.voicenotes.capture.ImageFileManager
import java.io.File

/**
 * Coordinates atomic database cascades with file-system audio and image cleanup.
 *
 * Enforces Constitution Article IV.4:
 * "Export and delete MUST be complete: deleting a note deletes its
 * audio, image, transcript, tags, actions, search index rows, and embeddings."
 */
class NoteDeletionCoordinator(
    private val dao: NoteDao,
    private val audioDir: File? = null,
    private val imageDir: File? = null
) {

    /**
     * Deletes a single note by ID:
     * 1. Retrieves the note to identify its associated audio and image file paths.
     * 2. Deletes DB rows atomically (actions, note_tags, note_embeddings, note, and synced FTS rows).
     * 3. Deletes the physical audio and image files from disk if present.
     */
    suspend fun deleteNote(noteId: Long): Boolean {
        val note = dao.byId(noteId) ?: return false
        dao.deleteNoteCascade(noteId)
        deleteMediaFile(note.audioPath)
        deleteMediaFile(note.imagePath)
        return true
    }

    /**
     * Deletes all notes:
     * 1. Collects all known audio and image file paths from the notes table.
     * 2. Deletes all DB rows atomically (actions, note_tags, optional tags, note_embeddings, notes, and FTS).
     * 3. Deletes all tracked audio and image files from disk.
     * 4. Deletes any remaining orphaned audio (.m4a, .pcm, .wav) and image files in private storage.
     */
    suspend fun deleteAllNotes(deleteTags: Boolean = true) {
        val audioPaths = dao.allAudioPaths()
        val imagePaths = dao.allImagePaths()
        dao.deleteAllNotesCascade(deleteTags = deleteTags)

        for (path in audioPaths) {
            deleteMediaFile(path)
        }
        for (path in imagePaths) {
            deleteMediaFile(path)
        }

        // Clean up any remaining orphaned audio files in private storage
        deleteOrphanFiles(audioDir, AUDIO_EXTENSIONS)
        audioDir?.let { dir ->
            deleteOrphanFiles(File(dir, RECORDINGS_SUBDIR), AUDIO_EXTENSIONS)
        }

        // Clean up any remaining orphaned image files in private images directory
        val resolvedImageDir = imageDir ?: audioDir?.let { File(it, ImageFileManager.IMAGES_DIR_NAME) }
        deleteOrphanFiles(resolvedImageDir, ImageFileManager.SUPPORTED_IMAGE_EXTENSIONS)
    }

    private fun deleteOrphanFiles(dir: File?, extensions: Set<String>) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.lowercase() in extensions) {
                file.delete()
            }
        }
    }

    private fun deleteMediaFile(path: String) {
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
        private const val RECORDINGS_SUBDIR = "recordings"
        private val AUDIO_EXTENSIONS = setOf("m4a", "pcm", "wav")

        fun fromContext(context: Context): NoteDeletionCoordinator {
            val db = AppDatabase.get(context)
            val filesDir = context.filesDir
            val imagesDir = File(filesDir, ImageFileManager.IMAGES_DIR_NAME)
            return NoteDeletionCoordinator(db.noteDao(), filesDir, imagesDir)
        }
    }
}
