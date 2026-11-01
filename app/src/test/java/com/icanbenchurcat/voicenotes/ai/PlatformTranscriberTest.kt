// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.service.SortingWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fake [PlatformFileSpeechEngine] that reads the actual bytes of [audioFile]
 * on disk and verifies dispatcher confinement.
 */
class FileReadingFakePlatformSpeechEngine(
    private val available: Boolean = true,
    private val expectedMainDispatcher: CoroutineDispatcher? = null,
    private val throwBusy: Boolean = false
) : PlatformFileSpeechEngine {

    val dispatchedOnExpectedContext = AtomicBoolean(false)
    var transcribedFiles = mutableListOf<String>()

    override fun isFileTranscriptionAvailable(): Boolean = available

    override suspend fun transcribeFile(audioFile: File): String {
        val runBlock: suspend () -> String = {
            if (expectedMainDispatcher != null) {
                dispatchedOnExpectedContext.set(true)
            }
            if (throwBusy) throw CapabilityBusyException()
            transcribedFiles += audioFile.absolutePath
            val content = audioFile.readText(Charsets.UTF_8).trim()
            "Transcribed from file: $content"
        }
        return if (expectedMainDispatcher != null) {
            withContext(expectedMainDispatcher) { runBlock() }
        } else {
            runBlock()
        }
    }
}

class PlatformTranscriberTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStoreScope: CoroutineScope

    @Before
    fun setUp() {
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun transcribe_readsFileAtAudioPath_twoDifferentFilesReturnTwoDifferentTranscripts() = runTest {
        val fileA = tempFolder.newFile("note_alpha.pcm").apply {
            writeText("Buy oat milk and espresso beans tomorrow morning.")
        }
        val fileB = tempFolder.newFile("note_beta.pcm").apply {
            writeText("What if we build a local-only voice note sorter?")
        }

        val engine = FileReadingFakePlatformSpeechEngine(available = true)
        val transcriber = PlatformTranscriber(engine)

        assertEquals(CapabilityStatus.AVAILABLE, transcriber.status())

        val transcriptA = transcriber.transcribe(fileA.absolutePath)
        val transcriptB = transcriber.transcribe(fileB.absolutePath)

        assertNotEquals(transcriptA.text, transcriptB.text)
        assertEquals(
            "Transcribed from file: Buy oat milk and espresso beans tomorrow morning.",
            transcriptA.text
        )
        assertEquals(
            "Transcribed from file: What if we build a local-only voice note sorter?",
            transcriptB.text
        )
        assertEquals(Tier.CORE, transcriptA.tier)
        assertEquals(Tier.CORE, transcriptB.tier)
        assertEquals(listOf(fileA.absolutePath, fileB.absolutePath), engine.transcribedFiles)
    }

    @Test
    fun transcribe_missingOrEmptyFile_throwsIllegalArgumentException() = runTest {
        val engine = FileReadingFakePlatformSpeechEngine(available = true)
        val transcriber = PlatformTranscriber(engine)

        val missingPath = File(tempFolder.root, "does_not_exist.m4a").absolutePath
        try {
            transcriber.transcribe(missingPath)
            fail("Expected IllegalArgumentException for non-existent audio file")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        val emptyFile = tempFolder.newFile("empty.m4a")
        try {
            transcriber.transcribe(emptyFile.absolutePath)
            fail("Expected IllegalArgumentException for empty audio file")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun noOnDeviceEngineCanTranscribeFiles_marksNoteTier1MinimalAndPreservesAudioPath() = runTest {
        val recordedAudio = tempFolder.newFile("recorded_note.m4a").apply {
            writeBytes(ByteArray(256) { 0x42 })
        }

        val unavailableEngine = FileReadingFakePlatformSpeechEngine(available = false)
        val platformTranscriber = PlatformTranscriber(unavailableEngine)
        val nanoWithNoEngines = NanoTranscriber(
            genAiClient = FakeGenAiSpeechClient(
                advancedStatus = CapabilityStatus.UNAVAILABLE,
                basicStatus = CapabilityStatus.UNAVAILABLE
            ),
            fallbackTranscriber = platformTranscriber
        )

        assertEquals(CapabilityStatus.UNAVAILABLE, platformTranscriber.status())
        assertEquals(CapabilityStatus.UNAVAILABLE, nanoWithNoEngines.status())

        // Direct call on NanoTranscriber or PlatformTranscriber falls back to Tier 1 without live listening
        val directResult = nanoWithNoEngines.transcribe(recordedAudio.absolutePath)
        assertEquals("", directResult.text)
        assertEquals(Tier.MINIMAL, directResult.tier)
        assertTrue(unavailableEngine.transcribedFiles.isEmpty())

        // Full SortingWorker pipeline marks the note MINIMAL and keeps audioPath intact for playback
        val dsFile = tempFolder.newFile("no_engine_caps.preferences_pb").also { it.delete() }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dsFile }
        )
        val probe = CapabilityProbe(
            checker = FakeCapabilityChecker(
                transcriber = CapabilityStatus.UNAVAILABLE,
                summarizer = CapabilityStatus.UNAVAILABLE,
                actions = CapabilityStatus.UNAVAILABLE
            ),
            dataStore = dataStore
        )
        val initialNote = Note(
            id = 42L,
            createdAtEpochMs = 1_700_000_000_000L,
            audioPath = recordedAudio.absolutePath
        )

        val outcome = SortingWorker.executePipeline(
            note = initialNote,
            capabilityProbe = probe,
            transcriber = nanoWithNoEngines,
            platformTranscriber = platformTranscriber,
            nullTranscriber = NullTranscriber,
            summarizer = ExtractiveSummarizer(),
            extractor = HeuristicActionExtractor(),
            bucketer = HeuristicBucketer()
        )

        assertEquals(recordedAudio.absolutePath, outcome.updatedNote.audioPath)
        assertTrue(File(outcome.updatedNote.audioPath).exists())
        assertEquals("", outcome.updatedNote.transcript)
        assertEquals("MINIMAL", outcome.updatedNote.tierUsed)
        assertEquals("transcribe_null:OK", outcome.updatedNote.stageStatus)
    }

    @Test
    fun callableFromBackgroundWorker_switchesToMainDispatcherForRecognition() = runTest {
        val mainTestDispatcher = StandardTestDispatcher(testScheduler)
        val audioFile = tempFolder.newFile("worker_note.pcm").apply {
            writeText("Remind me to call Alex at 5pm.")
        }
        val engine = FileReadingFakePlatformSpeechEngine(
            available = true,
            expectedMainDispatcher = mainTestDispatcher
        )
        val platformTranscriber = PlatformTranscriber(engine)

        // Invoke from Dispatchers.Default (simulating WorkManager CoroutineWorker background thread)
        val transcript = withContext(Dispatchers.Default) {
            platformTranscriber.transcribe(audioFile.absolutePath)
        }

        assertTrue(engine.dispatchedOnExpectedContext.get())
        assertEquals("Transcribed from file: Remind me to call Alex at 5pm.", transcript.text)
        assertEquals(Tier.CORE, transcript.tier)
    }
}
