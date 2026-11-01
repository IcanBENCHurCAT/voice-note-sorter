package com.icanbenchurcat.voicenotes.service

import android.content.Context
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.icanbenchurcat.voicenotes.ai.*
import com.icanbenchurcat.voicenotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import org.junit.After
import org.junit.Before

// Fake Transcribers
class FakeTranscriber(
    private val result: Transcript? = null,
    private val exception: Exception? = null
) : Transcriber {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE
    override suspend fun transcribe(audioPath: String): Transcript {
        if (exception != null) throw exception
        return result ?: Transcript("fake transcript", Tier.CORE)
    }
}

class FakeSummarizer : Summarizer {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE
    override suspend fun summarize(transcript: String): TitleSummary = TitleSummary("title", "summary")
}

class FakeActionExtractor : ActionExtractor {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE
    override suspend fun extract(transcript: String): List<ExtractedAction> = emptyList()
}

class FakeBucketer : Bucketer {
    override suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment =
        BucketAssignment("INBOX", emptyList(), Tier.CORE)
}

@RunWith(RobolectricTestRunner::class)
class SortingWorkerTest {

    private lateinit var db: AppDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var context: Context

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = db.noteDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun busyThrowsDemotesToPlatform() = runBlocking {
        val noteId = noteDao.insert(Note(createdAtEpochMs = 0, audioPath = "path"))

        val primary = FakeTranscriber(exception = CapabilityBusyException())
        val platform = FakeTranscriber(result = Transcript("platform transcript", Tier.CORE))
        val nullTranscriber = FakeTranscriber(result = Transcript("null", Tier.MINIMAL))

        val worker = TestListenableWorkerBuilder<SortingWorker>(
            context,
            inputData = Data.Builder().putLong(SortingWorker.KEY_NOTE_ID, noteId).build()
        ).setWorkerFactory(object : androidx.work.WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                return SortingWorker(
                    appContext, workerParameters,
                    primary, platform, nullTranscriber,
                    FakeSummarizer(), FakeActionExtractor(), FakeBucketer()
                )
            }
        }).build() as SortingWorker

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)

        val updatedNote = noteDao.byId(noteId)!!
        assertTrue(updatedNote.stageStatus.contains("transcribe:BUSY"))
        assertTrue(updatedNote.stageStatus.contains("transcribe_fallback:OK"))
        assertEquals("platform transcript", updatedNote.transcript)
        assertEquals("CORE", updatedNote.tierUsed)
    }

    @Test
    fun allTranscribersUnavailableCompletesMinimal() = runBlocking {
        val noteId = noteDao.insert(Note(createdAtEpochMs = 0, audioPath = "path"))

        val primary = FakeTranscriber(exception = CapabilityBusyException())
        val platform = FakeTranscriber(exception = CapabilityBusyException())
        val nullTranscriber = FakeTranscriber(result = Transcript("", Tier.MINIMAL))

        val worker = TestListenableWorkerBuilder<SortingWorker>(
            context,
            inputData = Data.Builder().putLong(SortingWorker.KEY_NOTE_ID, noteId).build()
        ).setWorkerFactory(object : androidx.work.WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                return SortingWorker(
                    appContext, workerParameters,
                    primary, platform, nullTranscriber,
                    FakeSummarizer(), FakeActionExtractor(), FakeBucketer()
                )
            }
        }).build() as SortingWorker

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)

        val updatedNote = noteDao.byId(noteId)!!
        assertTrue(updatedNote.stageStatus.contains("transcribe:BUSY"))
        assertTrue(updatedNote.stageStatus.contains("transcribe_fallback:BUSY"))
        assertTrue(updatedNote.stageStatus.contains("transcribe_null:OK"))
        assertEquals("", updatedNote.transcript)
        assertEquals("MINIMAL", updatedNote.tierUsed)
    }
}
