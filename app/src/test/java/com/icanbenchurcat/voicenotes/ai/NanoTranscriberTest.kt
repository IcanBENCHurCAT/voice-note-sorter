// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FakeGenAiSpeechClient(
    var advancedStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    var basicStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    var advancedText: String = "advanced nano transcript",
    var basicText: String = "basic mlkit transcript",
    var recognizeException: Exception? = null
) : GenAiSpeechClient {
    var advancedRecognizeCalls = 0
        private set
    var basicRecognizeCalls = 0
        private set

    override suspend fun checkStatus(advancedMode: Boolean): CapabilityStatus =
        if (advancedMode) advancedStatus else basicStatus

    override suspend fun recognizeFile(audioPath: String, advancedMode: Boolean): String {
        if (advancedMode) advancedRecognizeCalls++ else basicRecognizeCalls++
        recognizeException?.let { throw it }
        return if (advancedMode) advancedText else basicText
    }
}

class FakeFallbackTranscriber(
    private val transcript: Transcript = Transcript("platform fallback transcript", Tier.CORE),
    private val currentStatus: CapabilityStatus = CapabilityStatus.AVAILABLE
) : Transcriber {
    var calls = 0
        private set

    override suspend fun status(): CapabilityStatus = currentStatus

    override suspend fun transcribe(audioPath: String): Transcript {
        calls++
        return transcript
    }
}

class NanoTranscriberTest {

    @Test
    fun advancedModeAvailable_returnsAvailableStatus_andFullTierTranscript() = runTest {
        val fakeClient = FakeGenAiSpeechClient(
            advancedStatus = CapabilityStatus.AVAILABLE,
            basicStatus = CapabilityStatus.AVAILABLE,
            advancedText = "transcribed by gemini nano advanced"
        )
        val fallback = FakeFallbackTranscriber()
        val nano = NanoTranscriber(fakeClient, fallback)

        assertEquals(CapabilityStatus.AVAILABLE, nano.status())

        val result = nano.transcribe("/data/local/tmp/note.m4a")
        assertEquals("transcribed by gemini nano advanced", result.text)
        assertEquals(Tier.FULL, result.tier)
        assertEquals(1, fakeClient.advancedRecognizeCalls)
        assertEquals(0, fakeClient.basicRecognizeCalls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun advancedUnavailable_basicAvailable_returnsCoreTierTranscript() = runTest {
        val fakeClient = FakeGenAiSpeechClient(
            advancedStatus = CapabilityStatus.UNAVAILABLE,
            basicStatus = CapabilityStatus.AVAILABLE,
            basicText = "transcribed by mlkit basic"
        )
        val fallback = FakeFallbackTranscriber()
        val nano = NanoTranscriber(fakeClient, fallback)

        assertEquals(CapabilityStatus.AVAILABLE, nano.status())

        val result = nano.transcribe("/data/local/tmp/note.m4a")
        assertEquals("transcribed by mlkit basic", result.text)
        assertEquals(Tier.CORE, result.tier)
        assertEquals(0, fakeClient.advancedRecognizeCalls)
        assertEquals(1, fakeClient.basicRecognizeCalls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun downloadableStatus_reportsDownloadable_andFallsThroughToPlatformTranscriber() = runTest {
        val fakeClient = FakeGenAiSpeechClient(
            advancedStatus = CapabilityStatus.DOWNLOADABLE,
            basicStatus = CapabilityStatus.UNAVAILABLE
        )
        val fallback = FakeFallbackTranscriber(
            transcript = Transcript("platform speech transcript", Tier.CORE)
        )
        val nano = NanoTranscriber(fakeClient, fallback)

        assertEquals(CapabilityStatus.DOWNLOADABLE, nano.status())

        val result = nano.transcribe("/data/local/tmp/note.m4a")
        assertEquals("platform speech transcript", result.text)
        assertEquals(Tier.CORE, result.tier)
        assertEquals(0, fakeClient.advancedRecognizeCalls)
        assertEquals(0, fakeClient.basicRecognizeCalls)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun unavailableStatus_fallsThroughExplicitly_andPreservesActualTier() = runTest {
        val fakeClient = FakeGenAiSpeechClient(
            advancedStatus = CapabilityStatus.UNAVAILABLE,
            basicStatus = CapabilityStatus.UNAVAILABLE
        )
        val fallback = FakeFallbackTranscriber(
            transcript = Transcript("platform speech transcript", Tier.CORE)
        )
        val nano = NanoTranscriber(fakeClient, fallback)

        assertEquals(CapabilityStatus.UNAVAILABLE, nano.status())

        val result = nano.transcribe("/data/local/tmp/note.m4a")
        assertEquals("platform speech transcript", result.text)
        assertEquals(Tier.CORE, result.tier)
        assertEquals(1, fallback.calls)

        // Tier 1 path: when fallback is NullTranscriber, Transcript.tier is MINIMAL
        val nanoTier1 = NanoTranscriber(fakeClient, NullTranscriber)
        val tier1Result = nanoTier1.transcribe("/data/local/tmp/note.m4a")
        assertEquals("", tier1Result.text)
        assertEquals(Tier.MINIMAL, tier1Result.tier)
    }

    @Test
    fun busyOutcome_fromStatusOrRecognize_surfacesCapabilityBusyException() = runTest {
        val busyStatusClient = FakeGenAiSpeechClient(
            advancedStatus = CapabilityStatus.BUSY,
            basicStatus = CapabilityStatus.AVAILABLE
        )
        val fallback = FakeFallbackTranscriber()
        val nanoBusyStatus = NanoTranscriber(busyStatusClient, fallback)

        assertEquals(CapabilityStatus.BUSY, nanoBusyStatus.status())
        try {
            nanoBusyStatus.transcribe("/data/local/tmp/note.m4a")
            fail("Expected CapabilityBusyException when status is BUSY")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(0, fallback.calls)

        // Runtime BUSY during recognition call
        val busyRecognizeClient = FakeGenAiSpeechClient(
            advancedStatus = CapabilityStatus.AVAILABLE,
            recognizeException = CapabilityBusyException()
        )
        val nanoBusyCall = NanoTranscriber(busyRecognizeClient, fallback)
        try {
            nanoBusyCall.transcribe("/data/local/tmp/note.m4a")
            fail("Expected CapabilityBusyException when recognizeFile throws CapabilityBusyException")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(1, busyRecognizeClient.advancedRecognizeCalls)
    }
}
