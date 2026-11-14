// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException

/**
 * Seam over the ML Kit GenAI Prompt API client so that [NanoSummarizer]
 * can be unit-tested across AVAILABLE, DOWNLOADABLE, UNAVAILABLE, and BUSY
 * outcomes without requiring AICore hardware.
 */
interface GenAiSummarizerClient {
    suspend fun checkStatus(): CapabilityStatus
    suspend fun generateTitleAndSummary(transcript: String): TitleSummary
}

/**
 * Production [GenAiSummarizerClient] backed by `com.google.mlkit:genai-prompt`.
 * ML Kit Prompt imports are isolated in this file (see AGENTS.md: interfaces first).
 */
object MlKitGenAiSummarizerClient : GenAiSummarizerClient {

    private const val TEMPERATURE = 0.2f
    private const val MAX_OUTPUT_TOKENS = 160

    override suspend fun checkStatus(): CapabilityStatus {
        var model: GenerativeModel? = null
        return try {
            model = Generation.getClient()
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> CapabilityStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> CapabilityStatus.DOWNLOADABLE
                else -> CapabilityStatus.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            if (isBusyOrQuotaError(e)) {
                CapabilityStatus.BUSY
            } else {
                CapabilityStatus.UNAVAILABLE
            }
        } catch (_: Throwable) {
            CapabilityStatus.UNAVAILABLE
        } finally {
            runCatching { model?.close() }
        }
    }

    override suspend fun generateTitleAndSummary(transcript: String): TitleSummary {
        if (transcript.isBlank()) {
            return TitleSummary("Untitled note", "")
        }
        var model: GenerativeModel? = null
        return try {
            model = Generation.getClient()
            val request = generateContentRequest(TextPart(buildPrompt(transcript))) {
                temperature = TEMPERATURE
                candidateCount = 1
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
            val response = model.generateContent(request)
            val rawText = response.candidates.firstOrNull()?.text.orEmpty()
            parseTitleSummaryResponse(rawText)
        } catch (e: GenAiException) {
            if (isBusyOrQuotaError(e)) {
                throw CapabilityBusyException()
            }
            throw e
        } finally {
            runCatching { model?.close() }
        }
    }

    private fun buildPrompt(transcript: String): String = """
        Summarize the voice note transcript below into a concise topical title and a 1-2 sentence summary.
        Ignore spoken filler (such as "um", "so now", or "this is a test voice note").
        Respond using exactly these two lines and nothing else:
        TITLE: <short topical title under 60 chars>
        SUMMARY: <1-2 sentence summary>

        Transcript:
        $transcript
    """.trimIndent()

    private fun isBusyOrQuotaError(e: GenAiException): Boolean =
        e.errorCode == GenAiException.ErrorCode.BUSY ||
            e.errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED

    private val titlePrefixRegex = Regex(
        "^(?:[-*]\\s*)?(?:\\*\\*)?title(?:\\*\\*)?\\s*[:\\-]\\s*(?:\\*\\*)?\\s*",
        RegexOption.IGNORE_CASE
    )
    private val summaryPrefixRegex = Regex(
        "^(?:[-*]\\s*)?(?:\\*\\*)?summary(?:\\*\\*)?\\s*[:\\-]\\s*(?:\\*\\*)?\\s*",
        RegexOption.IGNORE_CASE
    )

    internal fun parseTitleSummaryResponse(rawText: String): TitleSummary {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return TitleSummary("", "")
        }

        var parsedTitle: String? = null
        val summaryLines = mutableListOf<String>()
        var inSummary = false

        for (line in lines) {
            when {
                parsedTitle == null && titlePrefixRegex.containsMatchIn(line) -> {
                    parsedTitle = line.replaceFirst(titlePrefixRegex, "")
                        .trim('"', '\'', '*', ' ')
                        .trimEnd('.', '!', '?', ',', ';', ':', '-', ' ')
                    inSummary = false
                }
                summaryPrefixRegex.containsMatchIn(line) -> {
                    val remainder = line.replaceFirst(summaryPrefixRegex, "")
                        .trim('*', ' ')
                    if (remainder.isNotEmpty()) {
                        summaryLines += remainder
                    }
                    inSummary = true
                }
                inSummary -> {
                    summaryLines += line
                }
            }
        }

        val rawTitle = parsedTitle.orEmpty().trim()
        val title = if (rawTitle.isNotBlank()) {
            ExtractiveSummarizer.truncateAtWordBoundary(rawTitle, ExtractiveSummarizer.MAX_TITLE_CHARS)
        } else {
            ""
        }
        val summary = summaryLines.joinToString(" ").trim()
        return TitleSummary(title = title, summary = summary)
    }
}

/**
 * Tier 3 summarization via Gemini Nano Prompt API when available,
 * demoting cleanly to [ExtractiveSummarizer] (Tier 2) on unavailability
 * or surfacing [CapabilityBusyException] when AICore is busy/over quota.
 */
class NanoSummarizer(
    private val client: GenAiSummarizerClient = MlKitGenAiSummarizerClient,
    private val fallback: Summarizer = ExtractiveSummarizer()
) : Summarizer {

    override suspend fun status(): CapabilityStatus = client.checkStatus()

    override suspend fun summarize(transcript: String): TitleSummary {
        return when (client.checkStatus()) {
            CapabilityStatus.AVAILABLE -> {
                if (transcript.isBlank()) {
                    return fallback.summarize(transcript)
                }
                val generated = client.generateTitleAndSummary(transcript)
                if (generated.title.isBlank()) {
                    CapabilityProbe.logDemotion("summarizer", Tier.FULL, Tier.CORE)
                    fallback.summarize(transcript)
                } else {
                    val truncatedTitle = ExtractiveSummarizer.truncateAtWordBoundary(
                        generated.title,
                        ExtractiveSummarizer.MAX_TITLE_CHARS
                    )
                    val finalSummary = generated.summary.trim().ifBlank {
                        fallback.summarize(transcript).summary
                    }
                    TitleSummary(title = truncatedTitle, summary = finalSummary)
                }
            }
            CapabilityStatus.BUSY -> throw CapabilityBusyException()
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.UNAVAILABLE -> {
                CapabilityProbe.logDemotion("summarizer", Tier.FULL, Tier.CORE)
                fallback.summarize(transcript)
            }
        }
    }
}
