// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.fixtures.FakeSummarizer
import com.icanbenchurcat.voicenotes.fixtures.FakeTranscriber
import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import com.icanbenchurcat.voicenotes.service.SortingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private class FakeGenAiSummarizerClient(
    var status: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    var result: TitleSummary = TitleSummary("", ""),
    var exception: Exception? = null
) : GenAiSummarizerClient {
    var generateCalls = 0
        private set

    override suspend fun checkStatus(): CapabilityStatus = status

    override suspend fun generateTitleAndSummary(transcript: String): TitleSummary {
        generateCalls++
        exception?.let { throw it }
        return result
    }
}

class HeuristicActionAndSummaryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Fixed reference time: Monday, 2026-11-02 10:00:00 UTC
    private val zone = ZoneId.of("UTC")
    private val fixedInstant = LocalDateTime.of(2026, 11, 2, 10, 0, 0).atZone(zone).toInstant()
    private val fixedClock = Clock.fixed(fixedInstant, zone)

    @Test
    fun actionExtractor_extractsImperativeOpenersAndCallEmailBuyPhrases() = runTest {
        val extractor = HeuristicActionExtractor(clock = fixedClock)
        val transcript = """
            The site walk went smoothly this morning.
            Call the structural engineer about the beam load.
            Please email the revised estimate to Dana.
            Buy M10 anchor bolts at the supply house.
            Pick up the permits from city hall.
            Schedule the concrete pour for Friday.
        """.trimIndent()

        val actions = extractor.extract(transcript)

        assertEquals(5, actions.size)
        assertTrue(actions.any { it.text.contains("Call the structural engineer") })
        assertTrue(actions.any { it.text.contains("email the revised estimate") })
        assertTrue(actions.any { it.text.contains("Buy M10 anchor bolts") })
        assertTrue(actions.any { it.text.contains("Pick up the permits") })
        assertTrue(actions.any { it.text.contains("Schedule the concrete pour") })
    }

    @Test
    fun actionExtractor_populatesDueAtEpochMsForDatePhrases() = runTest {
        val extractor = HeuristicActionExtractor(clock = fixedClock)
        val transcript = """
            Remind me to submit the inspection report tomorrow.
            Call the electrician on Thursday at 2pm.
            Don't forget to order the tiles next Monday.
            Send the invoice to accounting.
        """.trimIndent()

        val actions = extractor.extract(transcript)
        assertEquals(4, actions.size)

        // 1. "tomorrow" -> Tuesday 2026-11-03 09:00 UTC
        val tomorrowAction = actions.first { it.text.contains("tomorrow") }
        assertNotNull(tomorrowAction.dueAtEpochMs)
        val tomorrowDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(tomorrowAction.dueAtEpochMs!!),
            zone
        )
        assertEquals(LocalDateTime.of(2026, 11, 3, 9, 0), tomorrowDateTime)

        // 2. "on Thursday at 2pm" -> Thursday 2026-11-05 14:00 UTC
        val thursdayAction = actions.first { it.text.contains("on Thursday") }
        assertNotNull(thursdayAction.dueAtEpochMs)
        val thursdayDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(thursdayAction.dueAtEpochMs!!),
            zone
        )
        assertEquals(LocalDateTime.of(2026, 11, 5, 14, 0), thursdayDateTime)

        // 3. "next Monday" -> Monday 2026-11-09 09:00 UTC
        val nextMondayAction = actions.first { it.text.contains("next Monday") }
        assertNotNull(nextMondayAction.dueAtEpochMs)
        val nextMondayDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(nextMondayAction.dueAtEpochMs!!),
            zone
        )
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 0), nextMondayDateTime)

        // 4. No date phrase -> dueAtEpochMs is null
        val noDateAction = actions.first { it.text.contains("invoice") }
        assertNull(noDateAction.dueAtEpochMs)
    }

    @Test
    fun extractiveSummarizer_selectsHighestSignalSentencesInsteadOfAlwaysFirstTwo() = runTest {
        val summarizer = ExtractiveSummarizer()
        val transcript = """
            Testing one two three is this thing on.
            Um yeah just walking back to the car now.
            We need to remind the team to finalize the Q4 architecture budget before Friday's deadline.
            Also call the supplier tomorrow to confirm the transformer delivery schedule for 9am.
            Anyway it is pretty sunny outside today.
        """.trimIndent()

        val result = summarizer.summarize(transcript)

        // Neither the filler lead ("Testing one two three...") nor the second filler sentence
        // should be selected ahead of the two high-signal sentences.
        assertFalse(result.summary.contains("Testing one two three"))
        assertFalse(result.summary.contains("walking back to the car"))
        assertTrue(result.summary.contains("Q4 architecture budget"))
        assertTrue(result.summary.contains("confirm the transformer delivery schedule"))
    }

    @Test
    fun unpunctuatedDictation_isSegmentedBeforeSummarisingAndExtracting() = runTest {
        val summarizer = ExtractiveSummarizer()
        val extractor = HeuristicActionExtractor(clock = fixedClock)

        // Unpunctuated 60-second style speech-recognizer dictation with multiple clauses and actions
        val unpunctuated = "uh let me see walking out of the client meeting right now " +
            "the client approved phase two of the warehouse retrofit " +
            "remind me to call the electrical inspector tomorrow " +
            "also email the updated conduit layout to Sam on Thursday " +
            "and buy extra junction boxes at the supply house before noon " +
            "finally we should review the crane rental quote next week"

        val segments = TranscriptSegmenter.segment(unpunctuated)
        assertTrue("Expected unpunctuated transcript to be segmented into multiple clauses, got $segments", segments.size >= 4)

        val actions = extractor.extract(unpunctuated)
        assertTrue("Expected at least 3 extracted actions from unpunctuated transcript, got $actions", actions.size >= 3)
        assertTrue(actions.any { it.text.contains("call the electrical inspector tomorrow") && it.dueAtEpochMs != null })
        assertTrue(actions.any { it.text.contains("email the updated conduit layout to Sam on Thursday") && it.dueAtEpochMs != null })
        assertTrue(actions.any { it.text.contains("buy extra junction boxes") && it.dueAtEpochMs != null })

        val summary = summarizer.summarize(unpunctuated)
        // Summary must not be the entire unpunctuated dictation verbatim
        assertTrue(
            "Expected summary to be shorter than full unpunctuated dictation, got: ${summary.summary}",
            summary.summary.length < unpunctuated.length
        )
        assertTrue(summary.title.isNotBlank())
    }

    @Test
    fun extractiveSummarizer_neverSplitsWordsAt60CharBoundary_andStripsFillerOpeners() = runTest {
        val summarizer = ExtractiveSummarizer()

        val longTranscript =
            "So now I'd like to file issues on the repo for this app so that we can have our agents pick up new tasks."
        val longResult = summarizer.summarize(longTranscript)
        assertTrue(
            "Expected title length <= 60, got ${longResult.title.length}: '${longResult.title}'",
            longResult.title.length <= ExtractiveSummarizer.MAX_TITLE_CHARS
        )
        assertEquals("I'd like to file issues on the repo for this app so that we", longResult.title)
        assertFalse(longResult.title.endsWith(" c"))
        assertFalse(longResult.title.endsWith(" t"))

        val testOpenerTranscript =
            "this is a test voice note and First we'd like to make sure transcription works"
        val testOpenerResult = summarizer.summarize(testOpenerTranscript)
        assertTrue(testOpenerResult.title.length <= ExtractiveSummarizer.MAX_TITLE_CHARS)
        assertFalse(testOpenerResult.title.lowercase().startsWith("this is a test"))
        assertEquals("we'd like to make sure transcription works", testOpenerResult.title)

        // Multi-sentence transcript: long test opener is penalized against real content sentence
        val multiSentence =
            "This is a test voice note checking audio levels in the hallway. " +
                "Call the structural engineer before noon."
        val multiResult = summarizer.summarize(multiSentence)
        assertEquals("Call the structural engineer before noon", multiResult.title)

        // Remind-me title remains intact for E2E expectations
        val reminderResult = summarizer.summarize(
            "Remind me to call the electrician tomorrow. Also buy copper wire for the kitchen."
        )
        assertEquals("Remind me to call the electrician tomorrow", reminderResult.title)
    }

    @Test
    fun nanoSummarizer_producesTopicalTitleWhenAvailable_andDemotesCleanlyOnBusyOrUnavailable() = runTest {
        val availableClient = FakeGenAiSummarizerClient(
            status = CapabilityStatus.AVAILABLE,
            result = TitleSummary(
                title = "File issues on the repo",
                summary = "File repository issues so agents can pick up new tasks."
            )
        )
        val nanoAvailable = NanoSummarizer(client = availableClient, fallback = ExtractiveSummarizer())
        assertEquals(CapabilityStatus.AVAILABLE, nanoAvailable.status())

        val generated = nanoAvailable.summarize(
            "So now I'd like to file issues on the repo for this app so that we can have our agents pick up new tasks."
        )
        assertEquals("File issues on the repo", generated.title)
        assertEquals("File repository issues so agents can pick up new tasks.", generated.summary)
        assertEquals(1, availableClient.generateCalls)

        // BUSY surfaces CapabilityBusyException so the pipeline records degradation and demotes
        val busyClient = FakeGenAiSummarizerClient(status = CapabilityStatus.BUSY)
        val nanoBusy = NanoSummarizer(client = busyClient, fallback = ExtractiveSummarizer())
        assertEquals(CapabilityStatus.BUSY, nanoBusy.status())
        try {
            nanoBusy.summarize("Remind me to call the electrician tomorrow.")
            fail("Expected CapabilityBusyException when NanoSummarizer client is BUSY")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(0, busyClient.generateCalls)

        // UNAVAILABLE demotes cleanly to ExtractiveSummarizer
        val unavailableClient = FakeGenAiSummarizerClient(status = CapabilityStatus.UNAVAILABLE)
        val nanoUnavailable = NanoSummarizer(client = unavailableClient, fallback = ExtractiveSummarizer())
        assertEquals(CapabilityStatus.UNAVAILABLE, nanoUnavailable.status())
        val fallbackResult = nanoUnavailable.summarize(
            "this is a test voice note and First we'd like to make sure transcription works"
        )
        assertEquals("we'd like to make sure transcription works", fallbackResult.title)
        assertEquals(0, unavailableClient.generateCalls)

        // Blank generated title also demotes cleanly to ExtractiveSummarizer
        val blankTitleClient = FakeGenAiSummarizerClient(
            status = CapabilityStatus.AVAILABLE,
            result = TitleSummary(title = "   ", summary = "")
        )
        val nanoBlank = NanoSummarizer(client = blankTitleClient, fallback = ExtractiveSummarizer())
        val blankFallback = nanoBlank.summarize("Remind me to buy milk tomorrow.")
        assertEquals("Remind me to buy milk tomorrow", blankFallback.title)
        assertEquals(1, blankTitleClient.generateCalls)
    }

    @Test
    fun mlKitResponseParser_parsesTwoLineOutputAndTruncatesLongTitleAtWordBoundary() {
        val parsed = MlKitGenAiSummarizerClient.parseTitleSummaryResponse(
            """
            TITLE: File issues on the repo
            SUMMARY: File repository issues so agents can pick up tasks.
            """.trimIndent()
        )
        assertEquals("File issues on the repo", parsed.title)
        assertEquals("File repository issues so agents can pick up tasks.", parsed.summary)

        val overlong = MlKitGenAiSummarizerClient.parseTitleSummaryResponse(
            """
            **TITLE:** "Coordinate quarterly infrastructure audit with security and platform engineering teams"
            **SUMMARY:** Schedule the audit review meeting for next week.
            """.trimIndent()
        )
        assertTrue(overlong.title.length <= ExtractiveSummarizer.MAX_TITLE_CHARS)
        assertEquals("Coordinate quarterly infrastructure audit with security and", overlong.title)
        assertEquals("Schedule the audit review meeting for next week.", overlong.summary)

        val unlabelled = MlKitGenAiSummarizerClient.parseTitleSummaryResponse("Just an unlabelled paragraph.")
        assertEquals("", unlabelled.title)
    }

    @Test
    fun sortingPipeline_usesFakeSummarizerWhenAvailable_andDemotesOnBusyOrFailure() = runTest {
        val dsScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val transcript =
                "So now I'd like to file issues on the repo for this app so that we can have our agents pick up new tasks."
            val dsFile1 = tempFolder.newFile("sum_ok.preferences_pb").also { it.delete() }
            val probe1 = CapabilityProbe(
                checker = FakeCapabilityChecker(
                    transcriber = CapabilityStatus.AVAILABLE,
                    summarizer = CapabilityStatus.AVAILABLE,
                    actions = CapabilityStatus.UNAVAILABLE
                ),
                dataStore = PreferenceDataStoreFactory.create(scope = dsScope, produceFile = { dsFile1 })
            )
            val dao1 = InMemoryFakeNoteDao()
            val noteId1 = dao1.insert(
                Note(createdAtEpochMs = 1_700_000_000_000L, audioPath = "/tmp/note1.m4a")
            )
            val fakeTopicalSummarizer = FakeSummarizer(
                currentStatus = CapabilityStatus.AVAILABLE,
                result = TitleSummary(
                    title = "File issues on the repo",
                    summary = "Create repo issues for agents to pick up new tasks."
                )
            )

            val okOutcome = SortingWorker.executePipeline(
                note = dao1.byId(noteId1)!!,
                capabilityProbe = probe1,
                transcriber = FakeTranscriber(result = Transcript(transcript, Tier.FULL)),
                platformTranscriber = FakeTranscriber(),
                nullTranscriber = NullTranscriber,
                summarizer = fakeTopicalSummarizer,
                extractor = HeuristicActionExtractor(clock = fixedClock),
                bucketer = HeuristicBucketer(),
                fallbackSummarizer = ExtractiveSummarizer(),
                dao = dao1
            )

            assertEquals("File issues on the repo", okOutcome.updatedNote.title)
            assertEquals("Create repo issues for agents to pick up new tasks.", okOutcome.updatedNote.summary)
            assertTrue(okOutcome.updatedNote.stageStatus.contains("summarize:OK"))

            // Runtime BUSY in NanoSummarizer demotes cleanly to ExtractiveSummarizer with word-boundary title
            val dsFile2 = tempFolder.newFile("sum_busy.preferences_pb").also { it.delete() }
            val probe2 = CapabilityProbe(
                checker = FakeCapabilityChecker(
                    transcriber = CapabilityStatus.AVAILABLE,
                    summarizer = CapabilityStatus.AVAILABLE,
                    actions = CapabilityStatus.UNAVAILABLE
                ),
                dataStore = PreferenceDataStoreFactory.create(scope = dsScope, produceFile = { dsFile2 })
            )
            val dao2 = InMemoryFakeNoteDao()
            val noteId2 = dao2.insert(
                Note(createdAtEpochMs = 1_700_000_000_000L, audioPath = "/tmp/note2.m4a")
            )
            val busyNanoSummarizer = NanoSummarizer(
                client = FakeGenAiSummarizerClient(
                    status = CapabilityStatus.AVAILABLE,
                    exception = CapabilityBusyException()
                ),
                fallback = ExtractiveSummarizer()
            )

            val busyOutcome = SortingWorker.executePipeline(
                note = dao2.byId(noteId2)!!,
                capabilityProbe = probe2,
                transcriber = FakeTranscriber(result = Transcript(transcript, Tier.FULL)),
                platformTranscriber = FakeTranscriber(),
                nullTranscriber = NullTranscriber,
                summarizer = busyNanoSummarizer,
                extractor = HeuristicActionExtractor(clock = fixedClock),
                bucketer = HeuristicBucketer(),
                fallbackSummarizer = ExtractiveSummarizer(),
                dao = dao2
            )

            assertEquals("I'd like to file issues on the repo for this app so that we", busyOutcome.updatedNote.title)
            assertEquals(NoteStatus.NEEDS_REVIEW.name, busyOutcome.updatedNote.status)
            assertTrue(busyOutcome.updatedNote.stageStatus.contains("summarize:BUSY,summarize_fallback:OK"))

            // Runtime non-busy exception in NanoSummarizer also falls back cleanly to ExtractiveSummarizer
            val dsFile3 = tempFolder.newFile("sum_fail.preferences_pb").also { it.delete() }
            val probe3 = CapabilityProbe(
                checker = FakeCapabilityChecker(
                    transcriber = CapabilityStatus.AVAILABLE,
                    summarizer = CapabilityStatus.AVAILABLE,
                    actions = CapabilityStatus.UNAVAILABLE
                ),
                dataStore = PreferenceDataStoreFactory.create(scope = dsScope, produceFile = { dsFile3 })
            )
            val dao3 = InMemoryFakeNoteDao()
            val noteId3 = dao3.insert(
                Note(createdAtEpochMs = 1_700_000_000_000L, audioPath = "/tmp/note3.m4a")
            )
            val failingNanoSummarizer = NanoSummarizer(
                client = FakeGenAiSummarizerClient(
                    status = CapabilityStatus.AVAILABLE,
                    exception = IllegalStateException("Simulated Prompt API failure")
                ),
                fallback = ExtractiveSummarizer()
            )

            val failOutcome = SortingWorker.executePipeline(
                note = dao3.byId(noteId3)!!,
                capabilityProbe = probe3,
                transcriber = FakeTranscriber(result = Transcript(transcript, Tier.FULL)),
                platformTranscriber = FakeTranscriber(),
                nullTranscriber = NullTranscriber,
                summarizer = failingNanoSummarizer,
                extractor = HeuristicActionExtractor(clock = fixedClock),
                bucketer = HeuristicBucketer(),
                fallbackSummarizer = ExtractiveSummarizer(),
                dao = dao3
            )

            assertEquals("I'd like to file issues on the repo for this app so that we", failOutcome.updatedNote.title)
            assertEquals(NoteStatus.NEEDS_REVIEW.name, failOutcome.updatedNote.status)
            assertTrue(failOutcome.updatedNote.stageStatus.contains("summarize:FAILED,summarize_fallback:OK"))
        } finally {
            dsScope.cancel()
        }
    }
}
