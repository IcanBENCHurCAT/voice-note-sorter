// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.icanbenchurcat.voicenotes.ai.ActionExtractor
import com.icanbenchurcat.voicenotes.ai.Bucketer
import com.icanbenchurcat.voicenotes.ai.CapabilityBusyException
import com.icanbenchurcat.voicenotes.ai.CapabilityProbe
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.DefaultBucketProfileProvider
import com.icanbenchurcat.voicenotes.ai.ExtractiveSummarizer
import com.icanbenchurcat.voicenotes.ai.HeuristicActionExtractor
import com.icanbenchurcat.voicenotes.ai.HeuristicBucketer
import com.icanbenchurcat.voicenotes.ai.NanoTranscriber
import com.icanbenchurcat.voicenotes.ai.NullTranscriber
import com.icanbenchurcat.voicenotes.ai.PlatformTranscriber
import com.icanbenchurcat.voicenotes.ai.Summarizer
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteDao
import com.icanbenchurcat.voicenotes.data.NoteTag
import com.icanbenchurcat.voicenotes.data.Tag
import kotlinx.coroutines.CancellationException

/**
 * Outcome of running the sorting pipeline for a single [Note].
 */
data class PipelineOutcome(
    val updatedNote: Note,
    val extractedActions: List<ActionItem>,
    val tags: List<String>
)

/**
 * The sorting pipeline as a deferred WorkManager job: five stages, each
 * independently failable and independently persisted, each recording its
 * outcome on the note. A stage that hits a busy/quota capability demotes
 * to the next rung of the capability ladder instead of failing the note.
 * Organisation is automatic; the user only ever corrects it.
 */
class SortingWorker(
    context: Context,
    params: WorkerParameters,
    private val capabilityProbe: CapabilityProbe,
    private val transcriber: Transcriber,
    private val platformTranscriber: Transcriber,
    private val nullTranscriber: Transcriber,
    private val summarizer: Summarizer,
    private val extractor: ActionExtractor,
    private val bucketer: Bucketer,
    private val fallbackSummarizer: Summarizer = ExtractiveSummarizer(),
    private val fallbackExtractor: ActionExtractor = HeuristicActionExtractor()
) : CoroutineWorker(context, params) {

    // Primary constructor for WorkManager
    constructor(context: Context, params: WorkerParameters) : this(
        context = context,
        params = params,
        capabilityProbe = CapabilityProbe.get(context),
        transcriber = NanoTranscriber(context),
        platformTranscriber = PlatformTranscriber(context),
        nullTranscriber = NullTranscriber,
        summarizer = ExtractiveSummarizer(),
        extractor = HeuristicActionExtractor(),
        bucketer = HeuristicBucketer()
    )

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1)
        if (noteId < 0) return Result.failure()
        val dao = AppDatabase.get(applicationContext).noteDao()
        val note = dao.byId(noteId) ?: return Result.failure()

        executePipeline(
            note = note,
            capabilityProbe = capabilityProbe,
            transcriber = transcriber,
            platformTranscriber = platformTranscriber,
            nullTranscriber = nullTranscriber,
            summarizer = summarizer,
            extractor = extractor,
            bucketer = bucketer,
            fallbackSummarizer = fallbackSummarizer,
            fallbackExtractor = fallbackExtractor,
            dao = dao
        )
        return Result.success()
    }

    companion object {
        const val KEY_NOTE_ID = "noteId"

        suspend fun executePipeline(
            note: Note,
            capabilityProbe: CapabilityProbe,
            transcriber: Transcriber,
            platformTranscriber: Transcriber,
            nullTranscriber: Transcriber,
            summarizer: Summarizer,
            extractor: ActionExtractor,
            bucketer: Bucketer,
            fallbackSummarizer: Summarizer = ExtractiveSummarizer(),
            fallbackExtractor: ActionExtractor = HeuristicActionExtractor(),
            dao: NoteDao? = null
        ): PipelineOutcome {
            val stages = mutableListOf<String>()
            var currentNote = note
            val profile = DefaultBucketProfileProvider.profile()

            // Ensure default profile Bucket rows are seeded before sorting
            dao?.insertBuckets(
                profile.buckets.map { Bucket(kind = it.kind, name = it.name) }
            )

            // Stage 1: transcribe — consult CapabilityProbe and persist immediately
            val probedTranscriberStatus = capabilityProbe.transcriberStatus()
            val transcript = when (probedTranscriberStatus) {
                CapabilityStatus.UNAVAILABLE -> {
                    CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.MINIMAL)
                    val t = nullTranscriber.transcribe(currentNote.audioPath)
                    stages += "transcribe_null:OK"
                    t
                }
                CapabilityStatus.DOWNLOADABLE, CapabilityStatus.BUSY -> {
                    CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.CORE)
                    transcribeWithPlatformOrNull(
                        audioPath = currentNote.audioPath,
                        platformTranscriber = platformTranscriber,
                        nullTranscriber = nullTranscriber,
                        stages = stages
                    )
                }
                CapabilityStatus.AVAILABLE -> {
                    if (transcriber.status() == CapabilityStatus.AVAILABLE) {
                        try {
                            val t = transcriber.transcribe(currentNote.audioPath)
                            stages += "transcribe:OK"
                            t
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: CapabilityBusyException) {
                            stages += "transcribe:BUSY"
                            CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.CORE)
                            transcribeWithPlatformOrNull(
                                audioPath = currentNote.audioPath,
                                platformTranscriber = platformTranscriber,
                                nullTranscriber = nullTranscriber,
                                stages = stages
                            )
                        } catch (e: Exception) {
                            stages += "transcribe:FAILED"
                            null
                        }
                    } else {
                        CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.CORE)
                        transcribeWithPlatformOrNull(
                            audioPath = currentNote.audioPath,
                            platformTranscriber = platformTranscriber,
                            nullTranscriber = nullTranscriber,
                            stages = stages
                        )
                    }
                }
            }

            if (transcript == null || transcript.text.isBlank()) {
                val tierToSave = transcript?.tier?.name ?: Tier.MINIMAL.name
                currentNote = currentNote.copy(
                    stageStatus = stages.joinToString(","),
                    transcript = transcript?.text ?: "",
                    tierUsed = tierToSave
                )
                dao?.update(currentNote)
                return PipelineOutcome(
                    updatedNote = currentNote,
                    extractedActions = emptyList(),
                    tags = emptyList()
                )
            }

            // Persist Stage 1 output immediately so an interruption in Stages 2-4 preserves the transcript
            currentNote = currentNote.copy(
                transcript = transcript.text,
                tierUsed = transcript.tier.name,
                stageStatus = stages.joinToString(",")
            )
            dao?.update(currentNote)

            // Stage 2: title + summary — independently caught and persisted
            val probedSummarizerStatus = capabilityProbe.summarizerStatus()
            val activeSummarizer = if (
                probedSummarizerStatus == CapabilityStatus.AVAILABLE &&
                summarizer.status() == CapabilityStatus.AVAILABLE
            ) {
                summarizer
            } else {
                fallbackSummarizer
            }
            try {
                val ts = try {
                    val result = activeSummarizer.summarize(currentNote.transcript)
                    stages += "summarize:OK"
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CapabilityBusyException) {
                    stages += "summarize:BUSY"
                    CapabilityProbe.logDemotion("summarizer", Tier.FULL, Tier.CORE)
                    if (activeSummarizer !== fallbackSummarizer) {
                        val fallbackResult = fallbackSummarizer.summarize(currentNote.transcript)
                        stages += "summarize_fallback:OK"
                        fallbackResult
                    } else {
                        null
                    }
                }
                currentNote = if (ts != null) {
                    currentNote.copy(
                        title = ts.title,
                        summary = ts.summary,
                        stageStatus = stages.joinToString(",")
                    )
                } else {
                    currentNote.copy(stageStatus = stages.joinToString(","))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stages += "summarize:FAILED"
                currentNote = currentNote.copy(stageStatus = stages.joinToString(","))
            }
            dao?.update(currentNote)

            // Stage 3: actions — independently caught, idempotently replaced, and persisted
            val probedActionsStatus = capabilityProbe.actionExtractorStatus()
            val activeExtractor = if (
                probedActionsStatus == CapabilityStatus.AVAILABLE &&
                extractor.status() == CapabilityStatus.AVAILABLE
            ) {
                extractor
            } else {
                fallbackExtractor
            }
            var persistedActions: List<ActionItem> = emptyList()
            try {
                val extracted = try {
                    val result = activeExtractor.extract(currentNote.transcript)
                    stages += "actions:OK"
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CapabilityBusyException) {
                    stages += "actions:BUSY"
                    CapabilityProbe.logDemotion("actions", Tier.FULL, Tier.CORE)
                    if (activeExtractor !== fallbackExtractor) {
                        val fallbackResult = fallbackExtractor.extract(currentNote.transcript)
                        stages += "actions_fallback:OK"
                        fallbackResult
                    } else {
                        null
                    }
                }
                if (extracted != null) {
                    val existingByText = dao?.actionsFor(currentNote.id)
                        ?.associateBy { it.text.trim() }
                        .orEmpty()
                    val deduplicated = extracted
                        .map { it.copy(text = it.text.trim()) }
                        .filter { it.text.isNotEmpty() }
                        .distinctBy { it.text }
                        .map { item ->
                            val existing = existingByText[item.text]
                            ActionItem(
                                noteId = currentNote.id,
                                text = item.text,
                                dueAtEpochMs = item.dueAtEpochMs,
                                done = existing?.done ?: false
                            )
                        }
                    dao?.deleteActionsForNote(currentNote.id)
                    if (deduplicated.isNotEmpty()) {
                        dao?.insertActions(deduplicated)
                    }
                    persistedActions = dao?.actionsFor(currentNote.id) ?: deduplicated
                }
                currentNote = currentNote.copy(stageStatus = stages.joinToString(","))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stages += "actions:FAILED"
                currentNote = currentNote.copy(stageStatus = stages.joinToString(","))
            }
            dao?.update(currentNote)

            // Stage 4: bucket + tags — independently caught, idempotently replaced, and persisted
            var persistedTags: List<String> = emptyList()
            try {
                val assignment = bucketer.assign(currentNote.transcript, profile)
                stages += "bucket:OK"

                // Ensure the assigned bucket exists as a Bucket row in the database
                if (dao != null && dao.bucketByKind(assignment.bucketKind) == null) {
                    val def = profile.buckets.firstOrNull { it.kind == assignment.bucketKind }
                    dao.insertBuckets(
                        listOf(
                            Bucket(
                                kind = assignment.bucketKind,
                                name = def?.name ?: assignment.bucketKind
                            )
                        )
                    )
                }

                val distinctTags = assignment.tags
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()
                dao?.deleteNoteTagsForNote(currentNote.id)
                if (distinctTags.isNotEmpty()) {
                    dao?.insertTags(distinctTags.map { Tag(it) })
                    dao?.insertNoteTags(distinctTags.map { NoteTag(noteId = currentNote.id, tagName = it) })
                }
                persistedTags = dao?.tagsFor(currentNote.id) ?: distinctTags
                currentNote = currentNote.copy(
                    bucketKind = assignment.bucketKind,
                    stageStatus = stages.joinToString(",")
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: CapabilityBusyException) {
                stages += "bucket:BUSY"
                currentNote = currentNote.copy(stageStatus = stages.joinToString(","))
            } catch (e: Exception) {
                stages += "bucket:FAILED"
                currentNote = currentNote.copy(stageStatus = stages.joinToString(","))
            }
            dao?.update(currentNote)

            // Stage 5 (embed) is Tier 3 / v1.1; FTS is maintained by Room.

            return PipelineOutcome(
                updatedNote = currentNote,
                extractedActions = persistedActions,
                tags = persistedTags
            )
        }

        private suspend fun transcribeWithPlatformOrNull(
            audioPath: String,
            platformTranscriber: Transcriber,
            nullTranscriber: Transcriber,
            stages: MutableList<String>
        ) = if (platformTranscriber.status() == CapabilityStatus.AVAILABLE) {
            try {
                val t = platformTranscriber.transcribe(audioPath)
                stages += "transcribe_fallback:OK"
                t
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: CapabilityBusyException) {
                stages += "transcribe_fallback:BUSY"
                CapabilityProbe.logDemotion("transcriber", Tier.CORE, Tier.MINIMAL)
                val t = nullTranscriber.transcribe(audioPath)
                stages += "transcribe_null:OK"
                t
            } catch (e2: Exception) {
                stages += "transcribe_fallback:FAILED"
                null
            }
        } else {
            CapabilityProbe.logDemotion("transcriber", Tier.CORE, Tier.MINIMAL)
            val t = nullTranscriber.transcribe(audioPath)
            stages += "transcribe_null:OK"
            t
        }
    }
}
