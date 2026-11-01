// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

class HeuristicActionExtractorTest {
    private val reference = Instant.parse("2026-11-01T10:00:00Z")
    private val fixedClock = object : Clock {
        override fun now(): Instant = reference
    }

    @Test
    fun annotatesAnExplicitDateWithoutChangingActionText() = runBlocking {
        val action = extractor()
            .extract("Remind me to call on November 2, 2026 at 10:00.").single()

        assertEquals("Remind me to call on November 2, 2026 at 10:00.", action.text)
        assertEquals(Instant.parse("2026-11-02T10:00:00Z").toEpochMilliseconds(), action.dueAtEpochMs)
    }

    @Test
    fun tomorrowRetainsTheReferenceClock() = runBlocking {
        assertDue("Remind me to call tomorrow.", "2026-11-02T10:00:00Z")
    }

    @Test
    fun weekdayRetainsTheReferenceClock() = runBlocking {
        assertDue("I need to call on Thursday.", "2026-11-05T10:00:00Z")
    }

    @Test
    fun explicitTimeOverridesTheReferenceClock() = runBlocking {
        assertDue("Don't forget to call tomorrow at 7:30pm.", "2026-11-02T19:30:00Z")
    }

    @Test
    fun russianDateIsParsedWithinAnAlreadySelectedAction() = runBlocking {
        assertDue("Remind me позвонить завтра в 19:30.", "2026-11-02T19:30:00Z")
    }

    @Test
    fun aPastBareClockDoesNotRollForward() = runBlocking {
        assertDue("Remember to call at 09:00.", "2026-11-01T09:00:00Z")
    }

    @Test
    fun localTimeUsesTheExplicitTimeZone() = runBlocking {
        val transcript = "Remind me to call tomorrow at 5pm."
        val utc = extractor().extract(transcript).single()
        val losAngeles = extractor(TimeZone.of("America/Los_Angeles")).extract(transcript).single()

        assertEquals(Instant.parse("2026-11-02T17:00:00Z").toEpochMilliseconds(), utc.dueAtEpochMs)
        assertEquals(Instant.parse("2026-11-03T01:00:00Z").toEpochMilliseconds(), losAngeles.dueAtEpochMs)
    }

    @Test
    fun allActionsShareOneReferenceEvenAcrossMidnight() = runBlocking {
        var reads = 0
        val crossingClock = object : Clock {
            override fun now(): Instant = if (reads++ == 0) {
                Instant.parse("2026-11-01T23:59:59Z")
            } else {
                Instant.parse("2026-11-02T00:00:01Z")
            }
        }
        val actions = HeuristicActionExtractor(crossingClock, TimeZone.UTC)
            .extract("Remind me to call tomorrow. I need to buy milk tomorrow.")

        assertEquals(2, actions.size)
        val expected = Instant.parse("2026-11-02T23:59:59Z").toEpochMilliseconds()
        assertEquals(listOf(expected, expected), actions.map { it.dueAtEpochMs })
        assertEquals(1, reads)
    }

    @Test
    fun unsupportedOrAmbiguousDatesRemainNullWithoutLosingTheAction() = runBlocking {
        val transcripts = listOf(
            "Remind me to call tomorrow at 25.",
            "Remind me to call tomorrow at 7.",
            "Remind me to call tomorrow at 25:00.",
            "Remind me to call tomorrow at 19:61.",
            "Remind me to call on 03/04/2026.",
            "Remind me to call on February 30, 2026.",
            "Need to call tomorrow from 5pm to 8pm.",
            "Remember to call every Thursday.",
            "Remind me позвонить завтра с 17:30 до 20:00.",
            "Remind me звонить каждый месяц.",
            "Remind me to call tomorrow or Thursday.",
            "Remind me to call tomorrow at 9am and 5pm.",
        )

        for (transcript in transcripts) {
            val action = extractor().extract(transcript).single()
            assertEquals(transcript, action.text)
            assertNull(transcript, action.dueAtEpochMs)
        }
    }

    @Test
    fun partialRecurrenceParsesRemainUndated() = runBlocking {
        assertUndated("Remind me to call daily at 5pm.")
        assertUndated("Remind me to call weekly at 5pm.")
    }

    @Test
    fun partialBareHourRangesRemainUndated() = runBlocking {
        assertUndated("Remind me to call tomorrow between 5 and 8.")
        assertUndated("Remind me to call tomorrow from 5 to 8.")
    }

    @Test
    fun anUnconsumedExplicitTimeZoneDoesNotUseTheDeviceZone() = runBlocking {
        assertUndated("Remind me to call tomorrow at 5pm EST.")
        assertUndated("Remind me to call tomorrow at 17:30 UTC+3.")
    }

    @Test
    fun ordinaryTaskWordsAndUrlPortsDoNotHideAnUnambiguousDate() = runBlocking {
        assertDue("Need to read the daily report tomorrow.", "2026-11-02T10:00:00Z")
        assertDue("Need to call Alex and Sam tomorrow.", "2026-11-02T10:00:00Z")
        assertDue("Need to check http://127.0.0.1:8080 tomorrow.", "2026-11-02T10:00:00Z")
    }

    @Test
    fun dateAnnotationPreservesExistingActionSelectionAndTrimming() = runBlocking {
        val actions = extractor().extract(
            "  Just a note for tomorrow.  Remind me to call!\n" +
                "I NEED TO buy milk? Don't forget the keys. Remember to stretch.\n" +
                "Buy bread tomorrow. Напомни мне позвонить завтра.  "
        )

        assertEquals(
            listOf(
                ExtractedAction("Remind me to call!"),
                ExtractedAction("I NEED TO buy milk?"),
                ExtractedAction("Don't forget the keys."),
                ExtractedAction("Remember to stretch."),
            ),
            actions,
        )
    }

    @Test
    fun emptyInputProducesNoActions() = runBlocking {
        assertEquals(emptyList<ExtractedAction>(), extractor().extract(" \n "))
    }

    @Test
    fun defaultConstructionStillExtractsAnUndatedAction() = runBlocking {
        assertEquals(
            listOf(ExtractedAction("Remind me to call.")),
            HeuristicActionExtractor().extract("Remind me to call."),
        )
    }

    private fun extractor(timeZone: TimeZone = TimeZone.UTC) =
        HeuristicActionExtractor(fixedClock, timeZone)

    private suspend fun assertDue(transcript: String, expected: String) {
        val action = extractor().extract(transcript).single()
        assertEquals(transcript, action.text)
        assertEquals(Instant.parse(expected).toEpochMilliseconds(), action.dueAtEpochMs)
    }

    private suspend fun assertUndated(transcript: String) {
        val action = extractor().extract(transcript).single()
        assertEquals(transcript, action.text)
        assertNull(transcript, action.dueAtEpochMs)
    }
}
