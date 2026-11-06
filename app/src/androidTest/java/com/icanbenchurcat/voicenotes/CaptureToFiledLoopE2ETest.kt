// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.icanbenchurcat.voicenotes.ai.CapabilityChecker
import com.icanbenchurcat.voicenotes.ai.CapabilityProbe
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.Transcript
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.capture.RecordingService
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.service.SortingWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end instrumented test for the MVP capture-to-filed loop (Issue #40).
 *
 * Drives the real UI and service pipeline:
 * 1. Injects a deterministic fake [Transcriber] and fixture audio bytes so the
 *    test requires no live microphone, no network, and no AICore/Nano model in CI.
 * 2. Taps the Record FAB -> verifies active recording session -> taps Stop.
 * 3. Waits for the enqueued [SortingWorker] to complete all pipeline stages.
 * 4. Asserts the note appears in the UI list with its generated title, TASK bucket,
 *    and opens the detail view to verify persisted tags and extracted action items.
 * 5. Re-runs [SortingWorker] on the same note and verifies idempotency (no duplicate
 *    action items or tags).
 */
@RunWith(AndroidJUnit4::class)
class CaptureToFiledLoopE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val fakeTranscriptText =
        "Remind me to call the electrician tomorrow. Also buy copper wire for the kitchen."

    private val fakeTranscriber = object : Transcriber {
        override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE

        override suspend fun transcribe(audioPath: String): Transcript {
            val file = File(audioPath)
            require(file.exists() && file.length() > 0L) {
                "Expected recorded fixture audio file at $audioPath"
            }
            return Transcript(fakeTranscriptText, Tier.CORE)
        }
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        AppDatabase.get(context).clearAllTables()

        // Inject deterministic fixture audio bytes and fake transcriber + capability probe
        RecordingService.fixtureAudioBytesOverride = ByteArray(1024) { 0x42 }
        SortingWorker.transcriberOverride = fakeTranscriber
        SortingWorker.capabilityProbeOverride = CapabilityProbe(
            checker = object : CapabilityChecker {
                override suspend fun checkTranscriber(): CapabilityStatus = CapabilityStatus.AVAILABLE
                override suspend fun checkSummarizer(): CapabilityStatus = CapabilityStatus.UNAVAILABLE
                override suspend fun checkActionExtractor(): CapabilityStatus = CapabilityStatus.UNAVAILABLE
                override fun versionFingerprint(): String = "e2e-test-fingerprint"
            },
            dataStore = PreferenceDataStoreFactory.create(
                produceFile = {
                    context.preferencesDataStoreFile("e2e_probe_${System.nanoTime()}")
                }
            )
        )
    }

    @After
    fun tearDown() {
        RecordingService.fixtureAudioBytesOverride = null
        SortingWorker.transcriberOverride = null
        SortingWorker.capabilityProbeOverride = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.get(context).clearAllTables()
    }

    @Test
    fun recordStopSortAndFileLoop_persistsTitleBucketTagsActions_andIsIdempotentOnRerun() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.get(context).noteDao()

        // 1. Start recording via the Record FAB
        composeRule.onNodeWithContentDescription("Record a note").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Record a note").performClick()

        composeRule.waitUntil(timeoutMillis = 7_000) {
            composeRule.onAllNodesWithText("Recording note")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Recording note").assertIsDisplayed()

        // 2. Stop recording -> RecordingService persists Note and enqueues SortingWorker
        composeRule.onNodeWithContentDescription("Stop recording").performClick()

        // 3. Wait for SortingWorker to finish all 4 stages and file the note into TASK
        val expectedTitle = "Remind me to call the electrician tomorrow"
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Filed as TASK, CORE")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Assert note appears in the notes list with title and TASK · CORE metadata
        composeRule.onNodeWithText(expectedTitle).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Filed as TASK, CORE").assertIsDisplayed()

        // Wait until status reaches SORTED in Room
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                val notes = dao.observeAll().first()
                notes.size == 1 && notes.first().status == NoteStatus.SORTED.name
            }
        }

        // Open the note detail view to verify tags and action items in the UI
        composeRule.onNodeWithText(expectedTitle).performClick()

        val detailScope = hasAnyAncestor(hasTestTag("note_detail_content"))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("#buy") and detailScope)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNode(hasText("#buy") and detailScope).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasText("#call") and detailScope).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasText("#remind-me") and detailScope).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Remind me to call the electrician tomorrow.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Also buy copper wire for the kitchen.").performScrollTo().assertIsDisplayed()

        // Inspect persisted DB records
        var noteId = 0L
        val initialActions = runBlocking {
            val noteEntity = dao.observeAll().first().single()
            noteId = noteEntity.id
            assertEquals("TASK", noteEntity.bucketKind)
            assertEquals(NoteStatus.SORTED.name, noteEntity.status)
            assertEquals(
                listOf("buy", "call", "remind-me"),
                dao.tagsFor(noteId)
            )
            dao.actionsFor(noteId)
        }
        assertEquals(2, initialActions.size)
        val initialActionTexts = initialActions.map { it.text }

        // 5. Re-run the pipeline on the same note and assert idempotency (no duplicate action items)
        runBlocking {
            val noteBeforeRerun = dao.byId(noteId)!!
            SortingWorker.executePipeline(
                note = noteBeforeRerun,
                capabilityProbe = SortingWorker.capabilityProbeOverride!!,
                transcriber = fakeTranscriber,
                platformTranscriber = fakeTranscriber,
                nullTranscriber = fakeTranscriber,
                summarizer = com.icanbenchurcat.voicenotes.ai.ExtractiveSummarizer(),
                extractor = com.icanbenchurcat.voicenotes.ai.HeuristicActionExtractor(),
                bucketer = com.icanbenchurcat.voicenotes.ai.HeuristicBucketer(),
                dao = dao
            )
        }

        // Also enqueue a second WorkManager SortingWorker run to prove worker re-execution is idempotent
        val rerunWork = OneTimeWorkRequestBuilder<SortingWorker>()
            .setInputData(workDataOf(SortingWorker.KEY_NOTE_ID to noteId))
            .build()
        WorkManager.getInstance(context).enqueue(rerunWork)

        composeRule.waitUntil(timeoutMillis = 10_000) {
            val info = WorkManager.getInstance(context).getWorkInfoById(rerunWork.id).get()
            info != null && info.state.isFinished
        }

        val actionsAfterRerun = runBlocking { dao.actionsFor(noteId) }
        val tagsAfterRerun = runBlocking { dao.tagsFor(noteId) }
        assertEquals(
            "Re-running the pipeline must not duplicate action items",
            2,
            actionsAfterRerun.size
        )
        assertEquals(
            initialActionTexts,
            actionsAfterRerun.map { it.text }
        )
        assertEquals(
            listOf("buy", "call", "remind-me"),
            tagsAfterRerun
        )
    }
}
