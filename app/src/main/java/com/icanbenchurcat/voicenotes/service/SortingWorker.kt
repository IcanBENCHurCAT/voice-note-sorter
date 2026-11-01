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
import com.icanbenchurcat.voicenotes.data.Note

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
 * independently failable, each recording its outcome on the note. A stage
 * that hits a busy/quota capability demotes to the next rung of the
 * capability ladder instead of failing the note. Organisation is automatic;
 * the user only ever corrects it.
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

        return try {
            val outcome = executePipeline(
                note = note,
                capabilityProbe = capabilityProbe,
                transcriber = transcriber,
                platformTranscriber = platformTranscriber,
                nullTranscriber = nullTranscriber,
                summarizer = summarizer,
                extractor = extractor,
                bucketer = bucketer,
                fallbackSummarizer = fallbackSummarizer,
                fallbackExtractor = fallbackExtractor
            )
            if (outcome.extractedActions.isNotEmpty()) {
                dao.insertActions(outcome.extractedActions)
            }
            dao.update(outcome.updatedNote)
            Result.success()
        } catch (e: CapabilityBusyException) {
            Result.retry()
        }
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
            fallbackExtractor: ActionExtractor = HeuristicActionExtractor()
        ): PipelineOutcome {
            val stages = mutableListOf<String>()

            // Stage 1: transcribe — consult CapabilityProbe before selecting implementation
            val probedTranscriberStatus = capabilityProbe.transcriberStatus()
            val transcript = when (probedTranscriberStatus) {
                CapabilityStatus.UNAVAILABLE -> {
                    CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.MINIMAL)
                    val t = nullTranscriber.transcribe(note.audioPath)
                    stages += "transcribe_null:OK"
                    t
                }
                CapabilityStatus.DOWNLOADABLE, CapabilityStatus.BUSY -> {
                    CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.CORE)
                    transcribeWithPlatformOrNull(
                        audioPath = note.audioPath,
                        platformTranscriber = platformTranscriber,
                        nullTranscriber = nullTranscriber,
                        stages = stages
                    )
                }
                CapabilityStatus.AVAILABLE -> {
                    if (transcriber.status() == CapabilityStatus.AVAILABLE) {
                        try {
                            val t = transcriber.transcribe(note.audioPath)
                            stages += "transcribe:OK"
                            t
                        } catch (e: CapabilityBusyException) {
                            stages += "transcribe:BUSY"
                            CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.CORE)
                            transcribeWithPlatformOrNull(
                                audioPath = note.audioPath,
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
                            audioPath = note.audioPath,
                            platformTranscriber = platformTranscriber,
                            nullTranscriber = nullTranscriber,
                            stages = stages
                        )
                    }
                }
            }

            if (transcript == null || transcript.text.isBlank()) {
                val tierToSave = transcript?.tier?.name ?: Tier.MINIMAL.name
                return PipelineOutcome(
                    updatedNote = note.copy(
                        stageStatus = stages.joinToString(","),
                        transcript = transcript?.text ?: "",
                        tierUsed = tierToSave
                    ),
                    extractedActions = emptyList(),
                    tags = emptyList()
                )
            }

            // Stage 2: title + summary — consult CapabilityProbe for generative vs extractive selection
            val probedSummarizerStatus = capabilityProbe.summarizerStatus()
            val activeSummarizer = if (
                probedSummarizerStatus == CapabilityStatus.AVAILABLE &&
                summarizer.status() == CapabilityStatus.AVAILABLE
            ) {
                summarizer
            } else {
                fallbackSummarizer
            }
            val ts = try {
                activeSummarizer.summarize(transcript.text)
            } catch (e: CapabilityBusyException) {
                CapabilityProbe.logDemotion("summarizer", Tier.FULL, Tier.CORE)
                fallbackSummarizer.summarize(transcript.text)
            }
            stages += "summarize:OK"

            // Stage 3: actions — consult CapabilityProbe for generative vs heuristic selection
            val probedActionsStatus = capabilityProbe.actionExtractorStatus()
            val activeExtractor = if (
                probedActionsStatus == CapabilityStatus.AVAILABLE &&
                extractor.status() == CapabilityStatus.AVAILABLE
            ) {
                extractor
            } else {
                fallbackExtractor
            }
            val actions = try {
                activeExtractor.extract(transcript.text)
            } catch (e: CapabilityBusyException) {
                CapabilityProbe.logDemotion("actions", Tier.FULL, Tier.CORE)
                fallbackExtractor.extract(transcript.text)
            }
            val actionItems = actions.map {
                ActionItem(noteId = note.id, text = it.text, dueAtEpochMs = it.dueAtEpochMs)
            }
            stages += "actions:OK"

            // Stage 4: bucket + tags (profile-driven; see BucketProfileProvider)
            val assignment = bucketer.assign(transcript.text, DefaultBucketProfileProvider.profile())
            stages += "bucket:OK"

            // Stage 5 (embed) is Tier 3 / v1.1; FTS is maintained by Room.

            return PipelineOutcome(
                updatedNote = note.copy(
                    transcript = transcript.text,
                    title = ts.title,
                    summary = ts.summary,
                    bucketKind = assignment.bucketKind,
                    tierUsed = transcript.tier.name,
                    stageStatus = stages.joinToString(",")
                ),
                extractedActions = actionItems,
                tags = assignment.tags
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
