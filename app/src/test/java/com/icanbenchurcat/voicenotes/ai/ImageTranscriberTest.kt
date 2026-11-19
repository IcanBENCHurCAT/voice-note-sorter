// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import com.google.mlkit.genai.common.GenAiException
import com.icanbenchurcat.voicenotes.fixtures.FakeImageTranscriber
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeBundledOcrEngine(
    var available: Boolean = true,
    var exception: Exception? = null,
    private val responder: (File) -> String = { "OCR text from ${it.name}" }
) : BundledOcrEngine {
    val recognizedFiles = mutableListOf<String>()

    override suspend fun isAvailable(): Boolean = available

    override suspend fun recognizeText(imageFile: File): String {
        recognizedFiles += imageFile.absolutePath
        exception?.let { throw it }
        return responder(imageFile)
    }
}

class ImageTranscriberTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun nanoImageTranscriber_available_returnsFullTierTranscriptWithoutFallback() = runTest {
        val imageFile = tempFolder.newFile("whiteboard.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val promptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            imageResponse = """
                ```
                Sprint Action Items:
                - Buy coffee beans
                - Email Alex by Friday
                ```
            """.trimIndent()
        )
        val fallback = FakeImageTranscriber(result = Transcript("fallback ocr", Tier.CORE))
        val transcriber = NanoImageTranscriber(promptClient = promptClient, fallback = fallback)

        assertEquals(CapabilityStatus.AVAILABLE, transcriber.status())

        val result = transcriber.transcribeImage(imageFile.absolutePath)
        assertEquals("Sprint Action Items:\n- Buy coffee beans\n- Email Alex by Friday", result.text)
        assertEquals(Tier.FULL, result.tier)
        assertEquals(1, promptClient.imageGenerateCalls)
        assertEquals(imageFile.absolutePath, promptClient.lastImagePath)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun nanoImageTranscriber_unavailableDownloadableOrBlank_fallsBackToTier2Ocr() = runTest {
        val imageFile = tempFolder.newFile("notebook.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val ocrEngine = FakeBundledOcrEngine(available = true) { "Handwritten grocery list: oat milk, bread." }
        val tier2Fallback = MlKitOcrImageTranscriber(ocrEngine)

        for (nonReadyStatus in listOf(CapabilityStatus.UNAVAILABLE, CapabilityStatus.DOWNLOADABLE)) {
            val promptClient = FakeGenAiPromptClient(currentStatus = nonReadyStatus)
            val nano = NanoImageTranscriber(promptClient = promptClient, fallback = tier2Fallback)

            assertEquals(nonReadyStatus, nano.status())
            val result = nano.transcribeImage(imageFile.absolutePath)
            assertEquals("Handwritten grocery list: oat milk, bread.", result.text)
            assertEquals(Tier.CORE, result.tier)
            assertEquals(0, promptClient.imageGenerateCalls)
        }

        // Blank Nano output also falls back to Tier 2 bundled OCR
        val blankPromptClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            imageResponse = "   \n  "
        )
        val nanoBlank = NanoImageTranscriber(promptClient = blankPromptClient, fallback = tier2Fallback)
        val blankFallbackResult = nanoBlank.transcribeImage(imageFile.absolutePath)
        assertEquals("Handwritten grocery list: oat milk, bread.", blankFallbackResult.text)
        assertEquals(Tier.CORE, blankFallbackResult.tier)
        assertEquals(1, blankPromptClient.imageGenerateCalls)
    }

    @Test
    fun nanoImageTranscriber_busyStatusOrException_throwsCapabilityBusyExceptionWithoutFallback() = runTest {
        val imageFile = tempFolder.newFile("busy.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val fallback = FakeImageTranscriber(result = Transcript("should not run", Tier.CORE))

        val busyStatusClient = FakeGenAiPromptClient(currentStatus = CapabilityStatus.BUSY)
        val nanoBusyStatus = NanoImageTranscriber(promptClient = busyStatusClient, fallback = fallback)
        assertEquals(CapabilityStatus.BUSY, nanoBusyStatus.status())
        try {
            nanoBusyStatus.transcribeImage(imageFile.absolutePath)
            fail("Expected CapabilityBusyException when status is BUSY")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(0, busyStatusClient.imageGenerateCalls)
        assertEquals(0, fallback.calls)

        val busyCallClient = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            imageGenerateException = CapabilityBusyException()
        )
        val nanoBusyCall = NanoImageTranscriber(promptClient = busyCallClient, fallback = fallback)
        try {
            nanoBusyCall.transcribeImage(imageFile.absolutePath)
            fail("Expected CapabilityBusyException when generateTextFromImage throws BUSY")
        } catch (_: CapabilityBusyException) {
            // Expected
        }
        assertEquals(1, busyCallClient.imageGenerateCalls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun nanoImageTranscriber_nonBusyGenAiException_fallsBackToTier2Ocr() = runTest {
        val imageFile = tempFolder.newFile("unsupported_multimodal.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        val client = FakeGenAiPromptClient(
            currentStatus = CapabilityStatus.AVAILABLE,
            imageGenerateException = GenAiException("Multimodal not supported", null, GenAiException.ErrorCode.NOT_SUPPORTED)
        )
        val fallback = FakeImageTranscriber(result = Transcript("Bundled OCR fallback", Tier.CORE))
        val nano = NanoImageTranscriber(promptClient = client, fallback = fallback)

        val result = nano.transcribeImage(imageFile.absolutePath)
        assertEquals("Bundled OCR fallback", result.text)
        assertEquals(Tier.CORE, result.tier)
        assertEquals(1, client.imageGenerateCalls)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun mlKitOcrImageTranscriber_available_transcribesDistinctFilesAtCoreTier() = runTest {
        val fileA = tempFolder.newFile("receipt_a.jpg").apply { writeText("Total: $14.50 Coffee") }
        val fileB = tempFolder.newFile("notes_b.png").apply { writeText("Remind me to call Sam tomorrow") }

        val engine = FakeBundledOcrEngine(available = true) { file ->
            file.readText(Charsets.UTF_8)
        }
        val ocr = MlKitOcrImageTranscriber(engine)

        assertEquals(CapabilityStatus.AVAILABLE, ocr.status())

        val resultA = ocr.transcribeImage(fileA.absolutePath)
        val resultB = ocr.transcribeImage(fileB.absolutePath)

        assertNotEquals(resultA.text, resultB.text)
        assertEquals("Total: $14.50 Coffee", resultA.text)
        assertEquals(Tier.CORE, resultA.tier)
        assertEquals("Remind me to call Sam tomorrow", resultB.text)
        assertEquals(Tier.CORE, resultB.tier)
        assertEquals(listOf(fileA.absolutePath, fileB.absolutePath), engine.recognizedFiles)
    }

    @Test
    fun mlKitOcrImageTranscriber_blankOrUnavailable_demotesToMinimalAndValidatesFile() = runTest {
        val imageFile = tempFolder.newFile("blank_page.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val blankEngine = FakeBundledOcrEngine(available = true) { "   \n  " }
        val blankOcr = MlKitOcrImageTranscriber(blankEngine)
        val blankResult = blankOcr.transcribeImage(imageFile.absolutePath)
        assertEquals("", blankResult.text)
        assertEquals(Tier.MINIMAL, blankResult.tier)

        val unavailableEngine = FakeBundledOcrEngine(available = false)
        val unavailableOcr = MlKitOcrImageTranscriber(unavailableEngine)
        assertEquals(CapabilityStatus.UNAVAILABLE, unavailableOcr.status())
        val unavailableResult = unavailableOcr.transcribeImage(imageFile.absolutePath)
        assertEquals("", unavailableResult.text)
        assertEquals(Tier.MINIMAL, unavailableResult.tier)
        assertEquals(0, unavailableEngine.recognizedFiles.size)

        val missingPath = File(tempFolder.root, "missing.jpg").absolutePath
        try {
            blankOcr.transcribeImage(missingPath)
            fail("Expected IllegalArgumentException for missing image file")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        val emptyFile = tempFolder.newFile("empty.jpg")
        try {
            blankOcr.transcribeImage(emptyFile.absolutePath)
            fail("Expected IllegalArgumentException for empty image file")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun nullImageTranscriber_tier1_returnsUnavailableAndBlankMinimalTranscript() = runTest {
        assertEquals(CapabilityStatus.UNAVAILABLE, NullImageTranscriber.status())
        val result = NullImageTranscriber.transcribeImage("/any/image/path.jpg")
        assertEquals("", result.text)
        assertEquals(Tier.MINIMAL, result.tier)
    }
}
