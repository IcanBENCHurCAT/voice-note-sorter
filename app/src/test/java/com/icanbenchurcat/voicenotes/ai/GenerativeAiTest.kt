// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import com.icanbenchurcat.voicenotes.fixtures.FakeActionExtractor
import com.icanbenchurcat.voicenotes.fixtures.FakeBucketer
import com.icanbenchurcat.voicenotes.fixtures.FakeProofreader
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
import kotlin.reflect.KClass

class FakeGenAiPromptSession(
    private val client: FakeGenAiPromptClient
) : GenAiPromptSession {
    var statusCalls = 0
        private set
    var warmupCalls = 0
        private set
    var clearImplicitCachesCalls = 0
        private set
    var closeCalls = 0
        private set
    var generateTypedCalls = 0
        private set
    var generateTextCalls = 0
        private set
    val textRequests = mutableListOf<PromptRequest>()
    val typedRequests = mutableListOf<PromptRequest>()

    override suspend fun checkStatus(): CapabilityStatus {
        statusCalls++
        return client.checkStatus()
    }

    override suspend fun isStructuredOutputAvailable(): Boolean =
        client.isStructuredOutputAvailable()

    override suspend fun isSystemPromptAvailable(): Boolean =
        client.isSystemPromptAvailable()

    override suspend fun isCachingAvailable(): Boolean =
        client.isCachingAvailable()

    override suspend fun warmup() {
        warmupCalls++
        client.warmupCalls++
    }

    override suspend fun clearImplicitCaches() {
        clearImplicitCachesCalls++
        client.clearImplicitCachesCalls++
    }

    override suspend fun <T : Any> generateTyped(
        request: PromptRequest,
        outputClass: KClass<T>
    ): T? {
        generateTypedCalls++
        typedRequests += request
        return client.generateTyped(request, outputClass)
    }

    override suspend fun generateText(request: PromptRequest): String {
        generateTextCalls++
        textRequests += request
        return client.generateText(request)
    }

    override fun close() {
        closeCalls++
        client.closeCalls++
    }
}

class FakeGenAiPromptClient(
    var currentStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    var response: String = "",
    var generateException: Exception? = null,
    private val responder: ((String) -> String)? = null,
    var structuredOutputAvailable: Boolean = false,
    var systemPromptAvailable: Boolean = false,
    var cachingAvailable: Boolean = false,
    var typedResponse: Any? = null,
    var typedException: Exception? = null,
    private val typedResponder: ((PromptRequest, KClass<*>) -> Any?)? = null,
    var imageResponse: String = "",
    var imageGenerateException: Exception? = null,
    private val imageResponder: ((String, String) -> String)? = null
) : GenAiPromptClient {
    var statusCalls = 0
        private set
    var generateCalls = 0
        private set
    var generateTypedCalls = 0
        private set
    var imageGenerateCalls = 0
        private set
    var warmupCalls = 0
    var clearImplicitCachesCalls = 0
    var closeCalls = 0
    var lastPrompt: String? = null
        private set
    var lastImagePath: String? = null
        private set
    var lastRequest: PromptRequest? = null
        private set
    var lastTypedRequest: PromptRequest? = null
        private set
    var lastTypedOutputClass: KClass<*>? = null
        private set
    val textRequests = mutableListOf<PromptRequest>()
    val typedRequests = mutableListOf<PromptRequest>()
    val openedSessions = mutableListOf<FakeGenAiPromptSession>()

    override suspend fun checkStatus(): CapabilityStatus {
        statusCalls++
        return currentStatus
    }

    override suspend fun isStructuredOutputAvailable(): Boolean = structuredOutputAvailable

    override suspend fun isSystemPromptAvailable(): Boolean = systemPromptAvailable

    override suspend fun isCachingAvailable(): Boolean = cachingAvailable

    override suspend fun <T : Any> generateTyped(
        request: PromptRequest,
        outputClass: KClass<T>
    ): T? {
        generateTypedCalls++
        lastTypedRequest = request
        lastTypedOutputClass = outputClass
        typedRequests += request
        typedException?.let { throw it }
        val result = typedResponder?.invoke(request, outputClass) ?: typedResponse
        @Suppress("UNCHECKED_CAST")
        return result as T?
    }

    override suspend fun generateText(prompt: String): String {
        generateCalls++
        lastPrompt = prompt
        generateException?.let { throw it }
        return responder?.invoke(prompt) ?: response
    }

    override suspend fun generateText(request: PromptRequest): String {
        lastRequest = request
        textRequests += request
        return generateText(request.prompt)
    }

    override suspend fun generateTextFromImage(imagePath: String, prompt: String): String {
        imageGenerateCalls++
        lastImagePath = imagePath
        lastPrompt = prompt
        imageGenerateException?.let { throw it }
        return imageResponder?.invoke(imagePath, prompt) ?: imageResponse
    }

    override fun openSession(): GenAiPromptSession =
        FakeGenAiPromptSession(this).also { openedSessions += it }
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

    // --- Issue #145: Structured output, fallback, SystemInstruction & prefix caching ---

    @Test
    fun nanoSummarizer_structuredOutputAvailable_usesTypedGenerationWithSystemInstructionAndPrefix() = runTest {
        val longTitle = "Schedule structural engineering inspection for the warehouse roof tomorrow morning"
        val transcript = "Need to schedule structural engineering inspection for roof beams."
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            systemPromptAvailable = true,
            cachingAvailable = true,
            typedResponse = StructuredTitleSummary(
                title = "\"$longTitle\"",
                summary = "  **Call the engineer before Friday.**  "
            ),
            response = "Title: Should Not Use Text\nSummary: Should not use text."
        )
        val fallback = FakeSummarizer(result = TitleSummary("fallback", "fallback summary"))
        val summarizer = NanoSummarizer(promptClient = client, fallback = fallback)

        val (result, tier) = summarizer.summarizeWithTier(transcript)

        assertEquals(Tier.FULL, tier)
        assertEquals(1, client.generateTypedCalls)
        assertEquals(0, client.generateCalls)
        assertEquals(0, fallback.calls)
        assertEquals(StructuredTitleSummary::class, client.lastTypedOutputClass)
        assertEquals(NOTE_ANALYSIS_SYSTEM_INSTRUCTION, client.lastTypedRequest?.systemInstruction)
        assertEquals(buildTranscriptPrefix(transcript), client.lastTypedRequest?.promptPrefix)
        assertEquals("Schedule structural engineering inspection for the warehouse", result.title)
        assertEquals("Call the engineer before Friday.", result.summary)
    }

    @Test
    fun nanoActionExtractor_structuredOutputAvailable_resolvesDueDates_andSupportsEmptyActions() = runTest {
        val transcript = "Call the electrician tomorrow at 2pm and buy copper wire."
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedResponse = StructuredActionExtraction(
                actions = listOf(
                    StructuredActionItem(text = "Call the electrician", duePhrase = "tomorrow at 2pm"),
                    StructuredActionItem(text = "Buy copper wire", duePhrase = null),
                    StructuredActionItem(text = "Call the electrician", duePhrase = "tomorrow at 2pm")
                )
            )
        )
        val fallback = FakeActionExtractor(items = listOf(ExtractedAction("fallback action")))
        val extractor = NanoActionExtractor(promptClient = client, fallback = fallback, clock = fixedClock)

        val (actions, tier) = extractor.extractWithTier(transcript)

        assertEquals(Tier.FULL, tier)
        assertEquals(1, client.generateTypedCalls)
        assertEquals(0, client.generateCalls)
        assertEquals(0, fallback.calls)
        assertEquals(StructuredActionExtraction::class, client.lastTypedOutputClass)
        assertEquals(NOTE_ANALYSIS_SYSTEM_INSTRUCTION, client.lastTypedRequest?.systemInstruction)
        assertEquals(buildTranscriptPrefix(transcript), client.lastTypedRequest?.promptPrefix)
        assertEquals(2, actions.size)
        assertEquals("Call the electrician", actions[0].text)
        assertNotNull(actions[0].dueAtEpochMs)
        assertEquals("Buy copper wire", actions[1].text)
        assertNull(actions[1].dueAtEpochMs)

        // Empty list returned from structured output is treated as valid "no actions" at Tier.FULL
        val emptyTypedClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedResponse = StructuredActionExtraction(actions = emptyList())
        )
        val emptyExtractor = NanoActionExtractor(promptClient = emptyTypedClient, fallback = fallback, clock = fixedClock)
        val (emptyItems, emptyTier) = emptyExtractor.extractWithTier("Walking through the park today.")
        assertEquals(Tier.FULL, emptyTier)
        assertTrue(emptyItems.isEmpty())
        assertEquals(1, emptyTypedClient.generateTypedCalls)
        assertEquals(0, emptyTypedClient.generateCalls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun nanoBucketer_structuredOutputAvailable_matchesProfileBucketAndNormalizesTags() = runTest {
        val customProfile = BucketProfile(
            defaultProfile.buckets + BucketDef("FINANCE", "Finance", emptyList())
        )
        val transcript = "Check quarterly estimated tax payment with CPA."
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedResponse = StructuredBucketAssignment(
                bucketKind = "finance",
                tags = listOf("#Tax Prep", "quarterly-filing", "NONE", "tax-prep")
            )
        )
        val fallback = FakeBucketer(BucketAssignment("INBOX", emptyList(), Tier.CORE))
        val bucketer = NanoBucketer(promptClient = client, fallback = fallback)

        val assignment = bucketer.assign(transcript, customProfile)

        assertEquals("FINANCE", assignment.bucketKind)
        assertEquals(listOf("tax-prep", "quarterly-filing"), assignment.tags)
        assertEquals(Tier.FULL, assignment.tier)
        assertFalse(assignment.needsReview)
        assertEquals(1, client.generateTypedCalls)
        assertEquals(0, client.generateCalls)
        assertEquals(0, fallback.calls)
        assertEquals(StructuredBucketAssignment::class, client.lastTypedOutputClass)
        assertEquals(NOTE_ANALYSIS_SYSTEM_INSTRUCTION, client.lastTypedRequest?.systemInstruction)
        assertEquals(buildTranscriptPrefix(transcript), client.lastTypedRequest?.promptPrefix)
    }

    @Test
    fun structuredOutputNonBusyFailureOrInvalidPayload_fallsBackToRegexTextAtTierFull() = runTest {
        // 1. Summarizer: typed throws non-busy -> regex succeeds at Tier.FULL
        val summarizerFallback = FakeSummarizer()
        val summaryClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedException = IllegalStateException("Structured output decode error"),
            response = "Title: Hardware Run\nSummary: Pick up brass screws from the hardware store."
        )
        val (summaryResult, summaryTier) = NanoSummarizer(summaryClient, summarizerFallback)
            .summarizeWithTier("Pick up brass screws.")
        assertEquals(Tier.FULL, summaryTier)
        assertEquals(1, summaryClient.generateTypedCalls)
        assertEquals(1, summaryClient.generateCalls)
        assertEquals(0, summarizerFallback.calls)
        assertEquals("Hardware Run", summaryResult.title)
        assertEquals(NOTE_ANALYSIS_SYSTEM_INSTRUCTION, summaryClient.lastRequest?.systemInstruction)

        // 2. ActionExtractor: typed has only blank items -> falls back to regex -> succeeds at Tier.FULL
        val actionFallback = FakeActionExtractor()
        val actionClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedResponse = StructuredActionExtraction(actions = listOf(StructuredActionItem("   ", null))),
            response = "ACTION: Order brass screws | DUE: tomorrow at 9am"
        )
        val (actions, actionTier) = NanoActionExtractor(actionClient, actionFallback, fixedClock)
            .extractWithTier("Order brass screws tomorrow at 9am.")
        assertEquals(Tier.FULL, actionTier)
        assertEquals(1, actionClient.generateTypedCalls)
        assertEquals(1, actionClient.generateCalls)
        assertEquals(0, actionFallback.calls)
        assertEquals(listOf("Order brass screws"), actions.map { it.text })

        // 3. Bucketer: typed returns unknown bucket -> falls back to regex -> succeeds at Tier.FULL
        val bucketFallback = FakeBucketer()
        val bucketClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedResponse = StructuredBucketAssignment(bucketKind = "NON_EXISTENT", tags = listOf("misc")),
            response = "BUCKET: LIST\nTAGS: hardware, shopping"
        )
        val bucketAssignment = NanoBucketer(bucketClient, bucketFallback)
            .assign("Brass screws and bolts", defaultProfile)
        assertEquals(Tier.FULL, bucketAssignment.tier)
        assertEquals("LIST", bucketAssignment.bucketKind)
        assertEquals(listOf("hardware", "shopping"), bucketAssignment.tags)
        assertEquals(1, bucketClient.generateTypedCalls)
        assertEquals(1, bucketClient.generateCalls)
        assertEquals(0, bucketFallback.calls)
    }

    @Test
    fun structuredOutputAndRegexBothMalformed_demotesToTierCoreHeuristicFallback() = runTest {
        val transcript = "Remind me to buy oat milk tomorrow."

        val summaryFallback = FakeSummarizer(result = TitleSummary("Extractive", "Extractive summary."))
        val summaryClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedResponse = StructuredTitleSummary(title = "  ", summary = ""),
            response = "Not formatted correctly"
        )
        val (sumResult, sumTier) = NanoSummarizer(summaryClient, summaryFallback).summarizeWithTier(transcript)
        assertEquals(Tier.CORE, sumTier)
        assertEquals("Extractive", sumResult.title)
        assertEquals(1, summaryClient.generateTypedCalls)
        assertEquals(1, summaryClient.generateCalls)
        assertEquals(1, summaryFallback.calls)

        val actionFallback = FakeActionExtractor(items = listOf(ExtractedAction("Buy oat milk")))
        val actionClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedResponse = null,
            response = "Unparseable response"
        )
        val (actResult, actTier) = NanoActionExtractor(actionClient, actionFallback, fixedClock)
            .extractWithTier(transcript)
        assertEquals(Tier.CORE, actTier)
        assertEquals(listOf("Buy oat milk"), actResult.map { it.text })
        assertEquals(1, actionClient.generateTypedCalls)
        assertEquals(1, actionClient.generateCalls)
        assertEquals(1, actionFallback.calls)

        val bucketFallback = FakeBucketer(BucketAssignment("TASK", listOf("remind-me"), Tier.CORE))
        val bucketClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedResponse = StructuredBucketAssignment(bucketKind = "INVALID", tags = emptyList()),
            response = "Still not a bucket"
        )
        val bucketResult = NanoBucketer(bucketClient, bucketFallback).assign(transcript, defaultProfile)
        assertEquals(Tier.CORE, bucketResult.tier)
        assertEquals("TASK", bucketResult.bucketKind)
        assertEquals(1, bucketClient.generateTypedCalls)
        assertEquals(1, bucketClient.generateCalls)
        assertEquals(1, bucketFallback.calls)
    }

    @Test
    fun structuredOutputBusyException_propagatesWithoutCallingTextFallbackOrHeuristics() = runTest {
        val summaryFallback = FakeSummarizer()
        val summaryClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedException = CapabilityBusyException()
        )
        try {
            NanoSummarizer(summaryClient, summaryFallback).summarize("Some transcript")
            fail("Expected CapabilityBusyException from generateTyped in NanoSummarizer")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(1, summaryClient.generateTypedCalls)
        assertEquals(0, summaryClient.generateCalls)
        assertEquals(0, summaryFallback.calls)

        val actionFallback = FakeActionExtractor()
        val actionClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedException = CapabilityBusyException()
        )
        try {
            NanoActionExtractor(actionClient, actionFallback, fixedClock).extract("Remind me to call Sam")
            fail("Expected CapabilityBusyException from generateTyped in NanoActionExtractor")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(1, actionClient.generateTypedCalls)
        assertEquals(0, actionClient.generateCalls)
        assertEquals(0, actionFallback.calls)

        val bucketFallback = FakeBucketer()
        val bucketClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            structuredOutputAvailable = true,
            typedException = CapabilityBusyException()
        )
        try {
            NanoBucketer(bucketClient, bucketFallback).assign("Call Sam", defaultProfile)
            fail("Expected CapabilityBusyException from generateTyped in NanoBucketer")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(1, bucketClient.generateTypedCalls)
        assertEquals(0, bucketClient.generateCalls)
        assertEquals(0, bucketFallback.calls)
    }

    @Test
    fun mlKitGenAiPromptClient_withoutAiCoreContext_degradesSafelyInJvmSession() = runTest {
        val session = MlKitGenAiPromptClient.openSession()
        try {
            assertEquals(CapabilityStatus.UNAVAILABLE, session.checkStatus())
            assertFalse(session.isStructuredOutputAvailable())
            assertFalse(session.isSystemPromptAvailable())
            assertFalse(session.isCachingAvailable())
            session.warmup()
            session.clearImplicitCaches()
        } finally {
            session.close()
        }
    }

    @Test
    fun nanoProofreader_available_cleansTranscriptAndPreservesFactsNamesAndNumbers() = runTest {
        val rawTranscript = "remind me to call dr chen at 3 30 pm about invoice 4812 for $1250"
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = """
                ```
                PROOFREAD: "Remind me to call Dr. Chen at 3:30 PM about invoice 4812 for $1,250."
                ```
            """.trimIndent()
        )
        val fallback = FakeProofreader(result = "fallback output")
        val proofreader = NanoProofreader(promptClient = client, fallback = fallback)

        assertEquals(CapabilityStatus.AVAILABLE, proofreader.status())

        val (cleaned, tier) = proofreader.proofreadWithTier(rawTranscript)
        assertEquals(Tier.FULL, tier)
        assertEquals(1, client.generateCalls)
        assertEquals(0, fallback.calls)
        assertEquals("Remind me to call Dr. Chen at 3:30 PM about invoice 4812 for $1,250.", cleaned)
        assertTrue(client.lastPrompt!!.contains("Preserve all facts, names, dates, times, numbers"))
        assertTrue(client.lastPrompt!!.contains(rawTranscript))

        // Unlabeled direct prose response is also accepted
        val plainClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = "Remind me to call Dr. Chen at 3:30 PM about invoice 4812 for $1,250."
        )
        val plainProofreader = NanoProofreader(promptClient = plainClient, fallback = fallback)
        assertEquals(
            "Remind me to call Dr. Chen at 3:30 PM about invoice 4812 for $1,250.",
            plainProofreader.proofread(rawTranscript)
        )
    }

    @Test
    fun nanoProofreader_unavailableOrDownloadable_demotesToDeterministicFallback() = runTest {
        val raw = "  buy oat milk and call sarah  "
        for (status in listOf(CapabilityStatus.UNAVAILABLE, CapabilityStatus.DOWNLOADABLE)) {
            val client = FakeGenAiPromptClient(currentStatus = status)
            val fallback = DeterministicTranscriptCleaner()
            val proofreader = NanoProofreader(promptClient = client, fallback = fallback)

            assertEquals(status, proofreader.status())
            val (cleaned, tier) = proofreader.proofreadWithTier(raw)
            assertEquals(Tier.CORE, tier)
            assertEquals("Buy oat milk and call sarah.", cleaned)
            assertEquals(0, client.generateCalls)
        }
    }

    @Test
    fun nanoProofreader_blankOrUnusableOrAlteredNumberOutput_demotesToFallback() = runTest {
        val rawWithNumbers = "call dr chen tomorrow at 2pm about invoice 4812 for $1250 and 15 bolts"
        val unusableOutputs = listOf(
            "",
            "   ",
            "```\n\n```",
            "...",
            "PROOFREAD:   ",
            "Sorry, I cannot comply with that request.",
            "I'm unable to proofread this voice note.",
            "As an AI language model, I cannot process this.",
            // Altered / dropped numbers must demote to preserve facts
            "PROOFREAD: Call Dr. Chen tomorrow at 2pm about invoice 4819 for $1,250 and 15 bolts.",
            "PROOFREAD: Call Dr. Chen tomorrow at 2pm about invoice 4812.",
            // Severely truncated output (< 1/4 length for >= 40 chars)
            "PROOFREAD: Call Chen."
        )

        for (badOutput in unusableOutputs) {
            val client = FakeGenAiPromptClient(
                currentStatus = CapabilityStatus.AVAILABLE,
                response = badOutput
            )
            val fallback = FakeProofreader(result = "Deterministic fallback result.")
            val proofreader = NanoProofreader(promptClient = client, fallback = fallback)

            val (result, tier) = proofreader.proofreadWithTier(rawWithNumbers)
            assertEquals("Expected fallback for bad output '$badOutput'", Tier.CORE, tier)
            assertEquals("Deterministic fallback result.", result)
            assertEquals(1, client.generateCalls)
            assertEquals(1, fallback.calls)
        }
    }

    @Test
    fun nanoProofreader_busy_throwsCapabilityBusyExceptionWithoutCallingFallback() = runTest {
        val busyStatusClient = FakeGenAiPromptClient(currentStatus = CapabilityStatus.BUSY)
        val fallback = FakeProofreader(result = "fallback")
        val busyStatusProofreader = NanoProofreader(promptClient = busyStatusClient, fallback = fallback)

        assertEquals(CapabilityStatus.BUSY, busyStatusProofreader.status())
        try {
            busyStatusProofreader.proofread("buy milk")
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
        val busyGenerateProofreader = NanoProofreader(promptClient = busyGenerateClient, fallback = fallback)
        try {
            busyGenerateProofreader.proofread("buy milk")
            fail("Expected CapabilityBusyException when generateText throws BUSY")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(1, busyGenerateClient.generateCalls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun nanoProofreader_blankInput_returnsEmptyWithoutCallingPromptClient() = runTest {
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            response = "Should not be called"
        )
        val proofreader = NanoProofreader(promptClient = client)

        val (result, tier) = proofreader.proofreadWithTier("   ")
        assertEquals("", result)
        assertEquals(Tier.CORE, tier)
        assertEquals(0, client.generateCalls)
    }
}
