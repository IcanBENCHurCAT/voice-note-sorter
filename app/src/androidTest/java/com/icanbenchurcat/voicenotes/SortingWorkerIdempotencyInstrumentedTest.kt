// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icanbenchurcat.voicenotes.ai.ActionExtractor
import com.icanbenchurcat.voicenotes.ai.BucketAssignment
import com.icanbenchurcat.voicenotes.ai.BucketProfile
import com.icanbenchurcat.voicenotes.ai.Bucketer
import com.icanbenchurcat.voicenotes.ai.CapabilityChecker
import com.icanbenchurcat.voicenotes.ai.CapabilityProbe
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.ExtractiveSummarizer
import com.icanbenchurcat.voicenotes.ai.ExtractedAction
import com.icanbenchurcat.voicenotes.ai.HeuristicActionExtractor
import com.icanbenchurcat.voicenotes.ai.HeuristicBucketer
import com.icanbenchurcat.voicenotes.ai.NullTranscriber
import com.icanbenchurcat.voicenotes.ai.Summarizer
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.TitleSummary
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.ai.Transcript
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.Tag
import com.icanbenchurcat.voicenotes.service.SortingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SortingWorkerIdempotencyInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dsScope: CoroutineScope
    private lateinit var dsFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dsScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dsFile = File(context.cacheDir, "idempotency_test_caps.preferences_pb").also { it.delete() }
    }

    @After
    fun tearDown() {
        db.close()
        dsScope.cancel()
        dsFile.delete()
    }

    @Test
    fun runningSortingWorkerTwiceAgainstInMemoryRoomDb_producesIdenticalNoteActionAndTagState() = runBlocking {
        val dao = db.noteDao()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dsScope,
            produceFile = { dsFile }
        )
        val probe = CapabilityProbe(
            checker = object : CapabilityChecker {
                override suspend fun checkTranscriber() = CapabilityStatus.AVAILABLE
                override suspend fun checkSummarizer() = CapabilityStatus.AVAILABLE
                override suspend fun checkActionExtractor() = CapabilityStatus.AVAILABLE
                override fun versionFingerprint() = "app=1|aicore=test"
            },
            dataStore = dataStore
        )

        val transcriber = object : Transcriber {
            override suspend fun status() = CapabilityStatus.AVAILABLE
            override suspend fun transcribe(audioPath: String) =
                Transcript("Remind me to renew the passport. Need to book flights.", Tier.CORE)
        }
        val summarizer = object : Summarizer {
            override suspend fun status() = CapabilityStatus.AVAILABLE
            override suspend fun summarize(transcript: String) =
                TitleSummary("Passport and flights", "Renew passport and book flights.")
        }
        val extractor = object : ActionExtractor {
            override suspend fun status() = CapabilityStatus.AVAILABLE
            override suspend fun extract(transcript: String) = listOf(
                ExtractedAction("Remind me to renew the passport."),
                ExtractedAction("Need to book flights.")
            )
        }
        val bucketer = object : Bucketer {
            override suspend fun assign(transcript: String, profile: BucketProfile) =
                BucketAssignment("TASK", listOf("travel", "documents"), Tier.CORE)
        }

        val noteId = dao.insert(
            Note(
                createdAtEpochMs = 1_700_000_000_000L,
                audioPath = "/data/local/tmp/travel_note.m4a"
            )
        )

        // First worker run
        SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = transcriber,
            platformTranscriber = transcriber,
            nullTranscriber = NullTranscriber,
            summarizer = summarizer,
            extractor = extractor,
            bucketer = bucketer,
            dao = dao
        )

        val noteRun1 = dao.byId(noteId)!!
        val actionsRun1 = dao.actionsFor(noteId).map { it.copy(id = 0L) }
        val tagsRun1 = dao.tagsFor(noteId)

        assertEquals(2, actionsRun1.size)
        assertEquals(listOf("documents", "travel"), tagsRun1)

        // Second worker run on the already-sorted note
        SortingWorker.executePipeline(
            note = noteRun1,
            capabilityProbe = probe,
            transcriber = transcriber,
            platformTranscriber = transcriber,
            nullTranscriber = NullTranscriber,
            summarizer = summarizer,
            extractor = extractor,
            bucketer = bucketer,
            dao = dao
        )

        val noteRun2 = dao.byId(noteId)!!
        val actionsRun2 = dao.actionsFor(noteId).map { it.copy(id = 0L) }
        val tagsRun2 = dao.tagsFor(noteId)

        assertEquals(noteRun1, noteRun2)
        assertEquals(actionsRun1, actionsRun2)
        assertEquals(tagsRun1, tagsRun2)
    }

    @Test
    fun sortingWorkerWithHeuristicBucketer_persistsTagsAndBucketRowInRoomDb() = runBlocking {
        val dao = db.noteDao()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dsScope,
            produceFile = { dsFile }
        )
        val probe = CapabilityProbe(
            checker = object : CapabilityChecker {
                override suspend fun checkTranscriber() = CapabilityStatus.AVAILABLE
                override suspend fun checkSummarizer() = CapabilityStatus.UNAVAILABLE
                override suspend fun checkActionExtractor() = CapabilityStatus.UNAVAILABLE
                override fun versionFingerprint() = "app=1|aicore=test"
            },
            dataStore = dataStore
        )

        val transcriber = object : Transcriber {
            override suspend fun status() = CapabilityStatus.AVAILABLE
            override suspend fun transcribe(audioPath: String) =
                Transcript("Shopping list for groceries and coffee.", Tier.CORE)
        }

        val noteId = dao.insert(
            Note(
                createdAtEpochMs = 1_700_000_000_000L,
                audioPath = "/data/local/tmp/shopping.m4a"
            )
        )

        SortingWorker.executePipeline(
            note = dao.byId(noteId)!!,
            capabilityProbe = probe,
            transcriber = transcriber,
            platformTranscriber = transcriber,
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer(),
            dao = dao
        )

        val sortedNote = dao.byId(noteId)!!
        assertEquals("LIST", sortedNote.bucketKind)

        // Assigned bucket exists as a Bucket row and default profile is seeded
        val listBucket = dao.bucketByKind("LIST")
        assertNotNull(listBucket)
        assertEquals("Lists", listBucket?.name)
        assertEquals(5, dao.allBuckets().size)

        // HeuristicBucketer derived tags ("groceries", "list", "shopping") are persisted in Room
        val noteTags = dao.tagsFor(noteId)
        assertEquals(listOf("groceries", "list", "shopping"), noteTags)
        assertEquals(
            listOf(Tag("groceries"), Tag("list"), Tag("shopping")),
            dao.allTags()
        )
        assertEquals(listOf(sortedNote), dao.observeByTag("groceries").first())
    }
}
