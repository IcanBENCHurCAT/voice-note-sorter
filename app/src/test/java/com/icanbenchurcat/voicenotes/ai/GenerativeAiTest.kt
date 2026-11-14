// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import com.icanbenchurcat.voicenotes.fixtures.FakeActionExtractor
import com.icanbenchurcat.voicenotes.fixtures.FakeBucketer
import com.icanbenchurcat.voicenotes.fixtures.FakeSummarizer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

class FakeGenAiPromptClient(
    var currentStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    var response: String = "",
    var generateException: Exception? = null,
    private val responder: ((String) -> String)? = null
) : GenAiPromptClient {
    var statusCalls = 0
        private set
    var generateCalls = 0
        private set
    var lastPrompt: String? = null
        private set

    override suspend fun checkStatus(): CapabilityStatus {
        statusCalls++
        return currentStatus
    }

    override suspend fun generateText(prompt: String): String {
        generateCalls++
        lastPrompt = prompt
        generateException?.let { throw it }
        return responder?.invoke(prompt) ?: response
    }
}

class GenerativeAiTest {

    // Fixed reference time: Monday, 2026-11-02 10:00:00 UTC
    private val zone = ZoneId.of("UTC")
    private val fixedInstant = LocalDateTime.of(2026, 11, 2, 10, 0, 0).atZone(zone).toInstant()
    private val fixedClock = Clock.fixed(fixedInstant, zone)
    private val defaultProfile = DefaultBucketProfileProvider.profile()

    @Test
    fun nanoSummarizer_available_parsesTitleAndSummary_andTruncatesOnWordBoundary() = runTest {
        val longTitle = "Schedule structural engineering inspection for the warehouse roof tomorrow morning"
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = """
                ```
                **Title:** "$longTitle"
                **Summary:** Call the structural engineer to inspect the warehouse roof beams before Friday.
                Confirm crane access with site operations.
                ```
            """.trimIndent()
        )
        val fallback = FakeSummarizer(result = TitleSummary("fallback", "fallback summary"))
        val summarizer = NanoSummarizer(promptClient = client, fallback = fallback)

        assertEquals(CapabilityStatus.AVAILABLE, summarizer.status())

        val (result, tier) = summarizer.summarizeWithTier("Need to schedule structural engineering inspection.")
        assertEquals(Tier.FULL, tier)
        assertEquals(1, client.generateCalls)
        assertEquals(0, fallback.calls)
        assertTrue("Expected title <= 60 chars, got ${result.title.length}: '${result.title}'", result.title.length <= 60)
        assertEquals("Schedule structural engineering inspection for the warehouse", result.title)
        assertEquals(
            "Call the structural engineer to inspect the warehouse roof beams before Friday. Confirm crane access with site operations.",
            result.summary
        )
    }

    @Test
    fun nanoSummarizer_busy_throwsCapabilityBusyException_withoutCallingFallback() = runTest {
        val busyStatusClient = FakeGenAiPromptClient(currentStatus = CapabilityStatus.BUSY)
        val fallback = FakeSummarizer()
        val summarizerBusyStatus = NanoSummarizer(promptClient = busyStatusClient, fallback = fallback)

        assertEquals(CapabilityStatus.BUSY, summarizerBusyStatus.status())
        try {
            summarizerBusyStatus.summarize("Some transcript")
            fail("Expected CapabilityBusyException when status is BUSY")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(0, busyStatusClient.generateCalls)
        assertEquals(0, fallback.calls)

        val busyGenerateClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            generateException = CapabilityBusyException()
        )
        val summarizerBusyGenerate = NanoSummarizer(promptClient = busyGenerateClient, fallback = fallback)
        try {
            summarizerBusyGenerate.summarize("Some transcript")
            fail("Expected CapabilityBusyException when generateText throws BUSY")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(1, busyGenerateClient.generateCalls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun nanoSummarizer_unavailableDownloadableOrUnparseable_fallsBackToExtractive() = runTest {
        val fallbackResult = TitleSummary("Extractive title", "Extractive summary.")
        for (nonReadyStatus in listOf(CapabilityStatus.UNAVAILABLE, CapabilityStatus.DOWNLOADABLE)) {
            val client = FakeGenAiPromptClient(currentStatus = nonReadyStatus)
            val fallback = FakeSummarizer(result = fallbackResult)
            val summarizer = NanoSummarizer(promptClient = client, fallback = fallback)

            assertEquals(nonReadyStatus, summarizer.status())
            val (result, tier) = summarizer.summarizeWithTier("Transcript needing summary.")
            assertEquals(Tier.CORE, tier)
            assertEquals(fallbackResult, result)
            assertEquals(0, client.generateCalls)
            assertEquals(1, fallback.calls)
        }

        // Unparseable / blank responses also fall back to heuristic
        for (badOutput in listOf("", "   ", "Just a random sentence without labels.", "Title: Only title")) {
            val client = FakeGenAiPromptClient(
                currentStatus = CapabilityStatus.AVAILABLE,
                response = badOutput
            )
            val fallback = FakeSummarizer(result = fallbackResult)
            val summarizer = NanoSummarizer(promptClient = client, fallback = fallback)

            val (result, tier) = summarizer.summarizeWithTier("Transcript needing summary.")
            assertEquals(Tier.CORE, tier)
            assertEquals(fallbackResult, result)
            assertEquals(1, client.generateCalls)
            assertEquals(1, fallback.calls)
        }
    }

    @Test
    fun nanoActionExtractor_available_parsesActionsAndDueDates() = runTest {
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = """
                - ACTION: Call the electrician | DUE: tomorrow at 2pm
                - ACTION: Buy copper wire | DUE: NONE
                - ACTION: Email updated plans on Thursday | DUE: NONE
                - ACTION: Call the electrician | DUE: tomorrow at 2pm
            """.trimIndent()
        )
        val fallback = FakeActionExtractor(items = listOf(ExtractedAction("fallback action")))
        val extractor = NanoActionExtractor(
            promptClient = client,
            fallback = fallback,
            clock = fixedClock
        )

        assertEquals(CapabilityStatus.AVAILABLE, extractor.status())
        val (actions, tier) = extractor.extractWithTier(
            "Call the electrician tomorrow at 2pm, buy copper wire, and email updated plans on Thursday."
        )

        assertEquals(Tier.FULL, tier)
        assertEquals(1, client.generateCalls)
        assertEquals(0, fallback.calls)
        assertEquals(3, actions.size)

        assertEquals("Call the electrician", actions[0].text)
        assertNotNull(actions[0].dueAtEpochMs)
        assertEquals(
            LocalDateTime.of(2026, 11, 3, 14, 0),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(actions[0].dueAtEpochMs!!), zone)
        )

        assertEquals("Buy copper wire", actions[1].text)
        assertNull(actions[1].dueAtEpochMs)

        assertEquals("Email updated plans on Thursday", actions[2].text)
        assertNotNull(actions[2].dueAtEpochMs)
        assertEquals(
            LocalDateTime.of(2026, 11, 5, 9, 0),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(actions[2].dueAtEpochMs!!), zone)
        )
    }

    @Test
    fun nanoActionExtractor_noneResponse_returnsEmptyListWithoutFallback() = runTest {
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = "NONE"
        )
        val fallback = FakeActionExtractor(items = listOf(ExtractedAction("should not run")))
        val extractor = NanoActionExtractor(promptClient = client, fallback = fallback, clock = fixedClock)

        val (actions, tier) = extractor.extractWithTier("Just enjoying a quiet walk in the park.")
        assertEquals(Tier.FULL, tier)
        assertTrue(actions.isEmpty())
        assertEquals(1, client.generateCalls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun nanoActionExtractor_busyThrows_andUnavailableOrUnparseableFallsBack() = runTest {
        val busyClient = FakeGenAiPromptClient(currentStatus = CapabilityStatus.BUSY)
        val fallback = FakeActionExtractor(items = listOf(ExtractedAction("Heuristic action")))
        val busyExtractor = NanoActionExtractor(promptClient = busyClient, fallback = fallback, clock = fixedClock)
        try {
            busyExtractor.extract("Remind me to call Sam")
            fail("Expected CapabilityBusyException")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(0, fallback.calls)

        val unavailableClient = FakeGenAiPromptClient(currentStatus = CapabilityStatus.UNAVAILABLE)
        val unavailableExtractor = NanoActionExtractor(promptClient = unavailableClient, fallback = fallback, clock = fixedClock)
        val (unavailableItems, unavailableTier) = unavailableExtractor.extractWithTier("Remind me to call Sam")
        assertEquals(Tier.CORE, unavailableTier)
        assertEquals(listOf(ExtractedAction("Heuristic action")), unavailableItems)
        assertEquals(1, fallback.calls)

        val unparseableClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = "Sorry, I cannot comply with that format."
        )
        val fallback2 = FakeActionExtractor(items = listOf(ExtractedAction("Fallback item")))
        val unparseableExtractor = NanoActionExtractor(promptClient = unparseableClient, fallback = fallback2, clock = fixedClock)
        val (parsed, parsedTier) = unparseableExtractor.extractWithTier("Remind me to call Sam")
        assertEquals(Tier.CORE, parsedTier)
        assertEquals(listOf(ExtractedAction("Fallback item")), parsed)
        assertEquals(1, fallback2.calls)
    }

    @Test
    fun nanoBucketer_available_classifiesIntoProfileBucketAndNormalizesTags() = runTest {
        val customProfile = BucketProfile(
            defaultProfile.buckets + BucketDef("FINANCE", "Finance", emptyList())
        )
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = """
                BUCKET: FINANCE (Finance)
                TAGS: #Tax Prep, quarterly-filing, accountant, #Tax Prep
            """.trimIndent()
        )
        val fallback = FakeBucketer(BucketAssignment("INBOX", emptyList(), Tier.CORE))
        val bucketer = NanoBucketer(promptClient = client, fallback = fallback)

        assertEquals(CapabilityStatus.AVAILABLE, bucketer.status())

        val assignment = bucketer.assign("Check quarterly estimated tax payment with CPA.", customProfile)
        assertEquals("FINANCE", assignment.bucketKind)
        assertEquals(listOf("tax-prep", "quarterly-filing", "accountant"), assignment.tags)
        assertEquals(Tier.FULL, assignment.tier)
        assertFalse(assignment.needsReview)
        assertTrue(assignment.confidence >= 0.8f)
        assertTrue(assignment.score > 0)
        assertEquals(1, client.generateCalls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun nanoBucketer_inboxAssignment_setsTierFullWithNeedsReview() = runTest {
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = "BUCKET: INBOX\nTAGS: NONE"
        )
        val fallback = FakeBucketer()
        val bucketer = NanoBucketer(promptClient = client, fallback = fallback)

        val assignment = bucketer.assign("Random stray thought.", defaultProfile)
        assertEquals("INBOX", assignment.bucketKind)
        assertTrue(assignment.tags.isEmpty())
        assertEquals(Tier.FULL, assignment.tier)
        assertTrue(assignment.needsReview)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun nanoBucketer_busyThrows_andUnavailableOrInvalidBucketFallsBack() = runTest {
        val busyClient = FakeGenAiPromptClient(currentStatus = CapabilityStatus.BUSY)
        val fallback = FakeBucketer(BucketAssignment("TASK", listOf("fallback"), Tier.CORE))
        val busyBucketer = NanoBucketer(promptClient = busyClient, fallback = fallback)

        try {
            busyBucketer.assign("Call dentist", defaultProfile)
            fail("Expected CapabilityBusyException")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(0, fallback.calls)

        val downloadableClient = FakeGenAiPromptClient(currentStatus = CapabilityStatus.DOWNLOADABLE)
        val downloadableBucketer = NanoBucketer(promptClient = downloadableClient, fallback = fallback)
        val fallbackAssignment = downloadableBucketer.assign("Call dentist", defaultProfile)
        assertEquals(Tier.CORE, fallbackAssignment.tier)
        assertEquals("TASK", fallbackAssignment.bucketKind)
        assertEquals(1, fallback.calls)

        val invalidBucketClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = "BUCKET: UNKNOWN_CATEGORY\nTAGS: misc"
        )
        val fallback2 = FakeBucketer(BucketAssignment("INBOX", emptyList(), Tier.CORE))
        val invalidBucketer = NanoBucketer(promptClient = invalidBucketClient, fallback = fallback2)
        val invalidFallback = invalidBucketer.assign("Some transcript", defaultProfile)
        assertEquals(Tier.CORE, invalidFallback.tier)
        assertEquals("INBOX", invalidFallback.bucketKind)
        assertEquals(1, fallback2.calls)
    }
}
