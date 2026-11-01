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
import com.icanbenchurcat.voicenotes.ai.ExtractedAction
import com.icanbenchurcat.voicenotes.ai.FakeCapabilityChecker
import com.icanbenchurcat.voicenotes.ai.NullTranscriber
import com.icanbenchurcat.voicenotes.ai.Summarizer
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.TitleSummary
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.ai.Transcript
import com.icanbenchurcat.voicenotes.data.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val result: TitleSummary = TitleSummary("title", "summary")
) : Summarizer {
    var calls = 0
        private set

    override suspend fun status(): CapabilityStatus = currentStatus
    override suspend fun summarize(transcript: String): TitleSummary {
        calls++
        return result
    }
}

class FakeActionExtractor(
    private val currentStatus: CapabilityStatus = CapabilityStatus.AVAILABLE,
    private val items: List<ExtractedAction> = emptyList()
) : ActionExtractor {
    var calls = 0
        private set

    override suspend fun status(): CapabilityStatus = currentStatus
    override suspend fun extract(transcript: String): List<ExtractedAction> {
        calls++
        return items
    }
}

class FakeBucketer : Bucketer {
    override suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment =
        BucketAssignment("INBOX", emptyList(), Tier.CORE)
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
}
