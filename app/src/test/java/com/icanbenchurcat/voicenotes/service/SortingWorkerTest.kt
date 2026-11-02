// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.service

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
import com.icanbenchurcat.voicenotes.ai.ExtractiveSummarizer
import com.icanbenchurcat.voicenotes.ai.ExtractedAction
import com.icanbenchurcat.voicenotes.ai.FakeCapabilityChecker
import com.icanbenchurcat.voicenotes.ai.HeuristicActionExtractor
import com.icanbenchurcat.voicenotes.ai.HeuristicBucketer
import com.icanbenchurcat.voicenotes.ai.NullTranscriber
import com.icanbenchurcat.voicenotes.ai.Summarizer
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.TitleSummary
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.ai.Transcript
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteDao
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

class FakeTranscriber(
    private val result: Transcript? = null,
    private val exception: Exception? = null,
    private val currentStatus: CapabilityStatus = CapabilityStatus.AVAILABLE
) : Transcriber {
    var calls = 0
        private set

    override suspend fun status(): CapabilityStatus = currentStatus
    override suspend fun transcribe(audioPath: String): Transcript {
        calls++
        if (exception != null) throw exception
        return result ?: Transcript("fake transcript", Tier.CORE)
    }
}

class FakeSummarizer(
    private val currentStatus: CapabilityStatus = CapabilityStatus.AVAILABLE,
    private val result: TitleSummary = TitleSummary("title", "summary"),
    private val exception: Exception? = null
) : Summarizer {
    var calls = 0
        private set

    override suspend fun status(): CapabilityStatus = currentStatus
    override suspend fun summarize(transcript: String): TitleSummary {
        calls++
        if (exception != null) throw exception
        return result
    }
}

class FakeActionExtractor(
    private val currentStatus: CapabilityStatus = CapabilityStatus.AVAILABLE,
    private val items: List<ExtractedAction> = emptyList(),
    private val exception: Exception? = null
) : ActionExtractor {
    var calls = 0
        private set

    override suspend fun status(): CapabilityStatus = currentStatus
    override suspend fun extract(transcript: String): List<ExtractedAction> {
        calls++
        if (exception != null) throw exception
        return items
    }
}

class FakeBucketer(
    private val assignment: BucketAssignment = BucketAssignment("INBOX", emptyList(), Tier.CORE),
    private val exception: Exception? = null
) : Bucketer {
    override suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment {
        if (exception != null) throw exception
        return assignment
    }
}

/**
 * In-memory [NoteDao] fake that records every intermediate [Note] update
 * so tests can assert per-stage persistence and idempotency.
 */
class InMemoryFakeNoteDao : NoteDao {
    private var nextNoteId = 1L
    private var nextActionId = 1L
    val notes = mutableMapOf<Long, Note>()
    val updateSnapshots = mutableListOf<Note>()
    private val actions = mutableListOf<ActionItem>()
    private val buckets = mutableMapOf<String, Bucket>()
    private val tags = mutableSetOf<String>()
    private val noteTags = mutableSetOf<NoteTag>()

    override suspend fun insert(note: Note): Long {
        val id = if (note.id == 0L) nextNoteId++ else note.id
        notes[id] = note.copy(id = id)
        return id
    }

    override suspend fun update(note: Note) {
        notes[note.id] = note
        updateSnapshots += note
    }

    override fun observeAll(): Flow<List<Note>> = flowOf(notes.values.toList())
    override fun observeByBucket(kind: String): Flow<List<Note>> =
        flowOf(notes.values.filter { it.bucketKind == kind })

    override fun observeByStatus(status: String): Flow<List<Note>> =
        flowOf(notes.values.filter { it.status == status })

    override suspend fun confirmOrChangeBucket(noteId: Long, bucketKind: String, status: String) {
        val existing = notes[noteId] ?: return
        notes[noteId] = existing.copy(bucketKind = bucketKind, status = status)
    }

    override suspend fun byId(id: Long): Note? = notes[id]
    override fun search(query: String): Flow<List<Note>> = flowOf(emptyList())

    override suspend fun insertActions(items: List<ActionItem>) {
        items.forEach { item ->
            val id = if (item.id == 0L) nextActionId++ else item.id
            actions += item.copy(id = id)
        }
    }

    override suspend fun deleteActionsForNote(noteId: Long) {
        actions.removeAll { it.noteId == noteId }
    }

    override suspend fun actionsFor(noteId: Long): List<ActionItem> =
        actions.filter { it.noteId == noteId }.sortedBy { it.id }

    override suspend fun insertBuckets(buckets: List<Bucket>) {
        buckets.forEach { this.buckets[it.kind] = it }
    }

    override suspend fun allBuckets(): List<Bucket> =
        buckets.values.sortedBy { it.kind }

    override suspend fun bucketByKind(kind: String): Bucket? = buckets[kind]

    override suspend fun insertTags(tags: List<Tag>) {
        tags.forEach { this.tags += it.name }
    }

    override suspend fun allTags(): List<Tag> =
        tags.sorted().map { Tag(it) }

    override suspend fun insertNoteTags(noteTags: List<NoteTag>) {
        this.noteTags.addAll(noteTags)
    }

    override suspend fun deleteNoteTagsForNote(noteId: Long) {
        noteTags.removeAll { it.noteId == noteId }
    }

    override suspend fun tagsFor(noteId: Long): List<String> =
        noteTags.filter { it.noteId == noteId }.map { it.tagName }.sorted()

    override fun observeByTag(tagName: String): Flow<List<Note>> {
        val matchingIds = noteTags.filter { it.tagName == tagName }.map { it.noteId }.toSet()
        return flowOf(notes.values.filter { it.id in matchingIds })
    }
}

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
            "transcribe:BUSY,transcribe_fallback:OK,summarize:OK,actions:OK,bucket:OK",
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
            "transcribe_fallback:OK,summarize:OK,actions:OK,bucket:OK",
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
            "transcribe:OK,summarize:FAILED,actions:FAILED,bucket:OK",
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
            "transcribe:OK,summarize:OK,actions:OK,bucket:FAILED",
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
        // Verify each of the 4 stages wrote independently to the DAO during the first run
        assertEquals(4, dao.updateSnapshots.size)

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
    }
}
