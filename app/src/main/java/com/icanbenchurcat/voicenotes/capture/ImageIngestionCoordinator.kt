// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import android.content.Context
import android.net.Uri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteDao
import com.icanbenchurcat.voicenotes.data.NoteSourceType
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.service.SortingWorker
import java.io.File

/**
 * Coordinates zero-permission image ingestion into Room and WorkManager.
 * Persists a [Note] with `sourceType = IMAGE` pointing at `filesDir/images/`
 * and enqueues [SortingWorker] to transcribe, summarize, extract actions, and file it.
 */
object ImageIngestionCoordinator {

    suspend fun ingestImageFile(
        dao: NoteDao,
        imageFile: File,
        createdAtEpochMs: Long = System.currentTimeMillis(),
        enqueueWorker: (Long) -> Unit
    ): Long? {
        if (!imageFile.exists() || !imageFile.isFile || imageFile.length() <= 0L) {
            return null
        }
        val note = Note(
            createdAtEpochMs = createdAtEpochMs,
            audioPath = "",
            sourceType = NoteSourceType.IMAGE.name,
            imagePath = imageFile.absolutePath,
            bucketKind = "INBOX",
            status = NoteStatus.RECORDED.name,
            tierUsed = "CORE",
            stageStatus = ""
        )
        val noteId = dao.insert(note)
        enqueueWorker(noteId)
        return noteId
    }

    suspend fun ingestFromCameraFile(
        context: Context,
        imageFile: File,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long? {
        val appContext = context.applicationContext
        val dao = AppDatabase.get(appContext).noteDao()
        return ingestImageFile(dao, imageFile, createdAtEpochMs) { noteId ->
            enqueueSortingWorker(appContext, noteId)
        }
    }

    suspend fun ingestFromPickedUri(
        context: Context,
        sourceUri: Uri,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long? {
        val appContext = context.applicationContext
        val copied = ImageFileManager.copyUriToAppPrivateImage(appContext, sourceUri, createdAtEpochMs)
            ?: return null
        val dao = AppDatabase.get(appContext).noteDao()
        return ingestImageFile(dao, copied, createdAtEpochMs) { noteId ->
            enqueueSortingWorker(appContext, noteId)
        }
    }

    private fun enqueueSortingWorker(context: Context, noteId: Long) {
        val workRequest = OneTimeWorkRequestBuilder<SortingWorker>()
            .setInputData(workDataOf(SortingWorker.KEY_NOTE_ID to noteId))
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}
