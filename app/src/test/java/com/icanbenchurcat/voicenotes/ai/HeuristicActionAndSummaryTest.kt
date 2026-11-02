// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class HeuristicActionAndSummaryTest {

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
}
