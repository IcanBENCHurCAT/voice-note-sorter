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
import com.icanbenchurcat.voicenotes.ai.DeterministicTranscriptCleaner
import com.icanbenchurcat.voicenotes.ai.Embedder
import com.icanbenchurcat.voicenotes.ai.ExtractiveSummarizer
import com.icanbenchurcat.voicenotes.ai.ExtractedAction
import com.icanbenchurcat.voicenotes.ai.GenAiPromptClient
import com.icanbenchurcat.voicenotes.ai.GenAiPromptSession
import com.icanbenchurcat.voicenotes.ai.HeuristicActionExtractor
import com.icanbenchurcat.voicenotes.ai.HeuristicBucketer
import com.icanbenchurcat.voicenotes.ai.ImageTranscriber
import com.icanbenchurcat.voicenotes.ai.MlKitOcrImageTranscriber
import com.icanbenchurcat.voicenotes.ai.NanoActionExtractor
import com.icanbenchurcat.voicenotes.ai.NanoBucketer
import com.icanbenchurcat.voicenotes.ai.NanoImageTranscriber
import com.icanbenchurcat.voicenotes.ai.NanoProofreader
import com.icanbenchurcat.voicenotes.ai.NanoSummarizer
import com.icanbenchurcat.voicenotes.ai.NanoTranscriber
import com.icanbenchurcat.voicenotes.ai.NoOpEmbedder
import com.icanbenchurcat.voicenotes.ai.NullImageTranscriber
import com.icanbenchurcat.voicenotes.ai.NullTranscriber
import com.icanbenchurcat.voicenotes.ai.PlatformTranscriber
import com.icanbenchurcat.voicenotes.ai.Proofreader
import com.icanbenchurcat.voicenotes.ai.Summarizer
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.TitleSummary
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.ai.Transcript
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.EmbeddingCodec
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteDao
import com.icanbenchurcat.voicenotes.data.NoteEmbedding
import com.icanbenchurcat.voicenotes.data.NoteSourceType
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.data.NoteTag
import com.icanbenchurcat.voicenotes.data.Tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Outcome of running the sorting pipeline for a single [Note].
 */
data class PipelineOutcome(
    val updatedNote: Note,
    val extractedActions: List<ActionItem>,
    val tags: List<String>,
    val workingTranscript: String = updatedNote.transcript
)

/**
 * The sorting pipeline as a deferred WorkManager job: six stages, each
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
    private val fallbackExtractor: ActionExtractor = HeuristicActionExtractor(),
    private val fallbackBucketer: Bucketer = HeuristicBucketer(),
    private val proofreader: Proofreader? = null,
    private val fallbackProofreader: Proofreader = DeterministicTranscriptCleaner(),
    private val embedder: Embedder = NoOpEmbedder(),
    private val imageTranscriber: ImageTranscriber = NanoImageTranscriber(context),
    private val fallbackImageTranscriber: ImageTranscriber = MlKitOcrImageTranscriber(context),
    private val nullImageTranscriber: ImageTranscriber = NullImageTranscriber
) : CoroutineWorker(context, params) {

    // Primary constructor for WorkManager
    constructor(context: Context, params: WorkerParameters) : this(
        context = context,
        params = params,
        capabilityProbe = capabilityProbeOverride ?: CapabilityProbe.get(context),
        transcriber = transcriberOverride ?: NanoTranscriber(context),
        platformTranscriber = transcriberOverride ?: PlatformTranscriber(context),
        nullTranscriber = NullTranscriber,
        summarizer = NanoSummarizer(),
        extractor = NanoActionExtractor(),
        bucketer = NanoBucketer(),
        fallbackSummarizer = ExtractiveSummarizer(),
        fallbackExtractor = HeuristicActionExtractor(),
        fallbackBucketer = HeuristicBucketer(),
        proofreader = proofreaderOverride ?: NanoProofreader(),
        fallbackProofreader = DeterministicTranscriptCleaner(),
        embedder = embedderOverride ?: NoOpEmbedder(),
        imageTranscriber = imageTranscriberOverride ?: NanoImageTranscriber(context),
        fallbackImageTranscriber = imageTranscriberOverride ?: MlKitOcrImageTranscriber(context),
        nullImageTranscriber = NullImageTranscriber
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
            fallbackBucketer = fallbackBucketer,
            dao = dao,
            proofreader = proofreader,
            fallbackProofreader = fallbackProofreader,
            embedder = embedder,
            imageTranscriber = imageTranscriber,
            fallbackImageTranscriber = fallbackImageTranscriber,
            nullImageTranscriber = nullImageTranscriber
        )
        return Result.success()
    }

    companion object {
        const val KEY_NOTE_ID = "noteId"

        @Volatile
        var capabilityProbeOverride: CapabilityProbe? = null

        @Volatile
        var transcriberOverride: Transcriber? = null

        @Volatile
        var proofreaderOverride: Proofreader? = null

        @Volatile
        var embedderOverride: Embedder? = null

        @Volatile
        var imageTranscriberOverride: ImageTranscriber? = null

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
            fallbackBucketer: Bucketer = HeuristicBucketer(),
            dao: NoteDao? = null,
            promptSession: GenAiPromptSession? = null,
            proofreader: Proofreader? = null,
            fallbackProofreader: Proofreader = DeterministicTranscriptCleaner(),
            embedder: Embedder = NoOpEmbedder(),
            imageTranscriber: ImageTranscriber = NullImageTranscriber,
            fallbackImageTranscriber: ImageTranscriber = NullImageTranscriber,
            nullImageTranscriber: ImageTranscriber = NullImageTranscriber
        ): PipelineOutcome {
            val stages = mutableListOf<String>()
            var currentNote = note
            val profile = DefaultBucketProfileProvider.profile()

            // Ensure default profile Bucket rows are seeded before sorting
            dao?.insertBuckets(
                profile.buckets.map { Bucket(kind = it.kind, name = it.name) }
            )

            // Stage 1: transcribe — branch on sourceType (AUDIO vs IMAGE) and persist immediately
            val isImageNote = currentNote.sourceType == NoteSourceType.IMAGE.name
            val transcript = if (isImageNote) {
                transcribeStage1(
                    capabilityName = "image_transcriber",
                    probedStatus = capabilityProbe.imageTranscriberStatus(),
                    primaryStatus = { imageTranscriber.status() },
                    primaryTranscribe = { imageTranscriber.transcribeImage(currentNote.imagePath) },
                    fallbackStatus = { fallbackImageTranscriber.status() },
                    fallbackTranscribe = { fallbackImageTranscriber.transcribeImage(currentNote.imagePath) },
                    nullTranscribe = { nullImageTranscriber.transcribeImage(currentNote.imagePath) },
                    stages = stages
                )
            } else {
                transcribeStage1(
                    capabilityName = "transcriber",
                    probedStatus = capabilityProbe.transcriberStatus(),
                    primaryStatus = { transcriber.status() },
                    primaryTranscribe = { transcriber.transcribe(currentNote.audioPath) },
                    fallbackStatus = { platformTranscriber.status() },
                    fallbackTranscribe = { platformTranscriber.transcribe(currentNote.audioPath) },
                    nullTranscribe = { nullTranscriber.transcribe(currentNote.audioPath) },
                    stages = stages
                )
            }

            if (transcript == null || transcript.text.isBlank()) {
                val tierToSave = transcript?.tier?.name ?: Tier.MINIMAL.name
                val blankStatus = if (isImageNote) {
                    NoteStatus.NEEDS_REVIEW.name
                } else {
                    NoteStatus.RECORDED.name
                }
                currentNote = currentNote.copy(
                    stageStatus = stages.joinToString(","),
                    transcript = transcript?.text ?: "",
                    tierUsed = tierToSave,
                    status = blankStatus
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
                stageStatus = stages.joinToString(","),
                status = NoteStatus.TRANSCRIBED.name
            )
            dao?.update(currentNote)

            // Proofread stage (between Stage 1 and Stage 2): produces a separate workingTranscript
            // consumed by Stages 2-4 while leaving Note.transcript immutable verbatim from Stage 1.
            var workingTranscript = currentNote.transcript
            var proofreadModelSucceeded = false
            if (proofreader != null) {
                val probedProofreaderStatus = capabilityProbe.proofreaderStatus()
                try {
                    val proofreadResult = try {
                        when {
                            probedProofreaderStatus == CapabilityStatus.BUSY ||
                                proofreader.status() == CapabilityStatus.BUSY -> {
                                throw CapabilityBusyException()
                            }
                            probedProofreaderStatus == CapabilityStatus.AVAILABLE &&
                                proofreader.status() == CapabilityStatus.AVAILABLE -> {
                                val cleaned = if (proofreader is NanoProofreader) {
                                    val (text, proofreadTier) = proofreader.proofreadWithTier(currentNote.transcript)
                                    proofreadModelSucceeded = proofreadTier == Tier.FULL
                                    text
                                } else {
                                    val text = proofreader.proofread(currentNote.transcript)
                                    proofreadModelSucceeded = proofreader !== fallbackProofreader &&
                                        proofreader !is DeterministicTranscriptCleaner
                                    text
                                }
                                stages += "proofread:OK"
                                cleaned
                            }
                            else -> {
                                val fallbackText = fallbackProofreader.proofread(currentNote.transcript)
                                stages += "proofread:OK"
                                fallbackText
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CapabilityBusyException) {
                        stages += "proofread:BUSY"
                        CapabilityProbe.logDemotion("proofreader", Tier.FULL, Tier.CORE)
                        if (proofreader !== fallbackProofreader) {
                            val fallbackText = fallbackProofreader.proofread(currentNote.transcript)
                            stages += "proofread_fallback:OK"
                            fallbackText
                        } else {
                            currentNote.transcript
                        }
                    }
                    if (proofreadResult.isNotBlank()) {
                        workingTranscript = proofreadResult
                    }
                    currentNote = currentNote.copy(stageStatus = stages.joinToString(","))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stages += "proofread:FAILED"
                    currentNote = currentNote.copy(stageStatus = stages.joinToString(","))
                }
                dao?.update(currentNote)
            }

            // Stages 2-4 share one prompt session (warmup + SystemInstruction + transcript
            // PromptPrefix caching) whenever at least one Nano prompt stage is probed AVAILABLE.
            val probedSummarizerStatus = capabilityProbe.summarizerStatus()
            val probedActionsStatus = capabilityProbe.actionExtractorStatus()
            val sharedClient = resolveSharedNanoClient(
                probedSummarizerStatus = probedSummarizerStatus,
                probedActionsStatus = probedActionsStatus,
                summarizer = summarizer,
                extractor = extractor,
                bucketer = bucketer
            )
            val ownedSession = if (
                promptSession == null &&
                sharedClient != null &&
                currentNote.transcript.isNotBlank()
            ) {
                sharedClient.openSession()
            } else {
                null
            }
            val activeSession = promptSession ?: ownedSession

            var persistedActions: List<ActionItem> = emptyList()
            var persistedTags: List<String> = emptyList()
            try {
                if (activeSession != null && currentNote.transcript.isNotBlank()) {
                    try {
                        if (activeSession.checkStatus() == CapabilityStatus.AVAILABLE) {
                            activeSession.warmup()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Best-effort warmup; stages handle BUSY or demotion independently.
                    }
                }

                // Stage 2: title + summary — independently caught and persisted
                val sessionForSummarizer = sessionForStage(summarizer, activeSession, sharedClient)
                val summarizerReady = probedSummarizerStatus == CapabilityStatus.AVAILABLE &&
                    (sessionForSummarizer?.checkStatus() ?: summarizer.status()) == CapabilityStatus.AVAILABLE
                val activeSummarizer = if (summarizerReady) summarizer else fallbackSummarizer
                var stage2ModelSucceeded = false
                try {
                    val ts = try {
                        val result: TitleSummary
                        if (activeSummarizer is NanoSummarizer) {
                            val (summaryResult, summaryTier) = activeSummarizer.summarizeWithTier(
                                transcript = workingTranscript,
                                session = sessionForSummarizer
                            )
                            result = summaryResult
                            stage2ModelSucceeded = summaryTier == Tier.FULL
                        } else {
                            result = activeSummarizer.summarize(workingTranscript)
                            stage2ModelSucceeded = activeSummarizer !== fallbackSummarizer &&
                                activeSummarizer !is ExtractiveSummarizer
                        }
                        stages += "summarize:OK"
                        result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CapabilityBusyException) {
                        stages += "summarize:BUSY"
                        CapabilityProbe.logDemotion("summarizer", Tier.FULL, Tier.CORE)
                        if (activeSummarizer !== fallbackSummarizer) {
                            val fallbackResult = fallbackSummarizer.summarize(workingTranscript)
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
                val sessionForExtractor = sessionForStage(extractor, activeSession, sharedClient)
                val extractorReady = probedActionsStatus == CapabilityStatus.AVAILABLE &&
                    (sessionForExtractor?.checkStatus() ?: extractor.status()) == CapabilityStatus.AVAILABLE
                val activeExtractor = if (extractorReady) extractor else fallbackExtractor
                var stage3ModelSucceeded = false
                try {
                    val extracted = try {
                        val result: List<ExtractedAction>
                        if (activeExtractor is NanoActionExtractor) {
                            val (extractedItems, extractorTier) = activeExtractor.extractWithTier(
                                transcript = workingTranscript,
                                session = sessionForExtractor
                            )
                            result = extractedItems
                            stage3ModelSucceeded = extractorTier == Tier.FULL
                        } else {
                            result = activeExtractor.extract(workingTranscript)
                            stage3ModelSucceeded = activeExtractor !== fallbackExtractor &&
                                activeExtractor !is HeuristicActionExtractor
                        }
                        stages += "actions:OK"
                        result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CapabilityBusyException) {
                        stages += "actions:BUSY"
                        CapabilityProbe.logDemotion("actions", Tier.FULL, Tier.CORE)
                        if (activeExtractor !== fallbackExtractor) {
                            val fallbackResult = fallbackExtractor.extract(workingTranscript)
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
                val sessionForBucketer = sessionForStage(bucketer, activeSession, sharedClient)
                val bucketerReady = probedSummarizerStatus == CapabilityStatus.AVAILABLE &&
                    (sessionForBucketer?.checkStatus() ?: bucketer.status()) == CapabilityStatus.AVAILABLE
                val activeBucketer = if (bucketerReady) bucketer else fallbackBucketer
                var stage4ModelSucceeded = false
                var resolvedAssignmentTier: Tier? = null
                try {
                    val rawAssignment = try {
                        val result = if (activeBucketer is NanoBucketer) {
                            activeBucketer.assignWithSession(
                                transcript = workingTranscript,
                                profile = profile,
                                session = sessionForBucketer
                            )
                        } else {
                            activeBucketer.assign(workingTranscript, profile)
                        }
                        stages += "bucket:OK"
                        stage4ModelSucceeded = activeBucketer !== fallbackBucketer &&
                            activeBucketer !is HeuristicBucketer &&
                            result.tier == Tier.FULL
                        result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CapabilityBusyException) {
                        stages += "bucket:BUSY"
                        CapabilityProbe.logDemotion("bucketer", Tier.FULL, Tier.CORE)
                        if (activeBucketer !== fallbackBucketer) {
                            val fallbackResult = fallbackBucketer.assign(workingTranscript, profile)
                            stages += "bucket_fallback:OK"
                            fallbackResult
                        } else {
                            null
                        }
                    }
                    val assignment = rawAssignment?.let { raw ->
                        if (
                            raw.bucketKind == "INBOX" &&
                            persistedActions.isNotEmpty() &&
                            profile.buckets.any { it.kind == "TASK" }
                        ) {
                            raw.copy(
                                bucketKind = "TASK",
                                confidence = 0.8f,
                                score = persistedActions.size,
                                needsReview = false
                            )
                        } else {
                            raw
                        }
                    }
                    if (assignment != null) {
                        resolvedAssignmentTier = assignment.tier
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
                        val hadStageDegradation = stages.any { it.endsWith(":BUSY") || it.endsWith(":FAILED") }
                        val weakBucketEvidence = assignment.needsReview ||
                            assignment.bucketKind == "INBOX" ||
                            assignment.confidence < 0.7f ||
                            assignment.score <= 0
                        val finalStatus = if (hadStageDegradation || weakBucketEvidence) {
                            NoteStatus.NEEDS_REVIEW.name
                        } else {
                            NoteStatus.SORTED.name
                        }
                        val resolvedTier = resolvePipelineTier(
                            transcriptTier = transcript.tier,
                            anyModelStageSucceeded = proofreadModelSucceeded || stage2ModelSucceeded || stage3ModelSucceeded || stage4ModelSucceeded,
                            assignmentTier = assignment.tier
                        )
                        currentNote = currentNote.copy(
                            bucketKind = assignment.bucketKind,
                            tierUsed = resolvedTier.name,
                            stageStatus = stages.joinToString(","),
                            status = finalStatus
                        )
                    } else {
                        val resolvedTier = resolvePipelineTier(
                            transcriptTier = transcript.tier,
                            anyModelStageSucceeded = proofreadModelSucceeded || stage2ModelSucceeded || stage3ModelSucceeded || stage4ModelSucceeded,
                            assignmentTier = null
                        )
                        currentNote = currentNote.copy(
                            tierUsed = resolvedTier.name,
                            stageStatus = stages.joinToString(","),
                            status = NoteStatus.NEEDS_REVIEW.name
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stages += "bucket:FAILED"
                    val resolvedTier = resolvePipelineTier(
                        transcriptTier = transcript.tier,
                        anyModelStageSucceeded = proofreadModelSucceeded || stage2ModelSucceeded || stage3ModelSucceeded || stage4ModelSucceeded,
                        assignmentTier = resolvedAssignmentTier
                    )
                    currentNote = currentNote.copy(
                        tierUsed = resolvedTier.name,
                        stageStatus = stages.joinToString(","),
                        status = NoteStatus.NEEDS_REVIEW.name
                    )
                }
                dao?.update(currentNote)
            } finally {
                if (activeSession != null) {
                    withContext(NonCancellable) {
                        runCatching { activeSession.clearImplicitCaches() }
                        if (ownedSession != null) {
                            runCatching { ownedSession.close() }
                        }
                    }
                }
            }

            // Stage 5: embed — compute & persist semantic vector when an Embedder is AVAILABLE,
            // lazily re-embedding when the persisted modelId differs from the active Embedder.
            // Embeds the proofread workingTranscript (the Stages 2-4 input), never the raw
            // Note.transcript, which stays immutable verbatim from Stage 1.
            currentNote = executeStage5Embed(
                note = currentNote,
                workingTranscript = workingTranscript,
                embedder = embedder,
                dao = dao,
                stages = stages
            )

            return PipelineOutcome(
                updatedNote = currentNote,
                extractedActions = persistedActions,
                tags = persistedTags,
                workingTranscript = workingTranscript
            )
        }

        private suspend fun executeStage5Embed(
            note: Note,
            workingTranscript: String,
            embedder: Embedder,
            dao: NoteDao?,
            stages: MutableList<String>
        ): Note {
            try {
                when (embedder.status()) {
                    CapabilityStatus.AVAILABLE -> {
                        val existing = dao?.embeddingForNote(note.id)
                        if (existing != null && existing.modelId == embedder.modelId) {
                            stages += "embed:OK"
                        } else {
                            val embedInput = "${note.title}\n${note.summary}\n$workingTranscript"
                            val vector = embedder.embed(embedInput)
                            if (vector != null && vector.isNotEmpty()) {
                                dao?.upsertEmbedding(
                                    NoteEmbedding(
                                        noteId = note.id,
                                        modelId = embedder.modelId,
                                        dimension = vector.size,
                                        vectorBlob = EmbeddingCodec.encode(vector),
                                        updatedAtEpochMs = System.currentTimeMillis()
                                    )
                                )
                                stages += "embed:OK"
                            } else {
                                stages += "embed:SKIPPED"
                            }
                        }
                    }
                    CapabilityStatus.BUSY -> {
                        CapabilityProbe.logDemotion("embedder", Tier.FULL, Tier.CORE)
                        stages += "embed:BUSY"
                    }
                    CapabilityStatus.UNAVAILABLE, CapabilityStatus.DOWNLOADABLE -> {
                        stages += "embed:SKIPPED"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: CapabilityBusyException) {
                CapabilityProbe.logDemotion("embedder", Tier.FULL, Tier.CORE)
                stages += "embed:BUSY"
            } catch (_: Exception) {
                stages += "embed:FAILED"
            }
            val updated = note.copy(stageStatus = stages.joinToString(","))
            dao?.update(updated)
            return updated
        }

        private fun resolveSharedNanoClient(
            probedSummarizerStatus: CapabilityStatus,
            probedActionsStatus: CapabilityStatus,
            summarizer: Summarizer,
            extractor: ActionExtractor,
            bucketer: Bucketer
        ): GenAiPromptClient? {
            if (probedSummarizerStatus == CapabilityStatus.AVAILABLE) {
                (summarizer as? NanoSummarizer)?.promptClient?.let { return it }
                (bucketer as? NanoBucketer)?.promptClient?.let { return it }
            }
            if (probedActionsStatus == CapabilityStatus.AVAILABLE) {
                (extractor as? NanoActionExtractor)?.promptClient?.let { return it }
            }
            return null
        }

        private fun sessionForStage(
            stage: Any,
            activeSession: GenAiPromptSession?,
            sharedClient: GenAiPromptClient?
        ): GenAiPromptSession? {
            if (activeSession == null) return null
            val stageClient = when (stage) {
                is NanoSummarizer -> stage.promptClient
                is NanoActionExtractor -> stage.promptClient
                is NanoBucketer -> stage.promptClient
                else -> return null
            }
            return if (sharedClient == null || stageClient === sharedClient) {
                activeSession
            } else {
                null
            }
        }

        private fun resolvePipelineTier(
            transcriptTier: Tier,
            anyModelStageSucceeded: Boolean,
            assignmentTier: Tier?
        ): Tier {
            if (anyModelStageSucceeded || assignmentTier == Tier.FULL) {
                return Tier.FULL
            }
            return if (transcriptTier == Tier.FULL) {
                Tier.CORE
            } else {
                transcriptTier
            }
        }

        private suspend fun transcribeStage1(
            capabilityName: String,
            probedStatus: CapabilityStatus,
            primaryStatus: suspend () -> CapabilityStatus,
            primaryTranscribe: suspend () -> Transcript,
            fallbackStatus: suspend () -> CapabilityStatus,
            fallbackTranscribe: suspend () -> Transcript,
            nullTranscribe: suspend () -> Transcript,
            stages: MutableList<String>
        ): Transcript? = when (probedStatus) {
            CapabilityStatus.UNAVAILABLE -> {
                CapabilityProbe.logDemotion(capabilityName, Tier.FULL, Tier.MINIMAL)
                val t = nullTranscribe()
                stages += "transcribe_null:OK"
                t
            }
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.BUSY -> {
                CapabilityProbe.logDemotion(capabilityName, Tier.FULL, Tier.CORE)
                transcribeWithFallbackOrNull(
                    capabilityName = capabilityName,
                    fallbackStatus = fallbackStatus,
                    fallbackTranscribe = fallbackTranscribe,
                    nullTranscribe = nullTranscribe,
                    stages = stages
                )
            }
            CapabilityStatus.AVAILABLE -> {
                if (primaryStatus() == CapabilityStatus.AVAILABLE) {
                    try {
                        val t = primaryTranscribe()
                        stages += "transcribe:OK"
                        t
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: CapabilityBusyException) {
                        stages += "transcribe:BUSY"
                        CapabilityProbe.logDemotion(capabilityName, Tier.FULL, Tier.CORE)
                        transcribeWithFallbackOrNull(
                            capabilityName = capabilityName,
                            fallbackStatus = fallbackStatus,
                            fallbackTranscribe = fallbackTranscribe,
                            nullTranscribe = nullTranscribe,
                            stages = stages
                        )
                    } catch (_: Exception) {
                        stages += "transcribe:FAILED"
                        null
                    }
                } else {
                    CapabilityProbe.logDemotion(capabilityName, Tier.FULL, Tier.CORE)
                    transcribeWithFallbackOrNull(
                        capabilityName = capabilityName,
                        fallbackStatus = fallbackStatus,
                        fallbackTranscribe = fallbackTranscribe,
                        nullTranscribe = nullTranscribe,
                        stages = stages
                    )
                }
            }
        }

        private suspend fun transcribeWithFallbackOrNull(
            capabilityName: String,
            fallbackStatus: suspend () -> CapabilityStatus,
            fallbackTranscribe: suspend () -> Transcript,
            nullTranscribe: suspend () -> Transcript,
            stages: MutableList<String>
        ): Transcript? = if (fallbackStatus() == CapabilityStatus.AVAILABLE) {
            try {
                val t = fallbackTranscribe()
                stages += "transcribe_fallback:OK"
                t
            } catch (e2: CancellationException) {
                throw e2
            } catch (_: CapabilityBusyException) {
                stages += "transcribe_fallback:BUSY"
                CapabilityProbe.logDemotion(capabilityName, Tier.CORE, Tier.MINIMAL)
                val t = nullTranscribe()
                stages += "transcribe_null:OK"
                t
            } catch (_: Exception) {
                stages += "transcribe_fallback:FAILED"
                null
            }
        } else {
            CapabilityProbe.logDemotion(capabilityName, Tier.CORE, Tier.MINIMAL)
            val t = nullTranscribe()
            stages += "transcribe_null:OK"
            t
        }
    }
}
