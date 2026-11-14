// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.util.Locale

/**
 * Seam over the ML Kit GenAI Prompt API so [NanoSummarizer],
 * [NanoActionExtractor], and [NanoBucketer] can be deterministically
 * unit-tested across AVAILABLE, DOWNLOADABLE, UNAVAILABLE, and BUSY
 * outcomes without requiring AICore hardware.
 */
interface GenAiPromptClient {
    suspend fun checkStatus(): CapabilityStatus
    suspend fun generateText(prompt: String): String
}

/**
 * Production [GenAiPromptClient] backed by `com.google.mlkit:genai-prompt`.
 * All ML Kit GenAI Prompt imports in the repository are isolated in this file.
 */
object MlKitGenAiPromptClient : GenAiPromptClient {

    override suspend fun checkStatus(): CapabilityStatus {
        val model = try {
            Generation.getClient()
        } catch (_: Throwable) {
            return CapabilityStatus.UNAVAILABLE
        }
        return try {
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
            runCatching { model.close() }
        }
    }

    override suspend fun generateText(prompt: String): String {
        val model = try {
            Generation.getClient()
        } catch (e: GenAiException) {
            if (isBusyOrQuotaError(e)) {
                throw CapabilityBusyException()
            }
            throw e
        }
        return try {
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = 0.2f
                candidateCount = 1
                maxOutputTokens = 256
            }
            val response = model.generateContent(request)
            response.candidates.firstOrNull()?.text.orEmpty().trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CapabilityBusyException) {
            throw e
        } catch (e: GenAiException) {
            if (isBusyOrQuotaError(e)) {
                throw CapabilityBusyException()
            }
            throw e
        } finally {
            runCatching { model.close() }
        }
    }

    private fun isBusyOrQuotaError(e: GenAiException): Boolean =
        e.errorCode == GenAiException.ErrorCode.BUSY ||
            e.errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
}

/**
 * Tier 3 generative summarizer and title generator backed by Gemini Nano
 * via [GenAiPromptClient], falling back to [ExtractiveSummarizer] when
 * the foundation model is unavailable, downloadable, or returns unparseable
 * output. Throws [CapabilityBusyException] on BUSY/quota so the pipeline
 * records the demotion explicitly.
 */
class NanoSummarizer(
    private val promptClient: GenAiPromptClient = MlKitGenAiPromptClient,
    private val fallback: Summarizer = ExtractiveSummarizer()
) : Summarizer {

    override suspend fun status(): CapabilityStatus = promptClient.checkStatus()

    override suspend fun summarize(transcript: String): TitleSummary =
        summarizeWithTier(transcript).first

    internal suspend fun summarizeWithTier(transcript: String): Pair<TitleSummary, Tier> {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) {
            return fallback.summarize(transcript) to Tier.CORE
        }

        when (promptClient.checkStatus()) {
            CapabilityStatus.AVAILABLE -> Unit
            CapabilityStatus.BUSY -> throw CapabilityBusyException()
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.UNAVAILABLE -> {
                CapabilityProbe.logDemotion("summarizer", Tier.FULL, Tier.CORE)
                return fallback.summarize(transcript) to Tier.CORE
            }
        }

        val raw = promptClient.generateText(buildPrompt(trimmed))
        val parsed = parseResponse(raw)
        if (parsed == null) {
            CapabilityProbe.logDemotion("summarizer", Tier.FULL, Tier.CORE)
            return fallback.summarize(transcript) to Tier.CORE
        }
        return parsed to Tier.FULL
    }

    companion object {
        private const val MAX_TITLE_CHARS = 60
        private const val MAX_PROMPT_TRANSCRIPT_CHARS = 8000

        private val titleLineRegex = Regex(
            "^(?:[-*]|\\d+[.)])?\\s*(?:\\*\\*)?TITLE(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        )
        private val summaryLineRegex = Regex(
            "^(?:[-*]|\\d+[.)])?\\s*(?:\\*\\*)?SUMMARY(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*(.*)$",
            RegexOption.IGNORE_CASE
        )

        internal fun buildPrompt(transcript: String): String {
            val bounded = transcript.trim().take(MAX_PROMPT_TRANSCRIPT_CHARS)
            return buildString {
                appendLine("Summarize this voice note transcript.")
                appendLine("Respond in exactly two lines:")
                appendLine("Title: <concise topical title, max 60 characters, no quotes>")
                appendLine("Summary: <1-2 sentence summary capturing the key points>")
                appendLine()
                appendLine("Transcript:")
                append(bounded)
            }
        }

        internal fun parseResponse(raw: String): TitleSummary? {
            val cleaned = stripCodeFences(raw)
            if (cleaned.isBlank()) return null

            var rawTitle: String? = null
            val summaryLines = mutableListOf<String>()
            var inSummary = false

            for (line in cleaned.lines()) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) continue

                val titleMatch = titleLineRegex.matchEntire(trimmedLine)
                if (titleMatch != null && !inSummary) {
                    rawTitle = titleMatch.groupValues[1]
                    continue
                }

                val summaryMatch = summaryLineRegex.matchEntire(trimmedLine)
                if (summaryMatch != null) {
                    inSummary = true
                    val firstPart = summaryMatch.groupValues[1].trim()
                    if (firstPart.isNotEmpty()) {
                        summaryLines += firstPart
                    }
                    continue
                }

                if (inSummary) {
                    summaryLines += trimmedLine
                }
            }

            val title = truncateTitleOnWordBoundary(rawTitle.orEmpty(), MAX_TITLE_CHARS)
            val summary = cleanProseValue(summaryLines.joinToString(" "))
            if (title.isBlank() || summary.isBlank()) {
                return null
            }
            return TitleSummary(title = title, summary = summary)
        }

        internal fun truncateTitleOnWordBoundary(rawTitle: String, maxLength: Int = MAX_TITLE_CHARS): String {
            val normalized = cleanProseValue(rawTitle)
                .trimEnd('.', '!', '?', ',', ';', ':', '-')
                .trim()
            if (normalized.isEmpty()) return ""
            if (normalized.length <= maxLength) return normalized

            val cutoff = normalized.lastIndexOf(' ', startIndex = maxLength)
            val bounded = if (cutoff > 0) {
                normalized.substring(0, cutoff)
            } else {
                normalized.substring(0, maxLength)
            }
            return bounded.trimEnd('.', '!', '?', ',', ';', ':', '-', ' ')
        }
    }
}

/**
 * Tier 3 generative action item extractor backed by Gemini Nano via
 * [GenAiPromptClient] with deterministic due-date resolution through
 * [PlatformDateParser]. Falls back to [HeuristicActionExtractor] when
 * unavailable, downloadable, or unparseable, and propagates
 * [CapabilityBusyException] on BUSY/quota.
 */
class NanoActionExtractor(
    private val promptClient: GenAiPromptClient = MlKitGenAiPromptClient,
    private val fallback: ActionExtractor = HeuristicActionExtractor(),
    private val clock: Clock = Clock.systemDefaultZone()
) : ActionExtractor {

    override suspend fun status(): CapabilityStatus = promptClient.checkStatus()

    override suspend fun extract(transcript: String): List<ExtractedAction> =
        extractWithTier(transcript).first

    internal suspend fun extractWithTier(transcript: String): Pair<List<ExtractedAction>, Tier> {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) {
            return emptyList<ExtractedAction>() to Tier.CORE
        }

        when (promptClient.checkStatus()) {
            CapabilityStatus.AVAILABLE -> Unit
            CapabilityStatus.BUSY -> throw CapabilityBusyException()
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.UNAVAILABLE -> {
                CapabilityProbe.logDemotion("actions", Tier.FULL, Tier.CORE)
                return fallback.extract(transcript) to Tier.CORE
            }
        }

        val raw = promptClient.generateText(buildPrompt(trimmed))
        val parsed = parseResponse(raw, clock)
        if (parsed == null) {
            CapabilityProbe.logDemotion("actions", Tier.FULL, Tier.CORE)
            return fallback.extract(transcript) to Tier.CORE
        }
        return parsed to Tier.FULL
    }

    companion object {
        private const val MAX_PROMPT_TRANSCRIPT_CHARS = 8000

        private val emptyResponses = setOf(
            "none",
            "no actions",
            "no action items",
            "none found",
            "[]"
        )
        private val actionLineRegex = Regex(
            "^(?:[-*]|\\d+[.)])?\\s*(?:\\*\\*)?ACTION(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        )
        private val dueSplitRegex = Regex(
            "\\s*\\|\\s*(?:\\*\\*)?DUE(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*",
            RegexOption.IGNORE_CASE
        )
        private val emptyDueTokens = setOf("none", "n/a", "na", "null", "-")

        internal fun buildPrompt(transcript: String): String {
            val bounded = transcript.trim().take(MAX_PROMPT_TRANSCRIPT_CHARS)
            return buildString {
                appendLine("Extract concrete action items or tasks from this voice note transcript.")
                appendLine("Respond with one line per action item using this exact format:")
                appendLine("ACTION: <task description> | DUE: <date or time phrase from transcript, or NONE>")
                appendLine("If there are no action items, respond with exactly:")
                appendLine("NONE")
                appendLine()
                appendLine("Transcript:")
                append(bounded)
            }
        }

        internal fun parseResponse(raw: String, clock: Clock): List<ExtractedAction>? {
            val cleaned = stripCodeFences(raw)
            if (cleaned.isBlank()) return null

            val normalizedWhole = cleanProseValue(cleaned)
                .trimEnd('.', '!', '?')
                .lowercase(Locale.ROOT)
            if (normalizedWhole in emptyResponses) {
                return emptyList()
            }

            val items = mutableListOf<ExtractedAction>()
            val seenTexts = mutableSetOf<String>()
            for (line in cleaned.lines()) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) continue

                val match = actionLineRegex.matchEntire(trimmedLine) ?: continue
                val payload = match.groupValues[1].trim()
                if (payload.isEmpty()) continue

                val parts = dueSplitRegex.split(payload, limit = 2)
                val actionText = cleanProseValue(parts[0])
                if (actionText.isBlank()) continue
                if (!seenTexts.add(actionText)) continue

                val duePhrase = parts.getOrNull(1)?.let(::cleanProseValue).orEmpty()
                val dueAtEpochMs = if (
                    duePhrase.isNotBlank() &&
                    duePhrase.lowercase(Locale.ROOT) !in emptyDueTokens
                ) {
                    PlatformDateParser.parseDueAtEpochMs(duePhrase, clock)
                        ?: PlatformDateParser.parseDueAtEpochMs(actionText, clock)
                } else {
                    PlatformDateParser.parseDueAtEpochMs(actionText, clock)
                }

                items += ExtractedAction(
                    text = actionText,
                    dueAtEpochMs = dueAtEpochMs
                )
            }

            if (items.isEmpty()) {
                return null
            }
            return items
        }
    }
}

/**
 * Tier 3 generative bucketer and tagger backed by Gemini Nano via
 * [GenAiPromptClient]. Classifies transcripts into a bucket from the
 * active [BucketProfile] plus concise lowercase tags, returning
 * `Tier.FULL`. Falls back to [HeuristicBucketer] when unavailable,
 * downloadable, or unparseable, and propagates [CapabilityBusyException]
 * on BUSY/quota.
 */
class NanoBucketer(
    private val promptClient: GenAiPromptClient = MlKitGenAiPromptClient,
    private val fallback: Bucketer = HeuristicBucketer()
) : Bucketer {

    override suspend fun status(): CapabilityStatus = promptClient.checkStatus()

    override suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment {
        if (transcript.isBlank() || profile.buckets.isEmpty()) {
            return fallback.assign(transcript, profile)
        }

        when (promptClient.checkStatus()) {
            CapabilityStatus.AVAILABLE -> Unit
            CapabilityStatus.BUSY -> throw CapabilityBusyException()
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.UNAVAILABLE -> {
                CapabilityProbe.logDemotion("bucketer", Tier.FULL, Tier.CORE)
                return fallback.assign(transcript, profile)
            }
        }

        val raw = promptClient.generateText(buildPrompt(transcript, profile))
        val parsed = parseResponse(raw, profile)
        if (parsed == null) {
            CapabilityProbe.logDemotion("bucketer", Tier.FULL, Tier.CORE)
            return fallback.assign(transcript, profile)
        }
        return parsed
    }

    companion object {
        private const val MAX_PROMPT_TRANSCRIPT_CHARS = 8000
        private const val MAX_TAGS = 5

        private val bucketLineRegex = Regex(
            "^(?:[-*]|\\d+[.)])?\\s*(?:\\*\\*)?BUCKET(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        )
        private val tagsLineRegex = Regex(
            "^(?:[-*]|\\d+[.)])?\\s*(?:\\*\\*)?TAGS(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*(.*)$",
            RegexOption.IGNORE_CASE
        )
        private val emptyTagTokens = setOf("none", "n/a", "na", "null", "-")

        internal fun buildPrompt(transcript: String, profile: BucketProfile): String {
            val bounded = transcript.trim().take(MAX_PROMPT_TRANSCRIPT_CHARS)
            return buildString {
                appendLine("Classify this voice note transcript into exactly one bucket from the allowed list and generate up to 3 concise lowercase tags.")
                appendLine("Allowed buckets:")
                profile.buckets.forEach { def ->
                    appendLine("- ${def.kind} (${def.name})")
                }
                appendLine()
                appendLine("Respond in exactly two lines:")
                appendLine("BUCKET: <one bucket kind from the allowed list above>")
                appendLine("TAGS: <comma-separated concise lowercase tags, or NONE>")
                appendLine()
                appendLine("Transcript:")
                append(bounded)
            }
        }

        internal fun parseResponse(raw: String, profile: BucketProfile): BucketAssignment? {
            val cleaned = stripCodeFences(raw)
            if (cleaned.isBlank()) return null

            var rawBucket: String? = null
            var rawTags: String? = null

            for (line in cleaned.lines()) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) continue

                val bucketMatch = bucketLineRegex.matchEntire(trimmedLine)
                if (bucketMatch != null && rawBucket == null) {
                    rawBucket = bucketMatch.groupValues[1]
                    continue
                }

                val tagsMatch = tagsLineRegex.matchEntire(trimmedLine)
                if (tagsMatch != null && rawTags == null) {
                    rawTags = tagsMatch.groupValues[1]
                }
            }

            val candidateBucket = cleanProseValue(rawBucket.orEmpty())
                .substringBefore(" (")
                .trim()
            if (candidateBucket.isEmpty()) return null

            val matchedBucket = profile.buckets.firstOrNull {
                it.kind.equals(candidateBucket, ignoreCase = true)
            } ?: profile.buckets.firstOrNull {
                it.name.equals(candidateBucket, ignoreCase = true)
            } ?: return null

            val tags = parseTags(rawTags.orEmpty())
            val isInbox = matchedBucket.kind == "INBOX"
            return BucketAssignment(
                bucketKind = matchedBucket.kind,
                tags = tags,
                tier = Tier.FULL,
                confidence = if (isInbox) 0.5f else 0.9f,
                score = if (isInbox) 0 else 1,
                needsReview = isInbox
            )
        }

        private fun parseTags(rawTags: String): List<String> {
            val cleaned = cleanProseValue(rawTags)
            if (cleaned.isBlank() || cleaned.lowercase(Locale.ROOT) in emptyTagTokens) {
                return emptyList()
            }
            return cleaned.split(",")
                .map { token ->
                    token.trim()
                        .removePrefix("#")
                        .lowercase(Locale.ROOT)
                        .replace(Regex("[^a-z0-9-]+"), "-")
                        .trim('-')
                }
                .filter { it.isNotEmpty() && it !in emptyTagTokens }
                .distinct()
                .take(MAX_TAGS)
        }
    }
}

private fun stripCodeFences(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("```")) return trimmed
    val lines = trimmed.lines()
    if (lines.size < 2) return trimmed.removePrefix("```").removeSuffix("```").trim()
    val startIndex = 1
    val endIndex = if (lines.last().trim().startsWith("```")) lines.lastIndex else lines.size
    return lines.subList(startIndex, endIndex).joinToString("\n").trim()
}

private fun cleanProseValue(raw: String): String {
    var text = raw.trim().replace(Regex("\\s+"), " ")
    text = text.removePrefix("**").removeSuffix("**").trim()
    if ((text.startsWith("\"") && text.endsWith("\"")) ||
        (text.startsWith("'") && text.endsWith("'")) ||
        (text.startsWith("`") && text.endsWith("`"))
    ) {
        if (text.length >= 2) {
            text = text.substring(1, text.length - 1).trim()
        }
    }
    return text
}
