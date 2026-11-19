// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.service
import com.icanbenchurcat.voicenotes.fixtures.*

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.icanbenchurcat.voicenotes.ai.ActionExtractor
import com.icanbenchurcat.voicenotes.ai.BucketAssignment
import com.icanbenchurcat.voicenotes.ai.BucketProfile
import com.icanbenchurcat.voicenotes.ai.Bucketer
import com.icanbenchurcat.voicenotes.ai.CapabilityBusyException
import com.icanbenchurcat.voicenotes.ai.CapabilityProbe
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.DeterministicTranscriptCleaner
import com.icanbenchurcat.voicenotes.ai.ExtractiveSummarizer
import com.icanbenchurcat.voicenotes.ai.ExtractedAction
import com.icanbenchurcat.voicenotes.ai.FakeBundledOcrEngine
import com.icanbenchurcat.voicenotes.ai.FakeCapabilityChecker
import com.icanbenchurcat.voicenotes.ai.FakeGenAiPromptClient
import com.icanbenchurcat.voicenotes.ai.HeuristicActionExtractor
import com.icanbenchurcat.voicenotes.ai.HeuristicBucketer
import com.icanbenchurcat.voicenotes.ai.MlKitOcrImageTranscriber
import com.icanbenchurcat.voicenotes.ai.NanoActionExtractor
import com.icanbenchurcat.voicenotes.ai.NanoBucketer
import com.icanbenchurcat.voicenotes.ai.NanoImageTranscriber
import com.icanbenchurcat.voicenotes.ai.NanoProofreader
import com.icanbenchurcat.voicenotes.ai.NanoSummarizer
import com.icanbenchurcat.voicenotes.ai.NullImageTranscriber
import com.icanbenchurcat.voicenotes.ai.NullTranscriber
import com.icanbenchurcat.voicenotes.ai.Summarizer
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.TitleSummary
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.ai.NOTE_ANALYSIS_SYSTEM_INSTRUCTION
import com.icanbenchurcat.voicenotes.ai.PromptRequest
import com.icanbenchurcat.voicenotes.ai.StructuredActionExtraction
import com.icanbenchurcat.voicenotes.ai.StructuredActionItem
import com.icanbenchurcat.voicenotes.ai.StructuredBucketAssignment
import com.icanbenchurcat.voicenotes.ai.StructuredTitleSummary
import com.icanbenchurcat.voicenotes.ai.Transcript
import com.icanbenchurcat.voicenotes.ai.buildTranscriptPrefix
import com.icanbenchurcat.voicenotes.data.ActionItem
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

class SortingWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val file = tempFolder.newFile("worker_test_caps.preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { file }
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    private fun sampleNote() = Note(
        id = 1L,
        createdAtEpochMs = 1_700_000_000_000L,
        audioPath = "/data/local/tmp/sample.m4a"
    )

    @Test
    fun probeUnavailable_selectsNullTranscriberDirectly_tier1NoAiPath() = runTest {
        val checker = FakeCapabilityChecker(
            transcriber = CapabilityStatus.UNAVAILABLE,
            summarizer = CapabilityStatus.UNAVAILABLE,
            actions = CapabilityStatus.UNAVAILABLE
        )
        val probe = CapabilityProbe(checker, dataStore)
        val primary = FakeTranscriber(result = Transcript("should not run", Tier.FULL))
        val platform = FakeTranscriber(result = Transcript("should not run", Tier.CORE))

        val outcome = SortingWorker.executePipeline(
            note = sampleNote(),
            capabilityProbe = probe,
            transcriber = primary,
            platformTranscriber = platform,
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer()
        )

        assertEquals(0, primary.calls)
        assertEquals(0, platform.calls)
        assertEquals("", outcome.updatedNote.transcript)
        assertEquals("MINIMAL", outcome.updatedNote.tierUsed)
        assertEquals("transcribe_null:OK", outcome.updatedNote.stageStatus)
    }

    @Test
    fun busyThrowsDemotesToPlatform() = runTest {
        val checker = FakeCapabilityChecker(
            transcriber = CapabilityStatus.AVAILABLE,
            summarizer = CapabilityStatus.UNAVAILABLE,
            actions = CapabilityStatus.UNAVAILABLE
        )
        val probe = CapabilityProbe(checker, dataStore)
        val primary = FakeTranscriber(exception = CapabilityBusyException())
        val platform = FakeTranscriber(result = Transcript("platform transcript", Tier.CORE))

        val outcome = SortingWorker.executePipeline(
            note = sampleNote(),
            capabilityProbe = probe,
            transcriber = primary,
            platformTranscriber = platform,
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer()
        )

        assertEquals(1, primary.calls)
        assertEquals(1, platform.calls)
        assertEquals("platform transcript", outcome.updatedNote.transcript)
        assertEquals("CORE", outcome.updatedNote.tierUsed)
        assertEquals(
            "transcribe:BUSY,transcribe_fallback:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
    }

    @Test
    fun allTranscribersUnavailableCompletesMinimal() = runTest {
        val checker = FakeCapabilityChecker(
            transcriber = CapabilityStatus.AVAILABLE,
            summarizer = CapabilityStatus.UNAVAILABLE,
            actions = CapabilityStatus.UNAVAILABLE
        )
        val probe = CapabilityProbe(checker, dataStore)
        val primary = FakeTranscriber(exception = CapabilityBusyException())
        val platform = FakeTranscriber(exception = CapabilityBusyException())

        val outcome = SortingWorker.executePipeline(
            note = sampleNote(),
            capabilityProbe = probe,
            transcriber = primary,
            platformTranscriber = platform,
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer()
        )

        assertEquals("", outcome.updatedNote.transcript)
        assertEquals("MINIMAL", outcome.updatedNote.tierUsed)
        assertEquals(
            "transcribe:BUSY,transcribe_fallback:BUSY,transcribe_null:OK",
            outcome.updatedNote.stageStatus
        )
    }

    @Test
    fun probeDownloadable_demotesToPlatformWithoutCallingPrimary() = runTest {
        val checker = FakeCapabilityChecker(
            transcriber = CapabilityStatus.DOWNLOADABLE,
            summarizer = CapabilityStatus.UNAVAILABLE,
            actions = CapabilityStatus.UNAVAILABLE
        )
        val probe = CapabilityProbe(checker, dataStore)
        val primary = FakeTranscriber(result = Transcript("nano", Tier.FULL))
        val platform = FakeTranscriber(result = Transcript("platform", Tier.CORE))

        val outcome = SortingWorker.executePipeline(
            note = sampleNote(),
            capabilityProbe = probe,
            transcriber = primary,
            platformTranscriber = platform,
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer()
        )

        assertEquals(0, primary.calls)
        assertEquals(1, platform.calls)
        assertEquals("platform", outcome.updatedNote.transcript)
        assertEquals("CORE", outcome.updatedNote.tierUsed)
        assertEquals(
            "transcribe_fallback:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
    }

    @Test
    fun interruptionAfterTranscription_preservesTranscriptInDatabase() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())
        val initial = dao.byId(noteId)!!

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val cancellingSummarizer = FakeSummarizer(
            exception = CancellationException("Simulated worker interruption during Stage 2")
        )

        try {
            SortingWorker.executePipeline(
                note = initial,
                capabilityProbe = probe,
                transcriber = FakeTranscriber(result = Transcript("Preserved transcript text", Tier.FULL)),
                platformTranscriber = FakeTranscriber(),
                nullTranscriber = NullTranscriber,
                summarizer = cancellingSummarizer,
                extractor = FakeActionExtractor(),
                bucketer = FakeBucketer(),
                dao = dao
            )
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // Expected interruption
        }

        val savedAfterInterruption = dao.byId(noteId)!!
        assertEquals("Preserved transcript text", savedAfterInterruption.transcript)
        assertEquals("FULL", savedAfterInterruption.tierUsed)
        assertEquals("transcribe:OK", savedAfterInterruption.stageStatus)
    }

    @Test
    fun exceptionsInStages2To4_areCaughtPerStage_recordedInStageStatus_andDoNotAbortRemainingStages() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())
        val initial = dao.byId(noteId)!!

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val failingSummarizer = FakeSummarizer(exception = IllegalStateException("Summarizer crashed"))
        val failingExtractor = FakeActionExtractor(exception = RuntimeException("Extractor crashed"))
        val succeedingBucketer = FakeBucketer(
            assignment = BucketAssignment("TASK", listOf("urgent"), Tier.CORE)
        )

        // Case 1: Stages 2 and 3 fail, Stage 4 still runs and succeeds
        val outcome1 = SortingWorker.executePipeline(
            note = initial,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Remind me to call.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = failingSummarizer,
            extractor = failingExtractor,
            bucketer = succeedingBucketer,
            fallbackSummarizer = failingSummarizer,
            fallbackExtractor = failingExtractor,
            dao = dao
        )

        assertEquals(
            "transcribe:OK,summarize:FAILED,actions:FAILED,bucket:OK,embed:SKIPPED",
            outcome1.updatedNote.stageStatus
        )
        assertEquals("TASK", outcome1.updatedNote.bucketKind)
        assertEquals(listOf("urgent"), dao.tagsFor(noteId))

        // Case 2: Stage 4 throws an exception -> recorded as bucket:FAILED without failing worker
        val failingBucketer = FakeBucketer(exception = IllegalArgumentException("Bucketer crashed"))
        val outcome2 = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Remind me to call.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(result = TitleSummary("Title", "Summary")),
            extractor = FakeActionExtractor(items = listOf(ExtractedAction("Call tomorrow"))),
            bucketer = failingBucketer,
            dao = dao
        )

        assertEquals(
            "transcribe:OK,summarize:OK,actions:OK,bucket:FAILED,embed:SKIPPED",
            outcome2.updatedNote.stageStatus
        )
        assertEquals("Title", outcome2.updatedNote.title)
        assertEquals(1, dao.actionsFor(noteId).size)
    }

    @Test
    fun runningPipelineTwice_isIdempotent_andCreatesZeroDuplicateActionsOrTags() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val transcriber = FakeTranscriber(
            result = Transcript("Remind me to buy milk. Don't forget to call Sam.", Tier.CORE)
        )
        val summarizer = FakeSummarizer(
            result = TitleSummary("Errands reminder", "Buy milk and call Sam.")
        )
        val extractor = FakeActionExtractor(
            items = listOf(
                ExtractedAction("Remind me to buy milk."),
                ExtractedAction("Don't forget to call Sam."),
                ExtractedAction("Remind me to buy milk.") // duplicate in single run also deduped
            )
        )
        val bucketer = FakeBucketer(
            assignment = BucketAssignment("TASK", listOf("errands", "home", "errands"), Tier.CORE)
        )

        // First run
        SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = transcriber,
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = summarizer,
            extractor = extractor,
            bucketer = bucketer,
            dao = dao
        )
        // Verify each of the 5 stages wrote independently to the DAO during the first run
        assertEquals(5, dao.updateSnapshots.size)

        val noteAfterRun1 = dao.byId(noteId)!!
        val actionsAfterRun1 = dao.actionsFor(noteId).map { it.copy(id = 0L) }
        val tagsAfterRun1 = dao.tagsFor(noteId)

        assertEquals(2, actionsAfterRun1.size)
        assertEquals(listOf("errands", "home"), tagsAfterRun1)

        // Second run on the already-sorted note
        SortingWorker.executePipeline(
            note = noteAfterRun1,
            capabilityProbe = probe,
            transcriber = transcriber,
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = summarizer,
            extractor = extractor,
            bucketer = bucketer,
            dao = dao
        )

        val noteAfterRun2 = dao.byId(noteId)!!
        val actionsAfterRun2 = dao.actionsFor(noteId).map { it.copy(id = 0L) }
        val tagsAfterRun2 = dao.tagsFor(noteId)

        assertEquals(noteAfterRun1, noteAfterRun2)
        assertEquals(actionsAfterRun1, actionsAfterRun2)
        assertEquals(tagsAfterRun1, tagsAfterRun2)
        assertTrue(actionsAfterRun2.size == 2)
    }

    @Test
    fun heuristicBucketerAndPipeline_persistDerivedTagsAndBucketRowInDatabase() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(
                result = Transcript(
                    "Remind me to buy oat milk and call the electrician.",
                    Tier.CORE
                )
            ),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao
        )

        assertEquals("TASK", outcome.updatedNote.bucketKind)
        assertEquals(NoteStatus.SORTED.name, outcome.updatedNote.status)

        // Assigned bucket exists as a Bucket row and default profile buckets are seeded
        val taskBucket = dao.bucketByKind("TASK")
        assertNotNull(taskBucket)
        assertEquals("Tasks", taskBucket?.name)
        assertEquals(
            setOf("IDEA", "INBOX", "LIST", "NOTE", "TASK"),
            dao.allBuckets().map { it.kind }.toSet()
        )

        // HeuristicBucketer derived tags from matched keywords ("remind me", "buy", "call")
        val savedTags = dao.tagsFor(noteId)
        assertEquals(listOf("buy", "call", "remind-me"), savedTags)
        assertEquals(
            listOf(Tag("buy"), Tag("call"), Tag("remind-me")),
            dao.allTags()
        )
        assertEquals(1, dao.observeByTag("buy").first().size)
    }

    @Test
    fun noSignalTranscript_landsInInboxAndSetsNeedsReviewStatus() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(
                result = Transcript(
                    "The buyer could not recall the afternoon schedule.",
                    Tier.CORE
                )
            ),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao
        )

        assertEquals("INBOX", outcome.updatedNote.bucketKind)
        assertEquals(NoteStatus.NEEDS_REVIEW.name, outcome.updatedNote.status)
        assertEquals(1, dao.observeByStatus(NoteStatus.NEEDS_REVIEW.name).first().size)

        // Confirming or changing the bucket clears NEEDS_REVIEW to SORTED
        dao.confirmOrChangeBucket(noteId = noteId, bucketKind = "NOTE", status = NoteStatus.SORTED.name)
        val confirmed = dao.byId(noteId)!!
        assertEquals("NOTE", confirmed.bucketKind)
        assertEquals(NoteStatus.SORTED.name, confirmed.status)
    }

    @Test
    fun stageThatFellBackAfterBusy_setsNeedsReviewStatusEvenWithStrongBucketMatch() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(
                result = Transcript("Remind me to call the electrician and buy wire.", Tier.FULL)
            ),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(exception = CapabilityBusyException()),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            fallbackSummarizer = ExtractiveSummarizer(),
            dao = dao
        )

        assertEquals("TASK", outcome.updatedNote.bucketKind)
        assertEquals(NoteStatus.NEEDS_REVIEW.name, outcome.updatedNote.status)
        assertTrue(outcome.updatedNote.stageStatus.contains("summarize:BUSY"))
        assertEquals("CORE", outcome.updatedNote.tierUsed)
    }

    @Test
    fun tierFullAvailable_selectsNanoGenerativeModels_andSetsTierUsedFull() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val zone = ZoneId.of("UTC")
        val fixedClock = Clock.fixed(
            LocalDateTime.of(2026, 11, 2, 10, 0, 0).atZone(zone).toInstant(),
            zone
        )

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            responder = { prompt ->
                when {
                    prompt.startsWith("Summarize this voice note transcript.") ->
                        "Title: Plumbing & Parts Run\nSummary: Call the plumber tomorrow at 2pm and pick up replacement gaskets."
                    prompt.startsWith("Extract concrete action items") ->
                        "ACTION: Call the plumber | DUE: tomorrow at 2pm\nACTION: Pick up replacement gaskets | DUE: NONE"
                    prompt.startsWith("Classify this voice note transcript") ->
                        "BUCKET: TASK\nTAGS: plumbing, home-repair"
                    else -> ""
                }
            }
        )

        val fallbackSummarizer = FakeSummarizer(result = TitleSummary("Fallback", "Fallback summary"))
        val fallbackExtractor = FakeActionExtractor(items = listOf(ExtractedAction("Fallback action")))
        val fallbackBucketer = FakeBucketer(BucketAssignment("INBOX", listOf("fallback"), Tier.CORE))

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(
                result = Transcript(
                    "Need to call the plumber tomorrow at 2pm and pick up replacement gaskets.",
                    Tier.FULL
                )
            ),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = NanoSummarizer(promptClient, fallbackSummarizer),
            extractor = NanoActionExtractor(promptClient, fallbackExtractor, fixedClock),
            bucketer = NanoBucketer(promptClient, fallbackBucketer),
            fallbackSummarizer = fallbackSummarizer,
            fallbackExtractor = fallbackExtractor,
            fallbackBucketer = fallbackBucketer,
            dao = dao
        )

        assertEquals(3, promptClient.generateCalls)
        assertEquals(0, fallbackSummarizer.calls)
        assertEquals(0, fallbackExtractor.calls)
        assertEquals(0, fallbackBucketer.calls)

        assertEquals("Plumbing & Parts Run", outcome.updatedNote.title)
        assertEquals(
            "Call the plumber tomorrow at 2pm and pick up replacement gaskets.",
            outcome.updatedNote.summary
        )
        assertEquals("TASK", outcome.updatedNote.bucketKind)
        assertEquals(listOf("home-repair", "plumbing"), outcome.tags)
        assertEquals(2, outcome.extractedActions.size)
        assertNotNull(outcome.extractedActions[0].dueAtEpochMs)
        assertEquals("FULL", outcome.updatedNote.tierUsed)
        assertEquals(NoteStatus.SORTED.name, outcome.updatedNote.status)
        assertEquals(
            "transcribe:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
    }

    @Test
    fun probeUnavailable_usesFallbacksWithoutCallingPromptModels_andSetsTierUsedCoreEvenWhenTranscriptFull() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = "Title: Should Not Run\nSummary: Should not run."
        )
        val fallbackSummarizer = FakeSummarizer(result = TitleSummary("Extractive title", "Extractive summary"))
        val fallbackExtractor = FakeActionExtractor(items = listOf(ExtractedAction("Heuristic task")))
        val fallbackBucketer = FakeBucketer(BucketAssignment("TASK", listOf("heuristic"), Tier.CORE))

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(
                result = Transcript("Remind me to call Sam.", Tier.FULL)
            ),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = NanoSummarizer(promptClient, fallbackSummarizer),
            extractor = NanoActionExtractor(promptClient, fallbackExtractor),
            bucketer = NanoBucketer(promptClient, fallbackBucketer),
            fallbackSummarizer = fallbackSummarizer,
            fallbackExtractor = fallbackExtractor,
            fallbackBucketer = fallbackBucketer,
            dao = dao
        )

        assertEquals(0, promptClient.generateCalls)
        assertEquals(1, fallbackSummarizer.calls)
        assertEquals(1, fallbackExtractor.calls)
        assertEquals(1, fallbackBucketer.calls)
        assertEquals("Extractive title", outcome.updatedNote.title)
        assertEquals("TASK", outcome.updatedNote.bucketKind)
        assertEquals("CORE", outcome.updatedNote.tierUsed)
        assertEquals(
            "transcribe:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
    }

    @Test
    fun stages2To4Busy_demotesAllToFallbacks_setsNeedsReviewAndTierUsedCore() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val busyPromptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            generateException = CapabilityBusyException()
        )
        val fallbackSummarizer = FakeSummarizer(result = TitleSummary("Fallback Title", "Fallback Summary"))
        val fallbackExtractor = FakeActionExtractor(items = listOf(ExtractedAction("Call Sam")))
        val fallbackBucketer = FakeBucketer(BucketAssignment("TASK", listOf("call"), Tier.CORE))

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(
                result = Transcript("Remind me to call Sam tomorrow.", Tier.FULL)
            ),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = NanoSummarizer(busyPromptClient, fallbackSummarizer),
            extractor = NanoActionExtractor(busyPromptClient, fallbackExtractor),
            bucketer = NanoBucketer(busyPromptClient, fallbackBucketer),
            fallbackSummarizer = fallbackSummarizer,
            fallbackExtractor = fallbackExtractor,
            fallbackBucketer = fallbackBucketer,
            dao = dao
        )

        assertEquals(3, busyPromptClient.generateCalls)
        assertEquals(1, fallbackSummarizer.calls)
        assertEquals(1, fallbackExtractor.calls)
        assertEquals(1, fallbackBucketer.calls)
        assertEquals("CORE", outcome.updatedNote.tierUsed)
        assertEquals(NoteStatus.NEEDS_REVIEW.name, outcome.updatedNote.status)
        assertEquals(
            "transcribe:OK,summarize:BUSY,summarize_fallback:OK,actions:BUSY,actions_fallback:OK,bucket:BUSY,bucket_fallback:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
    }

    @Test
    fun bucketerStatusUnavailable_selectsFallbackBucketer_andPartialModelSuccessPreservesFullTier() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val primaryBucketer = FakeBucketer(
            assignment = BucketAssignment("IDEA", listOf("unused"), Tier.FULL),
            currentStatus = CapabilityStatus.UNAVAILABLE
        )
        val fallbackBucketer = FakeBucketer(
            assignment = BucketAssignment("TASK", listOf("fallback-tag"), Tier.CORE)
        )

        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            responder = { prompt ->
                if (prompt.startsWith("Summarize")) {
                    "Title: Generative Title\nSummary: Generative Summary."
                } else {
                    "NONE"
                }
            }
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Call Sam.", Tier.FULL)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = NanoSummarizer(promptClient),
            extractor = NanoActionExtractor(promptClient),
            bucketer = primaryBucketer,
            fallbackSummarizer = ExtractiveSummarizer(),
            fallbackExtractor = HeuristicActionExtractor(),
            fallbackBucketer = fallbackBucketer,
            dao = dao
        )

        assertEquals(0, primaryBucketer.calls)
        assertEquals(1, fallbackBucketer.calls)
        assertEquals("TASK", outcome.updatedNote.bucketKind)
        assertEquals(listOf("fallback-tag"), outcome.tags)
        // Stages 2 and 3 succeeded on Nano models -> tierUsed remains FULL
        assertEquals("FULL", outcome.updatedNote.tierUsed)
    }

    @Test
    fun nanoModelsUnparseableInAllStages2To4_fallsBackToHeuristics_andSetsTierUsedCore() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val unparseableClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = "Unparseable gibberish output"
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(
                result = Transcript("Remind me to buy oat milk and call Sam.", Tier.FULL)
            ),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = NanoSummarizer(unparseableClient, ExtractiveSummarizer()),
            extractor = NanoActionExtractor(unparseableClient, HeuristicActionExtractor()),
            bucketer = NanoBucketer(unparseableClient, HeuristicBucketer()),
            fallbackSummarizer = ExtractiveSummarizer(),
            fallbackExtractor = HeuristicActionExtractor(),
            fallbackBucketer = HeuristicBucketer(),
            dao = dao
        )

        assertEquals(3, unparseableClient.generateCalls)
        assertEquals("TASK", outcome.updatedNote.bucketKind)
        assertEquals(listOf("buy", "call", "remind-me"), outcome.tags)
        assertEquals("CORE", outcome.updatedNote.tierUsed)
    }

    @Test
    fun inboxKeywordFallback_promotedToTaskWhenActionsExtracted() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(
                result = Transcript("Pay the water bill tomorrow.", Tier.CORE)
            ),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao
        )

        assertEquals(1, outcome.extractedActions.size)
        assertEquals("TASK", outcome.updatedNote.bucketKind)
        assertEquals(NoteStatus.SORTED.name, outcome.updatedNote.status)
        assertEquals("transcribe:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED", outcome.updatedNote.stageStatus)
    }

    @Test
    fun stage5_noOpEmbedder_recordsEmbedSkipped_andWritesNoEmbedding() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())
        val probe = CapabilityProbe(
            FakeCapabilityChecker(transcriber = CapabilityStatus.AVAILABLE),
            dataStore
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Buy milk today.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao
        )

        assertTrue(outcome.updatedNote.stageStatus.endsWith("embed:SKIPPED"))
        assertEquals(null, dao.embeddingForNote(noteId))
    }

    @Test
    fun stage5_availableEmbedder_computesOverTitleSummaryTranscript_andPersistsEmbedding() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())
        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val expectedVector = floatArrayOf(0.6f, 0.8f, 0.0f)
        val embedder = FakeEmbedder(
            modelId = "gecko-test-v1",
            defaultVector = expectedVector
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Transcript body here.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(result = TitleSummary("Title One", "Summary One")),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer(BucketAssignment("NOTE", emptyList(), Tier.CORE)),
            dao = dao,
            embedder = embedder
        )

        assertEquals(1, embedder.calls)
        assertEquals("Title One\nSummary One\nTranscript body here.", embedder.inputs.single())
        assertTrue(outcome.updatedNote.stageStatus.endsWith("embed:OK"))

        val stored = dao.embeddingForNote(noteId)
        assertNotNull(stored)
        assertEquals(noteId, stored!!.noteId)
        assertEquals("gecko-test-v1", stored.modelId)
        assertEquals(3, stored.dimension)
        val decoded = EmbeddingCodec.decode(stored.vectorBlob, stored.dimension)!!
        assertEquals(expectedVector.toList(), decoded.toList())
    }

    @Test
    fun stage5_availableEmbedder_isIdempotentWhenModelIdMatches_andReEmbedsWhenModelIdChanges() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())
        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val embedderV1 = FakeEmbedder(
            modelId = "embed-v1",
            defaultVector = floatArrayOf(1.0f, 0.0f)
        )

        val run1 = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Order coffee beans.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(result = TitleSummary("Coffee", "Order coffee beans.")),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer(BucketAssignment("TASK", listOf("coffee"), Tier.CORE)),
            dao = dao,
            embedder = embedderV1
        )
        val storedRun1 = dao.embeddingForNote(noteId)!!
        assertEquals(1, embedderV1.calls)
        assertEquals("embed-v1", storedRun1.modelId)

        // Second run with the SAME modelId -> idempotent, does not call embedder again
        val run2 = SortingWorker.executePipeline(
            note = run1.updatedNote,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Order coffee beans.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(result = TitleSummary("Coffee", "Order coffee beans.")),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer(BucketAssignment("TASK", listOf("coffee"), Tier.CORE)),
            dao = dao,
            embedder = embedderV1
        )
        val storedRun2 = dao.embeddingForNote(noteId)!!
        assertEquals(1, embedderV1.calls)
        assertEquals(run1.updatedNote, run2.updatedNote)
        assertEquals(storedRun1, storedRun2)

        // Third run with a NEW modelId -> lazily re-embeds and replaces stored row
        val embedderV2 = FakeEmbedder(
            modelId = "embed-v2",
            defaultVector = floatArrayOf(0.0f, 1.0f, 0.5f)
        )
        val run3 = SortingWorker.executePipeline(
            note = run2.updatedNote,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Order coffee beans.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(result = TitleSummary("Coffee", "Order coffee beans.")),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer(BucketAssignment("TASK", listOf("coffee"), Tier.CORE)),
            dao = dao,
            embedder = embedderV2
        )
        val storedRun3 = dao.embeddingForNote(noteId)!!
        assertEquals(1, embedderV2.calls)
        assertTrue(run3.updatedNote.stageStatus.endsWith("embed:OK"))
        assertEquals("embed-v2", storedRun3.modelId)
        assertEquals(3, storedRun3.dimension)
        assertEquals(
            listOf(0.0f, 1.0f, 0.5f),
            EmbeddingCodec.decode(storedRun3.vectorBlob, storedRun3.dimension)!!.toList()
        )
    }

    @Test
    fun stage5_embedderBusyOrThrows_recordsHonestStatusWithoutLosingStages1To4() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())
        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        // Case 1: embed() throws CapabilityBusyException
        val busyOutcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Schedule dentist.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(result = TitleSummary("Dentist", "Schedule dentist.")),
            extractor = FakeActionExtractor(items = listOf(ExtractedAction("Schedule dentist"))),
            bucketer = FakeBucketer(BucketAssignment("TASK", listOf("health"), Tier.CORE)),
            dao = dao,
            embedder = FakeEmbedder(exception = CapabilityBusyException())
        )
        assertEquals(
            "transcribe:OK,summarize:OK,actions:OK,bucket:OK,embed:BUSY",
            busyOutcome.updatedNote.stageStatus
        )
        assertEquals("TASK", busyOutcome.updatedNote.bucketKind)
        assertEquals(NoteStatus.SORTED.name, busyOutcome.updatedNote.status)
        assertEquals(null, dao.embeddingForNote(noteId))

        // Case 2: status() returns BUSY
        val statusBusyOutcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Schedule dentist.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(result = TitleSummary("Dentist", "Schedule dentist.")),
            extractor = FakeActionExtractor(items = listOf(ExtractedAction("Schedule dentist"))),
            bucketer = FakeBucketer(BucketAssignment("TASK", listOf("health"), Tier.CORE)),
            dao = dao,
            embedder = FakeEmbedder(currentStatus = CapabilityStatus.BUSY)
        )
        assertTrue(statusBusyOutcome.updatedNote.stageStatus.endsWith("embed:BUSY"))

        // Case 3: unexpected RuntimeException -> embed:FAILED, note preserved
        val failedOutcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript("Schedule dentist.", Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(result = TitleSummary("Dentist", "Schedule dentist.")),
            extractor = FakeActionExtractor(items = listOf(ExtractedAction("Schedule dentist"))),
            bucketer = FakeBucketer(BucketAssignment("TASK", listOf("health"), Tier.CORE)),
            dao = dao,
            embedder = FakeEmbedder(exception = IllegalStateException("Model crashed"))
        )
        assertTrue(failedOutcome.updatedNote.stageStatus.endsWith("embed:FAILED"))
        assertEquals("Dentist", failedOutcome.updatedNote.title)
    }

    @Test
    fun nanoPipeline_reusesSingleSessionWithSharedPrefixWarmupAndCleanup_andPrefersStructuredOutput() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val zone = ZoneId.of("UTC")
        val fixedClock = Clock.fixed(
            LocalDateTime.of(2026, 11, 2, 10, 0, 0).atZone(zone).toInstant(),
            zone
        )

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val transcriptText = "Need to call the plumber tomorrow at 2pm and pick up replacement gaskets."
        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            systemPromptAvailable = true,
            cachingAvailable = true,
            typedResponder = { _, outputClass ->
                when (outputClass) {
                    StructuredTitleSummary::class -> StructuredTitleSummary(
                        title = "Plumbing & Parts Run",
                        summary = "Call the plumber tomorrow at 2pm and pick up replacement gaskets."
                    )
                    StructuredActionExtraction::class -> StructuredActionExtraction(
                        actions = listOf(
                            StructuredActionItem("Call the plumber", "tomorrow at 2pm"),
                            StructuredActionItem("Pick up replacement gaskets", null)
                        )
                    )
                    StructuredBucketAssignment::class -> StructuredBucketAssignment(
                        bucketKind = "TASK",
                        tags = listOf("plumbing", "home-repair")
                    )
                    else -> null
                }
            }
        )

        val fallbackSummarizer = FakeSummarizer()
        val fallbackExtractor = FakeActionExtractor()
        val fallbackBucketer = FakeBucketer()

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript(transcriptText, Tier.FULL)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = NanoSummarizer(promptClient, fallbackSummarizer),
            extractor = NanoActionExtractor(promptClient, fallbackExtractor, fixedClock),
            bucketer = NanoBucketer(promptClient, fallbackBucketer),
            fallbackSummarizer = fallbackSummarizer,
            fallbackExtractor = fallbackExtractor,
            fallbackBucketer = fallbackBucketer,
            dao = dao
        )

        assertEquals(1, promptClient.openedSessions.size)
        val session = promptClient.openedSessions.single()
        assertEquals(1, session.warmupCalls)
        assertEquals(3, session.generateTypedCalls)
        assertEquals(0, session.generateTextCalls)
        assertEquals(1, session.clearImplicitCachesCalls)
        assertEquals(1, session.closeCalls)
        assertEquals(0, fallbackSummarizer.calls)
        assertEquals(0, fallbackExtractor.calls)
        assertEquals(0, fallbackBucketer.calls)

        val expectedPrefix = buildTranscriptPrefix(transcriptText)
        assertEquals(3, session.typedRequests.size)
        session.typedRequests.forEach { req ->
            assertEquals(NOTE_ANALYSIS_SYSTEM_INSTRUCTION, req.systemInstruction)
            assertEquals(expectedPrefix, req.promptPrefix)
        }

        assertEquals("Plumbing & Parts Run", outcome.updatedNote.title)
        assertEquals("TASK", outcome.updatedNote.bucketKind)
        assertEquals(listOf("home-repair", "plumbing"), outcome.tags)
        assertEquals(2, outcome.extractedActions.size)
        assertNotNull(outcome.extractedActions[0].dueAtEpochMs)
        assertEquals("FULL", outcome.updatedNote.tierUsed)
        assertEquals(NoteStatus.SORTED.name, outcome.updatedNote.status)
    }

    @Test
    fun nanoPipeline_stageStructuredFailure_fallsBackToRegexWithinSharedSession() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val zone = ZoneId.of("UTC")
        val fixedClock = Clock.fixed(
            LocalDateTime.of(2026, 11, 2, 10, 0, 0).atZone(zone).toInstant(),
            zone
        )

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedResponder = { _, outputClass ->
                when (outputClass) {
                    StructuredTitleSummary::class -> StructuredTitleSummary(
                        title = "Hardware Errand",
                        summary = "Call plumber and buy gaskets."
                    )
                    StructuredActionExtraction::class -> null // Simulate non-busy structured decode failure
                    StructuredBucketAssignment::class -> StructuredBucketAssignment(
                        bucketKind = "TASK",
                        tags = listOf("plumbing")
                    )
                    else -> null
                }
            },
            responder = { prompt ->
                if (prompt.startsWith("Extract concrete action items")) {
                    "ACTION: Call the plumber | DUE: tomorrow at 2pm"
                } else {
                    ""
                }
            }
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(
                result = Transcript("Need to call the plumber tomorrow at 2pm.", Tier.FULL)
            ),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = NanoSummarizer(promptClient),
            extractor = NanoActionExtractor(promptClient, HeuristicActionExtractor(), fixedClock),
            bucketer = NanoBucketer(promptClient),
            dao = dao
        )

        assertEquals(1, promptClient.openedSessions.size)
        val session = promptClient.openedSessions.single()
        assertEquals(1, session.warmupCalls)
        assertEquals(3, session.generateTypedCalls)
        assertEquals(1, session.generateTextCalls)
        assertEquals(1, session.clearImplicitCachesCalls)
        assertEquals(1, session.closeCalls)
        assertEquals(listOf("Call the plumber"), outcome.extractedActions.map { it.text })
        assertEquals("FULL", outcome.updatedNote.tierUsed)
        assertEquals(NoteStatus.SORTED.name, outcome.updatedNote.status)
    }

    @Test
    fun nanoPipeline_clearsCachesAndClosesSessionOnCancellation() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val cancelingClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedException = CancellationException("Worker cancelled mid-pipeline")
        )

        try {
            SortingWorker.executePipeline(
                note = dao.byId(noteId)!!,
                capabilityProbe = probe,
                transcriber = FakeTranscriber(result = Transcript("Call Sam tomorrow.", Tier.FULL)),
                platformTranscriber = FakeTranscriber(),
                nullTranscriber = NullTranscriber,
                summarizer = NanoSummarizer(cancelingClient),
                extractor = NanoActionExtractor(cancelingClient),
                bucketer = NanoBucketer(cancelingClient),
                dao = dao
            )
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // Expected
        }

        assertEquals(1, cancelingClient.openedSessions.size)
        val session = cancelingClient.openedSessions.single()
        assertEquals(1, session.warmupCalls)
        assertEquals(1, session.clearImplicitCachesCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun proofreader_preservesRawNoteTranscriptImmutably_whileStages2To4ConsumeWorkingTranscript() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val rawTranscript = "  remind me   to call dr chen tomorrow at 3pm about invoice 4812  "
        val proofreadTranscript = "Remind me to call Dr. Chen tomorrow at 3:00 PM about invoice 4812."

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            responder = { prompt ->
                when {
                    prompt.startsWith("Proofread this raw voice note transcript") ->
                        "PROOFREAD: $proofreadTranscript"
                    else -> ""
                }
            }
        )

        val summarizer = FakeSummarizer(result = TitleSummary("Call Dr. Chen", "Follow up on invoice 4812."))
        val extractor = FakeActionExtractor(items = listOf(ExtractedAction("Call Dr. Chen tomorrow at 3:00 PM")))
        val bucketer = FakeBucketer(assignment = BucketAssignment("TASK", listOf("invoice"), Tier.FULL))

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript(rawTranscript, Tier.FULL)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = summarizer,
            extractor = extractor,
            bucketer = bucketer,
            dao = dao,
            proofreader = NanoProofreader(promptClient)
        )

        // Hard invariant: persisted Note.transcript remains verbatim raw Stage 1 output
        assertEquals(rawTranscript, outcome.updatedNote.transcript)
        assertEquals(rawTranscript, dao.byId(noteId)!!.transcript)
        assertTrue(dao.updateSnapshots.all { it.transcript == rawTranscript })

        // Downstream Stages 2-4 receive the separate proofread workingTranscript
        assertEquals(proofreadTranscript, outcome.workingTranscript)
        assertEquals(proofreadTranscript, summarizer.lastTranscript)
        assertEquals(proofreadTranscript, extractor.lastTranscript)
        assertEquals(proofreadTranscript, bucketer.lastTranscript)

        assertEquals(
            "transcribe:OK,proofread:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
        assertEquals("FULL", outcome.updatedNote.tierUsed)
        assertEquals(NoteStatus.SORTED.name, outcome.updatedNote.status)
    }

    @Test
    fun proofreader_tier1And2ProbeUnavailable_usesDeterministicCleanerWithoutCallingPromptModel() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val rawTranscript = "  buy   oat milk and call sarah  "
        val expectedWorking = "Buy oat milk and call sarah."

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = "PROOFREAD: Should not be invoked."
        )
        val primarySummarizer = FakeSummarizer()
        val primaryExtractor = FakeActionExtractor()
        val primaryBucketer = FakeBucketer()
        val fallbackSummarizer = FakeSummarizer(result = TitleSummary("Oat milk", "Buy oat milk and call Sarah."))
        val fallbackExtractor = FakeActionExtractor(items = listOf(ExtractedAction("Buy oat milk")))
        val fallbackBucketer = FakeBucketer(assignment = BucketAssignment("TASK", listOf("groceries"), Tier.CORE))

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript(rawTranscript, Tier.FULL)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = primarySummarizer,
            extractor = primaryExtractor,
            bucketer = primaryBucketer,
            fallbackSummarizer = fallbackSummarizer,
            fallbackExtractor = fallbackExtractor,
            fallbackBucketer = fallbackBucketer,
            dao = dao,
            proofreader = NanoProofreader(promptClient, DeterministicTranscriptCleaner())
        )

        assertEquals(0, promptClient.generateCalls)
        assertEquals(0, primarySummarizer.calls)
        assertEquals(0, primaryExtractor.calls)
        assertEquals(0, primaryBucketer.calls)
        assertEquals(rawTranscript, outcome.updatedNote.transcript)
        assertEquals(expectedWorking, outcome.workingTranscript)
        assertEquals(expectedWorking, fallbackSummarizer.lastTranscript)
        assertEquals(expectedWorking, fallbackExtractor.lastTranscript)
        assertEquals(expectedWorking, fallbackBucketer.lastTranscript)
        assertEquals("CORE", outcome.updatedNote.tierUsed)
        assertEquals(
            "transcribe:OK,proofread:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
    }

    @Test
    fun proofreader_busyDemotion_fallsBackToDeterministicCleaner_recordsBusyAndSetsNeedsReview() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val rawTranscript = "  call sam tomorrow at 2pm  "
        val expectedFallback = "Call sam tomorrow at 2pm."

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE,
                proofreader = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val busyClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            generateException = CapabilityBusyException()
        )
        val fallbackSummarizer = FakeSummarizer(result = TitleSummary("Call Sam", "Call Sam tomorrow at 2pm."))
        val fallbackExtractor = FakeActionExtractor(items = listOf(ExtractedAction("Call Sam tomorrow at 2pm")))
        val fallbackBucketer = FakeBucketer(assignment = BucketAssignment("TASK", listOf("call"), Tier.CORE))

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript(rawTranscript, Tier.FULL)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer(),
            fallbackSummarizer = fallbackSummarizer,
            fallbackExtractor = fallbackExtractor,
            fallbackBucketer = fallbackBucketer,
            dao = dao,
            proofreader = NanoProofreader(busyClient),
            fallbackProofreader = DeterministicTranscriptCleaner()
        )

        assertEquals(1, busyClient.generateCalls)
        assertEquals(rawTranscript, outcome.updatedNote.transcript)
        assertEquals(expectedFallback, outcome.workingTranscript)
        assertEquals(expectedFallback, fallbackSummarizer.lastTranscript)
        assertEquals(expectedFallback, fallbackExtractor.lastTranscript)
        assertEquals(expectedFallback, fallbackBucketer.lastTranscript)
        assertEquals(
            "transcribe:OK,proofread:BUSY,proofread_fallback:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
        assertEquals(NoteStatus.NEEDS_REVIEW.name, outcome.updatedNote.status)
        assertEquals("CORE", outcome.updatedNote.tierUsed)

        // Also verify BUSY status check (without generateText call) demotes cleanly
        val busyStatusClient = FakeGenAiPromptClient(currentStatus = CapabilityStatus.BUSY)
        val outcome2 = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript(rawTranscript, Tier.FULL)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = FakeSummarizer(),
            extractor = FakeActionExtractor(),
            bucketer = FakeBucketer(),
            fallbackSummarizer = fallbackSummarizer,
            fallbackExtractor = fallbackExtractor,
            fallbackBucketer = fallbackBucketer,
            dao = dao,
            proofreader = NanoProofreader(busyStatusClient),
            fallbackProofreader = DeterministicTranscriptCleaner()
        )
        assertEquals(0, busyStatusClient.generateCalls)
        assertEquals(rawTranscript, outcome2.updatedNote.transcript)
        assertEquals(expectedFallback, outcome2.workingTranscript)
        assertEquals(
            "transcribe:OK,proofread:BUSY,proofread_fallback:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome2.updatedNote.stageStatus
        )
        assertEquals(NoteStatus.NEEDS_REVIEW.name, outcome2.updatedNote.status)
        assertEquals("CORE", outcome2.updatedNote.tierUsed)
    }

    @Test
    fun proofreader_unexpectedException_recordsFailed_preservesRawTranscriptAndContinuesPipeline() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val rawTranscript = "remind me to email Alex"
        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )
        val summarizer = FakeSummarizer(result = TitleSummary("Email Alex", "Email Alex."))
        val extractor = FakeActionExtractor(items = listOf(ExtractedAction("Email Alex")))
        val bucketer = FakeBucketer(assignment = BucketAssignment("TASK", listOf("email"), Tier.CORE))

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(result = Transcript(rawTranscript, Tier.CORE)),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = summarizer,
            extractor = extractor,
            bucketer = bucketer,
            dao = dao,
            proofreader = FakeProofreader(exception = IllegalStateException("Proofreader failed"))
        )

        assertEquals(rawTranscript, outcome.updatedNote.transcript)
        assertEquals(rawTranscript, outcome.workingTranscript)
        assertEquals(rawTranscript, summarizer.lastTranscript)
        assertEquals(rawTranscript, extractor.lastTranscript)
        assertEquals(rawTranscript, bucketer.lastTranscript)
        assertEquals(
            "transcribe:OK,proofread:FAILED,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
        assertEquals(NoteStatus.NEEDS_REVIEW.name, outcome.updatedNote.status)
    }

    @Test
    fun runningPipelineTwiceWithProofreader_isIdempotent_andNeverDoubleProofreadsRawTranscript() = runTest {
        val dao = InMemoryFakeNoteDao()
        val noteId = dao.insert(sampleNote())

        val rawTranscript = "  remind me to buy milk.  dont forget to call sam  "
        val proofreadTranscript = "Remind me to buy milk. Don't forget to call Sam."

        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val promptInputs = mutableListOf<String>()
        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            responder = { prompt ->
                promptInputs += prompt
                "PROOFREAD: $proofreadTranscript"
            }
        )
        val proofreader = NanoProofreader(promptClient)
        val transcriber = FakeTranscriber(result = Transcript(rawTranscript, Tier.FULL))
        val summarizer = FakeSummarizer(result = TitleSummary("Errands", "Buy milk and call Sam."))
        val extractor = FakeActionExtractor(
            items = listOf(
                ExtractedAction("Remind me to buy milk."),
                ExtractedAction("Don't forget to call Sam.")
            )
        )
        val bucketer = FakeBucketer(assignment = BucketAssignment("TASK", listOf("errands"), Tier.FULL))

        val outcome1 = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = transcriber,
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = summarizer,
            extractor = extractor,
            bucketer = bucketer,
            dao = dao,
            proofreader = proofreader
        )
        val noteAfterRun1 = dao.byId(noteId)!!
        val actionsAfterRun1 = dao.actionsFor(noteId).map { it.copy(id = 0L) }
        val tagsAfterRun1 = dao.tagsFor(noteId)

        val outcome2 = SortingWorker.executePipeline(
            note = noteAfterRun1,
            capabilityProbe = probe,
            transcriber = transcriber,
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = summarizer,
            extractor = extractor,
            bucketer = bucketer,
            dao = dao,
            proofreader = proofreader
        )
        val noteAfterRun2 = dao.byId(noteId)!!
        val actionsAfterRun2 = dao.actionsFor(noteId).map { it.copy(id = 0L) }
        val tagsAfterRun2 = dao.tagsFor(noteId)

        assertEquals(rawTranscript, noteAfterRun1.transcript)
        assertEquals(rawTranscript, noteAfterRun2.transcript)
        assertEquals(proofreadTranscript, outcome1.workingTranscript)
        assertEquals(proofreadTranscript, outcome2.workingTranscript)
        assertEquals(noteAfterRun1, noteAfterRun2)
        assertEquals(actionsAfterRun1, actionsAfterRun2)
        assertEquals(tagsAfterRun1, tagsAfterRun2)
        assertEquals(2, promptInputs.size)
        assertEquals(promptInputs[0], promptInputs[1])
        assertEquals(
            "transcribe:OK,proofread:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            noteAfterRun2.stageStatus
        )
    }

    @Test
    fun imageNote_tier3Available_branchesToImageTranscriberAndFilesThroughStages2To4() = runTest {
        val dao = InMemoryFakeNoteDao()
        val imageFile = tempFolder.newFile("notebook_page.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val noteId = dao.insert(
            Note(
                createdAtEpochMs = 1_700_000_000_000L,
                sourceType = NoteSourceType.IMAGE.name,
                imagePath = imageFile.absolutePath
            )
        )

        val audioTranscriber = FakeTranscriber(result = Transcript("audio should not run", Tier.FULL))
        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            imageResponse = "Remind me to buy oat milk and schedule dentist tomorrow.",
            responder = { prompt ->
                when {
                    prompt.startsWith("Summarize") ->
                        "Title: Notebook Errands\nSummary: Buy oat milk and schedule dentist tomorrow."
                    prompt.startsWith("Extract concrete action items") ->
                        "ACTION: Buy oat milk | DUE: NONE\nACTION: Schedule dentist | DUE: tomorrow"
                    prompt.startsWith("Classify") ->
                        "BUCKET: TASK\nTAGS: errands, notebook"
                    else -> ""
                }
            }
        )
        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.UNAVAILABLE,
                imageTranscriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.AVAILABLE,
                actions = CapabilityStatus.AVAILABLE
            ),
            dataStore
        )

        val ocrFallback = MlKitOcrImageTranscriber(FakeBundledOcrEngine(available = true))
        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = audioTranscriber,
            platformTranscriber = audioTranscriber,
            nullTranscriber = NullTranscriber,
            summarizer = NanoSummarizer(promptClient),
            extractor = NanoActionExtractor(promptClient),
            bucketer = NanoBucketer(promptClient),
            dao = dao,
            imageTranscriber = NanoImageTranscriber(promptClient, ocrFallback),
            fallbackImageTranscriber = ocrFallback,
            nullImageTranscriber = NullImageTranscriber
        )

        assertEquals(0, audioTranscriber.calls)
        assertEquals(1, promptClient.imageGenerateCalls)
        assertEquals(imageFile.absolutePath, outcome.updatedNote.imagePath)
        assertEquals("Remind me to buy oat milk and schedule dentist tomorrow.", outcome.updatedNote.transcript)
        assertEquals("Notebook Errands", outcome.updatedNote.title)
        assertEquals("TASK", outcome.updatedNote.bucketKind)
        assertEquals("FULL", outcome.updatedNote.tierUsed)
        assertEquals(NoteStatus.SORTED.name, outcome.updatedNote.status)
        assertEquals(2, outcome.extractedActions.size)
        assertEquals(listOf("errands", "notebook"), outcome.tags)
    }

    @Test
    fun imageNote_busyDemotesToTier2BundledOcr_andMarksNeedsReview() = runTest {
        val dao = InMemoryFakeNoteDao()
        val imageFile = tempFolder.newFile("whiteboard.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val noteId = dao.insert(
            Note(
                createdAtEpochMs = 1_700_000_000_000L,
                sourceType = NoteSourceType.IMAGE.name,
                imagePath = imageFile.absolutePath
            )
        )

        val busyClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            imageGenerateException = CapabilityBusyException()
        )
        val ocrEngine = FakeBundledOcrEngine(available = true) {
            "Shopping list: sourdough bread and dark roast coffee."
        }
        val ocrTranscriber = MlKitOcrImageTranscriber(ocrEngine)
        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.UNAVAILABLE,
                imageTranscriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        val outcome = SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao,
            imageTranscriber = NanoImageTranscriber(busyClient, ocrTranscriber),
            fallbackImageTranscriber = ocrTranscriber,
            nullImageTranscriber = NullImageTranscriber
        )

        assertEquals(1, busyClient.imageGenerateCalls)
        assertEquals(listOf(imageFile.absolutePath), ocrEngine.recognizedFiles)
        assertEquals("Shopping list: sourdough bread and dark roast coffee.", outcome.updatedNote.transcript)
        assertEquals("LIST", outcome.updatedNote.bucketKind)
        assertEquals("CORE", outcome.updatedNote.tierUsed)
        assertEquals(NoteStatus.NEEDS_REVIEW.name, outcome.updatedNote.status)
        assertEquals(
            "transcribe:BUSY,transcribe_fallback:OK,summarize:OK,actions:OK,bucket:OK,embed:SKIPPED",
            outcome.updatedNote.stageStatus
        )
    }

    @Test
    fun imageNote_tier1NullOrBlankOutput_retainsImageAttachmentAndMarksNeedsReview() = runTest {
        val dao = InMemoryFakeNoteDao()
        val imageFile = tempFolder.newFile("sketch.jpg").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val note1Id = dao.insert(
            Note(
                createdAtEpochMs = 1_700_000_000_000L,
                sourceType = NoteSourceType.IMAGE.name,
                imagePath = imageFile.absolutePath
            )
        )

        // Case 1: CapabilityProbe reports UNAVAILABLE -> NullImageTranscriber
        val unavailableProbe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.UNAVAILABLE,
                imageTranscriber = CapabilityStatus.UNAVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        val outcome1 = SortingWorker.executePipeline(
            note = dao.byId(note1Id)!!,
            capabilityProbe = unavailableProbe,
            transcriber = FakeTranscriber(),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao,
            imageTranscriber = NullImageTranscriber,
            fallbackImageTranscriber = NullImageTranscriber,
            nullImageTranscriber = NullImageTranscriber
        )

        assertEquals(imageFile.absolutePath, outcome1.updatedNote.imagePath)
        assertEquals("", outcome1.updatedNote.transcript)
        assertEquals("MINIMAL", outcome1.updatedNote.tierUsed)
        assertEquals(NoteStatus.NEEDS_REVIEW.name, outcome1.updatedNote.status)
        assertEquals("transcribe_null:OK", outcome1.updatedNote.stageStatus)

        // Case 2: Tier 2 OCR runs on an unreadable/blank drawing -> blank text -> retains image & NEEDS_REVIEW
        val note2Id = dao.insert(
            Note(
                createdAtEpochMs = 1_700_000_001_000L,
                sourceType = NoteSourceType.IMAGE.name,
                imagePath = imageFile.absolutePath
            )
        )
        val blankOcr = MlKitOcrImageTranscriber(FakeBundledOcrEngine(available = true) { "   " })
        val availableProbe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.UNAVAILABLE,
                imageTranscriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        val outcome2 = SortingWorker.executePipeline(
            note = dao.byId(note2Id)!!,
            capabilityProbe = availableProbe,
            transcriber = FakeTranscriber(),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao,
            imageTranscriber = NanoImageTranscriber(FakeGenAiPromptClient(CapabilityStatus.UNAVAILABLE), blankOcr),
            fallbackImageTranscriber = blankOcr,
            nullImageTranscriber = NullImageTranscriber
        )

        assertEquals(imageFile.absolutePath, outcome2.updatedNote.imagePath)
        assertEquals("", outcome2.updatedNote.transcript)
        assertEquals("MINIMAL", outcome2.updatedNote.tierUsed)
        assertEquals(NoteStatus.NEEDS_REVIEW.name, outcome2.updatedNote.status)
    }

    @Test
    fun imageNote_runningPipelineTwice_isIdempotent() = runTest {
        val dao = InMemoryFakeNoteDao()
        val imageFile = tempFolder.newFile("idempotent_board.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val noteId = dao.insert(
            Note(
                createdAtEpochMs = 1_700_000_000_000L,
                sourceType = NoteSourceType.IMAGE.name,
                imagePath = imageFile.absolutePath
            )
        )

        val ocrEngine = FakeBundledOcrEngine(available = true) {
            "Remind me to buy copper wire. Call the electrician tomorrow."
        }
        val ocrTranscriber = MlKitOcrImageTranscriber(ocrEngine)
        val probe = CapabilityProbe(
            FakeCapabilityChecker(
                transcriber = CapabilityStatus.UNAVAILABLE,
                imageTranscriber = CapabilityStatus.AVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore
        )

        SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao,
            imageTranscriber = NanoImageTranscriber(FakeGenAiPromptClient(CapabilityStatus.UNAVAILABLE), ocrTranscriber),
            fallbackImageTranscriber = ocrTranscriber,
            nullImageTranscriber = NullImageTranscriber
        )

        val noteRun1 = dao.byId(noteId)!!
        val actionsRun1 = dao.actionsFor(noteId).map { it.copy(id = 0L) }
        val tagsRun1 = dao.tagsFor(noteId)

        SortingWorker.executePipeline(
            note = noteRun1,
            capabilityProbe = probe,
            transcriber = FakeTranscriber(),
            platformTranscriber = FakeTranscriber(),
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao,
            imageTranscriber = NanoImageTranscriber(FakeGenAiPromptClient(CapabilityStatus.UNAVAILABLE), ocrTranscriber),
            fallbackImageTranscriber = ocrTranscriber,
            nullImageTranscriber = NullImageTranscriber
        )

        assertEquals(noteRun1, dao.byId(noteId)!!)
        assertEquals(actionsRun1, dao.actionsFor(noteId).map { it.copy(id = 0L) })
        assertEquals(tagsRun1, dao.tagsFor(noteId))
        assertTrue(actionsRun1.isNotEmpty())
    }
}
