// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.icanbenchurcat.voicenotes.ai.ActionExtractor
import com.icanbenchurcat.voicenotes.ai.Bucketer
import com.icanbenchurcat.voicenotes.ai.CapabilityBusyException
import com.icanbenchurcat.voicenotes.ai.DefaultBucketProfileProvider
import com.icanbenchurcat.voicenotes.ai.ExtractiveSummarizer
import com.icanbenchurcat.voicenotes.ai.HeuristicActionExtractor
import com.icanbenchurcat.voicenotes.ai.HeuristicBucketer
import com.icanbenchurcat.voicenotes.ai.NanoTranscriber
import com.icanbenchurcat.voicenotes.ai.Summarizer
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.AppDatabase

/**
 * The sorting pipeline as a deferred WorkManager job: five stages, each
 * independently failable, each recording its outcome on the note. A stage
 * that hits a busy/quota capability marks the note for the upgrade retry
 * pass instead of failing the note. Organisation is automatic; the user
 * only ever corrects it.
 */
class SortingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val transcriber: Transcriber = NanoTranscriber(context)
    private val summarizer: Summarizer = ExtractiveSummarizer()
    private val extractor: ActionExtractor = HeuristicActionExtractor()
    private val bucketer: Bucketer = HeuristicBucketer()

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1)
        if (noteId < 0) return Result.failure()
        val dao = AppDatabase.get(applicationContext).noteDao()
        val note = dao.byId(noteId) ?: return Result.failure()
        val stages = mutableListOf<String>()

        // Stage 1: transcribe
        val transcript = try {
            val t = transcriber.transcribe(note.audioPath)
            stages += "transcribe:OK"
            t
        } catch (e: CapabilityBusyException) {
            stages += "transcribe:BUSY"
            return Result.retry()
        } catch (e: Exception) {
            stages += "transcribe:FAILED"
            null
        }

        if (transcript == null || transcript.text.isBlank()) {
            dao.update(note.copy(stageStatus = stages.joinToString(",")))
            return Result.success()
        }

        // Stage 2: title + summary
        val ts = summarizer.summarize(transcript.text)
        stages += "summarize:OK"

        // Stage 3: actions
        val actions = extractor.extract(transcript.text)
        if (actions.isNotEmpty()) {
            dao.insertActions(actions.map { ActionItem(noteId = noteId, text = it.text, dueAtEpochMs = it.dueAtEpochMs) })
        }
        stages += "actions:OK"

        // Stage 4: bucket + tags (profile-driven; see BucketProfileProvider)
        val assignment = bucketer.assign(transcript.text, DefaultBucketProfileProvider.profile())
        stages += "bucket:OK"

        // Stage 5 (embed) is Tier 3 / v1.1; FTS is maintained by Room.

        dao.update(
            note.copy(
                transcript = transcript.text,
                title = ts.title,
                summary = ts.summary,
                bucketKind = assignment.bucketKind,
                tierUsed = transcript.tier.name,
                stageStatus = stages.joinToString(",")
            )
        )
        return Result.success()
    }

    companion object {
        const val KEY_NOTE_ID = "noteId"
    }
}
