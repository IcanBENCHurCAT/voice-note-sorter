// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide
import com.icanbenchurcat.voicenotes.capture.ImageFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.util.Locale
import kotlin.reflect.KClass

/**
 * Structured request payload passed across [GenAiPromptClient] and
 * [GenAiPromptSession]. Maps to ML Kit `SystemInstruction`, `PromptPrefix`,
 * and `GenerateContentRequest` inside [MlKitGenAiPromptClient] so no
 * `com.google.mlkit.genai.*` types leak outside this file.
 */
data class PromptRequest(
    val prompt: String,
    val systemInstruction: String? = null,
    val promptPrefix: String? = null,
    val temperature: Float = 0.2f,
    val candidateCount: Int = 1,
    val maxOutputTokens: Int = 256
)

/**
 * Reusable prompt session scoping a single underlying model instance across
 * the summarization, action extraction, and bucketing stages of a note so
 * model warmup and prefix caches ([PromptRequest.promptPrefix] +
 * [PromptRequest.systemInstruction]) can be reused and cleaned up together.
 */
interface GenAiPromptSession : AutoCloseable {
    suspend fun checkStatus(): CapabilityStatus
    suspend fun isStructuredOutputAvailable(): Boolean = false
    suspend fun isSystemPromptAvailable(): Boolean = false
    suspend fun isCachingAvailable(): Boolean = false
    suspend fun warmup() {}
    suspend fun clearImplicitCaches() {}
    suspend fun <T : Any> generateTyped(request: PromptRequest, outputClass: KClass<T>): T? = null
    suspend fun generateText(request: PromptRequest): String
    suspend fun generateText(prompt: String): String = generateText(PromptRequest(prompt = prompt))
    override fun close() {}
}

/**
 * Seam over the ML Kit GenAI Prompt API so [NanoSummarizer],
 * [NanoActionExtractor], [NanoBucketer], [NanoProofreader], and
 * [NanoImageTranscriber] can be deterministically unit-tested across
 * AVAILABLE, DOWNLOADABLE, UNAVAILABLE, and BUSY outcomes without
 * requiring AICore hardware.
 */
interface GenAiPromptClient {
    suspend fun checkStatus(): CapabilityStatus
    suspend fun generateText(prompt: String): String
    suspend fun generateText(request: PromptRequest): String = generateText(request.prompt)
    suspend fun generateText(prompt: String, maxOutputTokens: Int): String =
        generateText(PromptRequest(prompt = prompt, maxOutputTokens = maxOutputTokens))
    suspend fun generateTextFromImage(imagePath: String, prompt: String): String = ""
    suspend fun <T : Any> generateTyped(request: PromptRequest, outputClass: KClass<T>): T? = null
    suspend fun isStructuredOutputAvailable(): Boolean = false
    suspend fun isSystemPromptAvailable(): Boolean = false
    suspend fun isCachingAvailable(): Boolean = false
    suspend fun download(): Boolean = false
    fun openSession(): GenAiPromptSession = StatelessGenAiPromptSession(this)
}

private class StatelessGenAiPromptSession(
    private val client: GenAiPromptClient
) : GenAiPromptSession {
    override suspend fun checkStatus(): CapabilityStatus = client.checkStatus()

    override suspend fun isStructuredOutputAvailable(): Boolean =
        client.isStructuredOutputAvailable()

    override suspend fun isSystemPromptAvailable(): Boolean =
        client.isSystemPromptAvailable()

    override suspend fun isCachingAvailable(): Boolean =
        client.isCachingAvailable()

    override suspend fun <T : Any> generateTyped(
        request: PromptRequest,
        outputClass: KClass<T>
    ): T? = client.generateTyped(request, outputClass)

    override suspend fun generateText(request: PromptRequest): String =
        client.generateText(request)
}

@Generable(description = "Concise title and 1-2 sentence summary of a voice note transcript")
data class StructuredTitleSummary(
    @Guide(description = "Concise topical title of the voice note, maximum 60 characters, no surrounding quotes")
    val title: String = "",
    @Guide(description = "1-2 sentence summary capturing the key points of the voice note")
    val summary: String = ""
)

@Generable(description = "A single concrete action item extracted from a voice note")
data class StructuredActionItem(
    @Guide(description = "Concise description of the concrete task or action item")
    val text: String = "",
    @Guide(description = "Date or time phrase mentioned for when this action is due, or null if none")
    val duePhrase: String? = null
)

@Generable(description = "List of concrete action items extracted from a voice note transcript")
data class StructuredActionExtraction(
    @Guide(description = "Concrete tasks or action items found in the voice note; empty list if none", maxItems = 20)
    val actions: List<StructuredActionItem> = emptyList()
)

@Generable(description = "Bucket classification and concise tags for a voice note transcript")
data class StructuredBucketAssignment(
    @Guide(description = "Exact bucket kind identifier from the allowed bucket list in the prompt")
    val bucketKind: String = "",
    @Guide(description = "Up to 3 concise lowercase hyphenated topic tags without leading #", maxItems = 3)
    val tags: List<String> = emptyList()
)

/**
 * Production [GenAiPromptClient] backed by `com.google.mlkit:genai-prompt`.
 * All ML Kit GenAI Prompt and Schema imports in the repository are isolated in this file.
 */
object MlKitGenAiPromptClient : GenAiPromptClient {

    override fun openSession(): GenAiPromptSession = MlKitGenAiPromptSession()

    override suspend fun checkStatus(): CapabilityStatus =
        withOneShotSession { it.checkStatus() }

    override suspend fun isStructuredOutputAvailable(): Boolean =
        withOneShotSession { it.isStructuredOutputAvailable() }

    override suspend fun isSystemPromptAvailable(): Boolean =
        withOneShotSession { it.isSystemPromptAvailable() }

    override suspend fun isCachingAvailable(): Boolean =
        withOneShotSession { it.isCachingAvailable() }

    override suspend fun download(): Boolean {
        val model = try {
            Generation.getClient()
        } catch (_: Throwable) {
            return false
        }
        return try {
            var completed = false
            model.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadCompleted -> completed = true
                    is DownloadStatus.DownloadFailed -> throw status.e
                    else -> Unit
                }
            }
            completed || model.checkStatus() == FeatureStatus.AVAILABLE
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { model.close() }
        }
    }

    override suspend fun <T : Any> generateTyped(
        request: PromptRequest,
        outputClass: KClass<T>
    ): T? = withOneShotSession { it.generateTyped(request, outputClass) }

    override suspend fun generateText(prompt: String): String =
        generateText(PromptRequest(prompt = prompt))

    override suspend fun generateText(request: PromptRequest): String =
        withOneShotSession { it.generateText(request) }

    override suspend fun generateTextFromImage(imagePath: String, prompt: String): String {
        val file = File(imagePath)
        require(file.exists() && file.isFile && file.length() > 0L) {
            "Image file does not exist or is empty: $imagePath"
        }
        val bitmap = withContext(Dispatchers.IO) {
            ImageFileManager.decodeScaledBitmap(imagePath, maxDimensionPx = 2048)
        } ?: return ""
        return try {
            withOneShotSession { session ->
                (session as? MlKitGenAiPromptSession)?.generateTextFromBitmap(bitmap, prompt) ?: ""
            }
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private suspend inline fun <R> withOneShotSession(block: (GenAiPromptSession) -> R): R {
        val session = openSession()
        return try {
            block(session)
        } finally {
            runCatching { session.close() }
        }
    }

    internal fun isBusyOrQuotaError(e: GenAiException): Boolean =
        e.errorCode == GenAiException.ErrorCode.BUSY ||
            e.errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ||
            e.errorCode == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED
}

private class MlKitGenAiPromptSession : GenAiPromptSession {
    private val modelLock = Any()
    private var model: GenerativeModel? = null
    private var closed = false
    private var structuredOutputSupported: Boolean? = null
    private var systemPromptSupported: Boolean? = null
    private var cachingSupported: Boolean? = null

    private fun getOrCreateModel(): GenerativeModel = synchronized(modelLock) {
        check(!closed) { "GenAiPromptSession is already closed" }
        model ?: Generation.getClient().also { model = it }
    }

    override suspend fun checkStatus(): CapabilityStatus {
        val activeModel = try {
            getOrCreateModel()
        } catch (_: Throwable) {
            return CapabilityStatus.UNAVAILABLE
        }
        return try {
            when (activeModel.checkStatus()) {
                FeatureStatus.AVAILABLE -> CapabilityStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> CapabilityStatus.DOWNLOADABLE
                else -> CapabilityStatus.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            if (MlKitGenAiPromptClient.isBusyOrQuotaError(e)) {
                CapabilityStatus.BUSY
            } else {
                CapabilityStatus.UNAVAILABLE
            }
        } catch (_: Throwable) {
            CapabilityStatus.UNAVAILABLE
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun isStructuredOutputAvailable(): Boolean {
        structuredOutputSupported?.let { return it }
        val supported = queryFeatureFlag { it.isStructuredOutputFeatureAvailable() }
        structuredOutputSupported = supported
        return supported
    }

    @Suppress("DEPRECATION")
    override suspend fun isSystemPromptAvailable(): Boolean {
        systemPromptSupported?.let { return it }
        val supported = queryFeatureFlag { it.isSystemPromptAvailable() }
        systemPromptSupported = supported
        return supported
    }

    @Suppress("DEPRECATION")
    override suspend fun isCachingAvailable(): Boolean {
        cachingSupported?.let { return it }
        val supported = queryFeatureFlag { it.isCachingFeatureAvailable() }
        cachingSupported = supported
        return supported
    }

    override suspend fun warmup() {
        val activeModel = runCatching { getOrCreateModel() }.getOrNull() ?: return
        try {
            if (activeModel.checkStatus() == FeatureStatus.AVAILABLE) {
                activeModel.warmup()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Warmup is best-effort; inference calls surface BUSY or fallbacks cleanly.
        }
    }

    override suspend fun clearImplicitCaches() {
        val activeModel = synchronized(modelLock) { model } ?: return
        runCatching { activeModel.clearImplicitCaches() }
    }

    override suspend fun <T : Any> generateTyped(
        request: PromptRequest,
        outputClass: KClass<T>
    ): T? {
        val activeModel = mapInferenceErrors { getOrCreateModel() }
        return try {
            val contentRequest = buildMlKitRequest(activeModel, request, allowPrefixCaching = true)
            val typedRequest = generateTypedContentRequest(contentRequest, outputClass)
            val response = mapInferenceErrors { activeModel.generateContent(typedRequest) }
            response.candidates.firstOrNull()?.response
        } catch (e: CancellationException) {
            throw e
        } catch (e: CapabilityBusyException) {
            throw e
        } catch (_: GenAiException) {
            // Non-busy structured output or cache errors fall back cleanly to text + regex.
            null
        } catch (_: IllegalArgumentException) {
            // Missing ServiceLoader provider or unsupported schema falls back to text + regex.
            null
        }
    }

    suspend fun generateTextFromBitmap(bitmap: android.graphics.Bitmap, prompt: String): String {
        val activeModel = mapInferenceErrors { getOrCreateModel() }
        val contentRequest = generateContentRequest(ImagePart(bitmap), TextPart(prompt)) {
            temperature = 0.2f
            candidateCount = 1
            maxOutputTokens = 512
        }
        val response = mapInferenceErrors { activeModel.generateContent(contentRequest) }
        return response.candidates.firstOrNull()?.text.orEmpty().trim()
    }

    override suspend fun generateText(request: PromptRequest): String {
        val activeModel = mapInferenceErrors { getOrCreateModel() }
        return try {
            val contentRequest = buildMlKitRequest(activeModel, request, allowPrefixCaching = true)
            val response = mapInferenceErrors { activeModel.generateContent(contentRequest) }
            response.candidates.firstOrNull()?.text.orEmpty().trim()
        } catch (e: GenAiException) {
            if (e.errorCode == GenAiException.ErrorCode.CACHE_PROCESSING_ERROR &&
                !request.promptPrefix.isNullOrEmpty()
            ) {
                val uncachedRequest = buildMlKitRequest(activeModel, request, allowPrefixCaching = false)
                val response = mapInferenceErrors { activeModel.generateContent(uncachedRequest) }
                response.candidates.firstOrNull()?.text.orEmpty().trim()
            } else {
                throw e
            }
        }
    }

    override fun close() {
        val toClose = synchronized(modelLock) {
            closed = true
            val current = model
            model = null
            current
        }
        toClose?.let { runCatching { it.close() } }
    }

    private suspend inline fun queryFeatureFlag(
        crossinline check: suspend (GenerativeModel) -> Boolean
    ): Boolean {
        val activeModel = try {
            getOrCreateModel()
        } catch (_: Throwable) {
            return false
        }
        return try {
            check(activeModel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            if (MlKitGenAiPromptClient.isBusyOrQuotaError(e)) {
                throw CapabilityBusyException()
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun buildMlKitRequest(
        activeModel: GenerativeModel,
        request: PromptRequest,
        allowPrefixCaching: Boolean
    ): GenerateContentRequest {
        val sysInstructionText = request.systemInstruction?.takeIf { it.isNotBlank() }
        val useSystemPrompt = sysInstructionText != null && isSystemPromptAvailable()
        val prefixText = request.promptPrefix?.takeIf { it.isNotEmpty() }
        val usePrefixCaching = allowPrefixCaching && prefixText != null && isCachingAvailable()

        val promptText = if (!usePrefixCaching && prefixText != null) {
            prefixText + request.prompt
        } else {
            request.prompt
        }

        return generateContentRequest(TextPart(promptText)) {
            temperature = request.temperature
            candidateCount = request.candidateCount
            maxOutputTokens = request.maxOutputTokens
            if (useSystemPrompt) {
                systemInstruction = SystemInstruction(sysInstructionText)
            }
            if (usePrefixCaching) {
                promptPrefix = PromptPrefix(
                    textString = prefixText,
                    cacheSystemInstruction = useSystemPrompt,
                    cacheTools = false
                )
            }
        }
    }

    private inline fun <R> mapInferenceErrors(block: () -> R): R =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CapabilityBusyException) {
            throw e
        } catch (e: GenAiException) {
            if (MlKitGenAiPromptClient.isBusyOrQuotaError(e)) {
                throw CapabilityBusyException()
            }
            throw e
        }
}

internal const val MAX_PROMPT_TRANSCRIPT_CHARS = 8000

internal const val NOTE_ANALYSIS_SYSTEM_INSTRUCTION =
    "You are an on-device voice-note analysis assistant. " +
        "Use only facts present in the voice note transcript and follow the requested output format strictly."

internal fun buildTranscriptPrefix(transcript: String): String = buildString {
    appendLine("Voice note transcript:")
    append(transcript.trim().take(MAX_PROMPT_TRANSCRIPT_CHARS))
    append("\n\n")
}

private suspend fun <Typed : Any, Out> executePromptLadder(
    stageName: String,
    promptClient: GenAiPromptClient,
    session: GenAiPromptSession?,
    outputClass: KClass<Typed>,
    structuredRequest: PromptRequest,
    textRequest: PromptRequest,
    normalizeTyped: (Typed) -> Out?,
    parseText: (String) -> Out?,
    fallbackResult: suspend () -> Out
): Pair<Out, Tier> {
    val ownedSession = if (session == null) promptClient.openSession() else null
    val activeSession = session ?: ownedSession!!
    try {
        when (activeSession.checkStatus()) {
            CapabilityStatus.AVAILABLE -> Unit
            CapabilityStatus.BUSY -> throw CapabilityBusyException()
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.UNAVAILABLE -> {
                CapabilityProbe.logDemotion(stageName, Tier.FULL, Tier.CORE)
                return fallbackResult() to Tier.CORE
            }
        }

        if (activeSession.isStructuredOutputAvailable()) {
            val typed = try {
                activeSession.generateTyped(structuredRequest, outputClass)
                    ?.let(normalizeTyped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CapabilityBusyException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (typed != null) {
                return typed to Tier.FULL
            }
        }

        val raw = activeSession.generateText(textRequest)
        val parsed = parseText(raw)
        if (parsed != null) {
            return parsed to Tier.FULL
        }

        CapabilityProbe.logDemotion(stageName, Tier.FULL, Tier.CORE)
        return fallbackResult() to Tier.CORE
    } finally {
        ownedSession?.let { runCatching { it.close() } }
    }
}

/**
 * Tier 3 transcript proofreader v1 backed by Gemini Nano via
 * [GenAiPromptClient] (Prompt API path only; adopting
 * `com.google.mlkit:genai-proofreading` awaits physical hardware verification
 * in #147).
 *
 * Strictly preserves facts, names, dates, and numbers; demotes to [fallback]
 * ([DeterministicTranscriptCleaner]) when UNAVAILABLE, DOWNLOADABLE, or when
 * the model returns blank or unusable output, and throws
 * [CapabilityBusyException] on BUSY/quota so `SortingWorker` logs demotion and
 * falls back cleanly.
 */
class NanoProofreader(
    private val promptClient: GenAiPromptClient = MlKitGenAiPromptClient,
    private val fallback: Proofreader = DeterministicTranscriptCleaner()
) : Proofreader {

    override suspend fun status(): CapabilityStatus = promptClient.checkStatus()

    override suspend fun proofread(rawTranscript: String): String =
        proofreadWithTier(rawTranscript).first

    internal suspend fun proofreadWithTier(rawTranscript: String): Pair<String, Tier> {
        val trimmed = rawTranscript.trim()
        if (trimmed.isEmpty()) {
            return fallback.proofread(rawTranscript) to Tier.CORE
        }

        when (promptClient.checkStatus()) {
            CapabilityStatus.AVAILABLE -> Unit
            CapabilityStatus.BUSY -> throw CapabilityBusyException()
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.UNAVAILABLE -> {
                CapabilityProbe.logDemotion("proofreader", Tier.FULL, Tier.CORE)
                return fallback.proofread(rawTranscript) to Tier.CORE
            }
        }

        if (trimmed.length > MAX_PROMPT_TRANSCRIPT_CHARS) {
            CapabilityProbe.logDemotion("proofreader", Tier.FULL, Tier.CORE)
            return fallback.proofread(rawTranscript) to Tier.CORE
        }

        val raw = promptClient.generateText(buildPrompt(trimmed), MAX_PROOFREAD_OUTPUT_TOKENS)
        val parsed = parseResponse(raw, trimmed)
        if (parsed == null) {
            CapabilityProbe.logDemotion("proofreader", Tier.FULL, Tier.CORE)
            return fallback.proofread(rawTranscript) to Tier.CORE
        }
        return parsed to Tier.FULL
    }

    companion object {
        internal const val MAX_PROMPT_TRANSCRIPT_CHARS = 8000
        private const val MAX_PROOFREAD_OUTPUT_TOKENS = 1024

        private val preambleLineRegex = Regex(
            "^(?:here(?:'s| is)\\s+the\\s+(?:proofread|corrected)\\s+(?:transcript|text)\\s*:?)$",
            RegexOption.IGNORE_CASE
        )
        private val proofreadLabelRegex = Regex(
            "^(?:\\*\\*)?(?:PROOFREAD(?:\\s+TRANSCRIPT)?|CORRECTED(?:\\s+TRANSCRIPT)?)(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*",
            RegexOption.IGNORE_CASE
        )
        private val refusalPhrases = listOf(
            "sorry, i cannot",
            "sorry, i can't",
            "i cannot comply",
            "i cannot help",
            "i cannot fulfill",
            "i cannot assist",
            "i cannot proofread",
            "i cannot process",
            "i can't comply",
            "i can't help",
            "i can't assist",
            "i can't proofread",
            "i am unable to",
            "i'm unable to",
            "as an ai",
            "i apologize"
        )
        private val thousandsCommaRegex = Regex("(?<=\\d),(?=\\d{3}\\b)")
        private val digitTokenRegex = Regex("\\d+")

        internal fun buildPrompt(rawTranscript: String): String {
            val bounded = rawTranscript.trim()
            return buildString {
                appendLine("Proofread this raw voice note transcript by adding proper punctuation, capitalization, and fixing obvious speech-to-text grammar errors.")
                appendLine("Strict rules:")
                appendLine("- Preserve all facts, names, dates, times, numbers, and speaker intent verbatim.")
                appendLine("- Do not summarize, omit sentences, or add commentary or new information.")
                appendLine("Respond in one line using this exact format:")
                appendLine("PROOFREAD: <proofread transcript>")
                appendLine()
                appendLine("Transcript:")
                append(bounded)
            }
        }

        internal fun parseResponse(raw: String, originalTranscript: String): String? {
            val strippedFence = stripCodeFences(raw)
            if (strippedFence.isBlank()) return null

            val lines = strippedFence.trim()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null

            val withoutPreamble = if (lines.size > 1 && preambleLineRegex.matches(lines.first())) {
                lines.drop(1).joinToString(" ")
            } else {
                lines.joinToString(" ")
            }

            val withoutLabel = withoutPreamble.replaceFirst(proofreadLabelRegex, "").trim()
            val originalTrimmed = originalTranscript.trim()
            val candidate = cleanProofreadText(withoutLabel, originalTrimmed)
            if (candidate.isBlank() || candidate.none { it.isLetterOrDigit() }) {
                return null
            }

            val candidateLower = candidate.lowercase(Locale.ROOT)
            val originalLower = originalTrimmed.lowercase(Locale.ROOT)
            if (refusalPhrases.any { phrase ->
                    candidateLower.startsWith(phrase) && !originalLower.startsWith(phrase)
                }
            ) {
                return null
            }

            if (!preservesDigitTokens(originalTrimmed, candidate)) {
                return null
            }

            if (originalTrimmed.length >= 40 && candidate.length < originalTrimmed.length / 4) {
                return null
            }
            if (candidate.length > originalTrimmed.length * 3 + 60) {
                return null
            }

            return candidate
        }

        private fun cleanProofreadText(raw: String, originalTrimmed: String): String {
            var text = raw.trim().replace(proseWhitespaceRegex, " ")
            if (!originalTrimmed.startsWith("**") && !originalTrimmed.endsWith("**")) {
                text = text.removePrefix("**").removeSuffix("**").trim()
            }
            val quotePairs = listOf("\"" to "\"", "'" to "'", "`" to "`")
            for ((open, close) in quotePairs) {
                if (text.length >= 2 &&
                    text.startsWith(open) &&
                    text.endsWith(close) &&
                    !(originalTrimmed.startsWith(open) && originalTrimmed.endsWith(close))
                ) {
                    text = text.substring(1, text.length - 1).trim()
                }
            }
            return text
        }

        private fun preservesDigitTokens(original: String, candidate: String): Boolean {
            val originalTokens = extractDigitTokens(original)
            if (originalTokens.isEmpty()) return true
            val candidateTokens = extractDigitTokens(candidate)
            if (candidateTokens.isEmpty()) return false

            val remaining = candidateTokens.toMutableList()
            if (originalTokens.all { remaining.remove(it) }) {
                return true
            }

            val originalDigitsOnly = original.filter(Char::isDigit)
            val candidateDigitsOnly = candidate.filter(Char::isDigit)
            return originalDigitsOnly.isNotEmpty() && originalDigitsOnly == candidateDigitsOnly
        }

        private fun extractDigitTokens(text: String): List<String> {
            val normalized = text.replace(thousandsCommaRegex, "")
            return digitTokenRegex.findAll(normalized).map { it.value }.toList()
        }
    }
}


/**
 * Tier 3 generative summarizer and title generator backed by Gemini Nano
 * via [GenAiPromptClient]. Prefers `GenerateTypedContentRequest` structured
 * output when supported, falls back to text generation + regex parsing when
 * unavailable or non-busy-failing, and demotes to [ExtractiveSummarizer]
 * on unavailable/downloadable/unparseable results. Throws
 * [CapabilityBusyException] on BUSY/quota so the pipeline records the
 * demotion explicitly.
 */
class NanoSummarizer(
    internal val promptClient: GenAiPromptClient = MlKitGenAiPromptClient,
    private val fallback: Summarizer = ExtractiveSummarizer()
) : Summarizer {

    override suspend fun status(): CapabilityStatus = promptClient.checkStatus()

    override suspend fun summarize(transcript: String): TitleSummary =
        summarizeWithTier(transcript).first

    internal suspend fun summarizeWithTier(
        transcript: String,
        session: GenAiPromptSession? = null
    ): Pair<TitleSummary, Tier> {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) {
            return fallback.summarize(transcript) to Tier.CORE
        }

        val prefix = buildTranscriptPrefix(trimmed)
        return executePromptLadder(
            stageName = "summarizer",
            promptClient = promptClient,
            session = session,
            outputClass = StructuredTitleSummary::class,
            structuredRequest = PromptRequest(
                prompt = buildStructuredTaskPrompt(),
                systemInstruction = NOTE_ANALYSIS_SYSTEM_INSTRUCTION,
                promptPrefix = prefix,
                maxOutputTokens = 256
            ),
            textRequest = PromptRequest(
                prompt = buildTextTaskPrompt(),
                systemInstruction = NOTE_ANALYSIS_SYSTEM_INSTRUCTION,
                promptPrefix = prefix,
                maxOutputTokens = 256
            ),
            normalizeTyped = ::normalizeStructuredResponse,
            parseText = ::parseResponse,
            fallbackResult = { fallback.summarize(transcript) }
        )
    }

    companion object {
        private const val MAX_TITLE_CHARS = 60

        private val titleLineRegex = Regex(
            "^(?:[-*]|\\d+[.)])?\\s*(?:\\*\\*)?TITLE(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        )
        private val summaryLineRegex = Regex(
            "^(?:[-*]|\\d+[.)])?\\s*(?:\\*\\*)?SUMMARY(?:\\*\\*)?\\s*:\\s*(?:\\*\\*)?\\s*(.*)$",
            RegexOption.IGNORE_CASE
        )

        internal fun buildStructuredTaskPrompt(): String =
            "Summarize this voice note transcript. Provide a concise topical title " +
                "(maximum 60 characters, no quotes) and a 1-2 sentence summary capturing the key points."

        internal fun buildTextTaskPrompt(): String = buildString {
            appendLine("Summarize this voice note transcript.")
            appendLine("Respond in exactly two lines:")
            appendLine("Title: <concise topical title, max 60 characters, no quotes>")
            append("Summary: <1-2 sentence summary capturing the key points>")
        }

        internal fun buildPrompt(transcript: String): String =
            buildTranscriptPrefix(transcript) + buildTextTaskPrompt()

        internal fun normalizeStructuredResponse(typed: StructuredTitleSummary): TitleSummary? {
            val title = truncateTitleOnWordBoundary(typed.title, MAX_TITLE_CHARS)
            val summary = cleanProseValue(typed.summary)
            if (title.isBlank() || summary.isBlank()) {
                return null
            }
            return TitleSummary(title = title, summary = summary)
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
 * [PlatformDateParser]. Prefers `GenerateTypedContentRequest` structured
 * output when supported, falls back to text generation + regex parsing when
 * unavailable or non-busy-failing, and demotes to [HeuristicActionExtractor]
 * when unavailable, downloadable, or unparseable while propagating
 * [CapabilityBusyException] on BUSY/quota.
 */
class NanoActionExtractor(
    internal val promptClient: GenAiPromptClient = MlKitGenAiPromptClient,
    private val fallback: ActionExtractor = HeuristicActionExtractor(),
    private val clock: Clock = Clock.systemDefaultZone()
) : ActionExtractor {

    override suspend fun status(): CapabilityStatus = promptClient.checkStatus()

    override suspend fun extract(transcript: String): List<ExtractedAction> =
        extractWithTier(transcript).first

    internal suspend fun extractWithTier(
        transcript: String,
        session: GenAiPromptSession? = null
    ): Pair<List<ExtractedAction>, Tier> {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) {
            return emptyList<ExtractedAction>() to Tier.CORE
        }

        val prefix = buildTranscriptPrefix(trimmed)
        return executePromptLadder(
            stageName = "actions",
            promptClient = promptClient,
            session = session,
            outputClass = StructuredActionExtraction::class,
            structuredRequest = PromptRequest(
                prompt = buildStructuredTaskPrompt(),
                systemInstruction = NOTE_ANALYSIS_SYSTEM_INSTRUCTION,
                promptPrefix = prefix,
                maxOutputTokens = 384
            ),
            textRequest = PromptRequest(
                prompt = buildTextTaskPrompt(),
                systemInstruction = NOTE_ANALYSIS_SYSTEM_INSTRUCTION,
                promptPrefix = prefix,
                maxOutputTokens = 256
            ),
            normalizeTyped = { normalizeStructuredResponse(it, clock) },
            parseText = { parseResponse(it, clock) },
            fallbackResult = { fallback.extract(transcript) }
        )
    }

    companion object {
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

        internal fun buildStructuredTaskPrompt(): String = buildString {
            appendLine("Extract concrete action items or tasks from this voice note transcript.")
            appendLine("For each item, set text to the task description and duePhrase to any date/time phrase (or null if none).")
            append("If there are no action items, return an empty actions list.")
        }

        internal fun buildTextTaskPrompt(): String = buildString {
            appendLine("Extract concrete action items or tasks from this voice note transcript.")
            appendLine("Respond with one line per action item using this exact format:")
            appendLine("ACTION: <task description> | DUE: <date or time phrase from transcript, or NONE>")
            appendLine("If there are no action items, respond with exactly:")
            append("NONE")
        }

        internal fun buildPrompt(transcript: String): String =
            buildTranscriptPrefix(transcript) + buildTextTaskPrompt()

        internal fun normalizeStructuredResponse(
            typed: StructuredActionExtraction,
            clock: Clock
        ): List<ExtractedAction>? {
            if (typed.actions.isEmpty()) {
                return emptyList()
            }
            val rawPairs = typed.actions.map { item -> item.text to item.duePhrase.orEmpty() }
            val items = normalizeExtractedActionPairs(rawPairs, clock)
            return if (items.isEmpty()) null else items
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

            val rawPairs = mutableListOf<Pair<String, String>>()
            for (line in cleaned.lines()) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) continue

                val match = actionLineRegex.matchEntire(trimmedLine) ?: continue
                val payload = match.groupValues[1].trim()
                if (payload.isEmpty()) continue

                val parts = dueSplitRegex.split(payload, limit = 2)
                rawPairs += parts[0] to parts.getOrNull(1).orEmpty()
            }

            val items = normalizeExtractedActionPairs(rawPairs, clock)
            if (items.isEmpty()) {
                return null
            }
            return items
        }

        private fun normalizeExtractedActionPairs(
            pairs: List<Pair<String, String>>,
            clock: Clock
        ): List<ExtractedAction> {
            val items = mutableListOf<ExtractedAction>()
            val seenTexts = mutableSetOf<String>()
            for ((rawText, rawDue) in pairs) {
                val actionText = cleanProseValue(rawText)
                if (actionText.isBlank() || !seenTexts.add(actionText)) continue

                val duePhrase = cleanProseValue(rawDue)
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
            return items
        }
    }
}

/**
 * Tier 3 generative bucketer and tagger backed by Gemini Nano via
 * [GenAiPromptClient]. Classifies transcripts into a bucket from the
 * active [BucketProfile] plus concise lowercase tags, returning
 * `Tier.FULL`. Prefers `GenerateTypedContentRequest` structured output when
 * supported, falls back to text generation + regex parsing when
 * unavailable or non-busy-failing, and demotes to [HeuristicBucketer] when
 * unavailable, downloadable, or unparseable while propagating
 * [CapabilityBusyException] on BUSY/quota.
 */
class NanoBucketer(
    internal val promptClient: GenAiPromptClient = MlKitGenAiPromptClient,
    private val fallback: Bucketer = HeuristicBucketer()
) : Bucketer {

    override suspend fun status(): CapabilityStatus = promptClient.checkStatus()

    override suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment =
        assignWithSession(transcript, profile, session = null)

    internal suspend fun assignWithSession(
        transcript: String,
        profile: BucketProfile,
        session: GenAiPromptSession? = null
    ): BucketAssignment {
        if (transcript.isBlank() || profile.buckets.isEmpty()) {
            return fallback.assign(transcript, profile)
        }

        val prefix = buildTranscriptPrefix(transcript)
        return executePromptLadder(
            stageName = "bucketer",
            promptClient = promptClient,
            session = session,
            outputClass = StructuredBucketAssignment::class,
            structuredRequest = PromptRequest(
                prompt = buildStructuredTaskPrompt(profile),
                systemInstruction = NOTE_ANALYSIS_SYSTEM_INSTRUCTION,
                promptPrefix = prefix,
                maxOutputTokens = 256
            ),
            textRequest = PromptRequest(
                prompt = buildTextTaskPrompt(profile),
                systemInstruction = NOTE_ANALYSIS_SYSTEM_INSTRUCTION,
                promptPrefix = prefix,
                maxOutputTokens = 256
            ),
            normalizeTyped = { normalizeStructuredResponse(it, profile) },
            parseText = { parseResponse(it, profile) },
            fallbackResult = { fallback.assign(transcript, profile) }
        ).first
    }

    companion object {
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
        private val tagSanitizeRegex = Regex("[^a-z0-9-]+")

        internal fun buildStructuredTaskPrompt(profile: BucketProfile): String = buildString {
            appendLine("Classify this voice note transcript into exactly one bucket from the allowed list and generate up to 3 concise lowercase tags.")
            appendLine("Allowed buckets (set bucketKind to the matching bucket kind):")
            profile.buckets.forEach { def ->
                appendLine("- ${def.kind} (${def.name})")
            }
        }.trimEnd()

        internal fun buildTextTaskPrompt(profile: BucketProfile): String = buildString {
            appendLine("Classify this voice note transcript into exactly one bucket from the allowed list and generate up to 3 concise lowercase tags.")
            appendLine("Allowed buckets:")
            profile.buckets.forEach { def ->
                appendLine("- ${def.kind} (${def.name})")
            }
            appendLine()
            appendLine("Respond in exactly two lines:")
            appendLine("BUCKET: <one bucket kind from the allowed list above>")
            append("TAGS: <comma-separated concise lowercase tags, or NONE>")
        }

        internal fun buildPrompt(transcript: String, profile: BucketProfile): String =
            buildTranscriptPrefix(transcript) + buildTextTaskPrompt(profile)

        internal fun normalizeStructuredResponse(
            typed: StructuredBucketAssignment,
            profile: BucketProfile
        ): BucketAssignment? {
            val matchedBucket = matchAllowedBucket(typed.bucketKind, profile) ?: return null
            val tags = normalizeTagTokens(typed.tags)
            return buildAssignment(matchedBucket, tags)
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

            val matchedBucket = matchAllowedBucket(rawBucket.orEmpty(), profile) ?: return null
            val cleanedTags = cleanProseValue(rawTags.orEmpty())
            val tags = if (cleanedTags.isBlank() || cleanedTags.lowercase(Locale.ROOT) in emptyTagTokens) {
                emptyList()
            } else {
                normalizeTagTokens(cleanedTags.split(","))
            }
            return buildAssignment(matchedBucket, tags)
        }

        private fun matchAllowedBucket(rawBucket: String, profile: BucketProfile): BucketDef? {
            val candidateBucket = cleanProseValue(rawBucket)
                .substringBefore(" (")
                .trim()
            if (candidateBucket.isEmpty()) return null

            return profile.buckets.firstOrNull {
                it.kind.equals(candidateBucket, ignoreCase = true)
            } ?: profile.buckets.firstOrNull {
                it.name.equals(candidateBucket, ignoreCase = true)
            }
        }

        private fun buildAssignment(matchedBucket: BucketDef, tags: List<String>): BucketAssignment {
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

        private fun normalizeTagTokens(rawTokens: Iterable<String>): List<String> =
            rawTokens
                .map { token ->
                    cleanProseValue(token)
                        .trim()
                        .removePrefix("#")
                        .lowercase(Locale.ROOT)
                        .replace(tagSanitizeRegex, "-")
                        .trim('-')
                }
                .filter { it.isNotEmpty() && it !in emptyTagTokens }
                .distinct()
                .take(MAX_TAGS)
    }
}

/**
 * Tier 3 multimodal image transcriber backed by Gemini Nano via
 * [GenAiPromptClient] (`ImagePart(Bitmap)` + `TextPart`), falling back to
 * [MlKitOcrImageTranscriber] when unavailable, downloadable, or blank.
 * Propagates [CapabilityBusyException] on BUSY/quota so the pipeline
 * records the demotion explicitly.
 */
class NanoImageTranscriber(
    private val promptClient: GenAiPromptClient = MlKitGenAiPromptClient,
    private val fallback: ImageTranscriber = NullImageTranscriber
) : ImageTranscriber {

    constructor(context: Context) : this(
        promptClient = MlKitGenAiPromptClient,
        fallback = MlKitOcrImageTranscriber(context.applicationContext)
    )

    override suspend fun status(): CapabilityStatus = promptClient.checkStatus()

    override suspend fun transcribeImage(imagePath: String): Transcript {
        require(imagePath.isNotBlank()) { "Image path must not be blank" }

        when (promptClient.checkStatus()) {
            CapabilityStatus.AVAILABLE -> Unit
            CapabilityStatus.BUSY -> throw CapabilityBusyException()
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.UNAVAILABLE -> {
                CapabilityProbe.logDemotion("image_transcriber", Tier.FULL, Tier.CORE)
                return fallback.transcribeImage(imagePath)
            }
        }

        val raw = try {
            promptClient.generateTextFromImage(imagePath, buildPrompt())
        } catch (e: CancellationException) {
            throw e
        } catch (e: CapabilityBusyException) {
            throw e
        } catch (_: GenAiException) {
            CapabilityProbe.logDemotion("image_transcriber", Tier.FULL, Tier.CORE)
            return fallback.transcribeImage(imagePath)
        }

        val cleaned = stripCodeFences(raw).trim()
        if (cleaned.isBlank()) {
            CapabilityProbe.logDemotion("image_transcriber", Tier.FULL, Tier.CORE)
            return fallback.transcribeImage(imagePath)
        }
        return Transcript(cleaned, Tier.FULL)
    }

    companion object {
        internal fun buildPrompt(): String = buildString {
            appendLine("Transcribe all handwritten and printed text visible in this image verbatim.")
            appendLine("Preserve line breaks and list items. Do not add commentary or markdown code fences.")
            append("If there is no readable text in the image, respond with an empty string.")
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

private val proseWhitespaceRegex = Regex("\\s+")

private fun cleanProseValue(raw: String): String {
    var text = raw.trim().replace(proseWhitespaceRegex, " ")
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
