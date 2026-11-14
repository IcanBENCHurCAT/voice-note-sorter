// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Tier 2 filing: deterministic keyword heuristics over the transcript.
 * No model, no magic, and fully unit-testable - which is why the ladder's
 * tests concentrate here. Tier 3 replaces this with model classification
 * behind the same Bucketer interface.
 */
class HeuristicBucketer : Bucketer {
    private data class CandidateScore(
        val def: BucketDef,
        val matchedKeywords: List<String>,
        val totalMatches: Int
    ) {
        val distinctKeywordCount: Int get() = matchedKeywords.size
    }

    override suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment {
        val candidates = profile.buckets.map { def ->
            val matched = mutableListOf<String>()
            var occurrences = 0
            for (kw in def.keywords) {
                val count = countWordBoundaryMatches(transcript, kw)
                if (count > 0) {
                    matched += kw
                    occurrences += count
                }
            }
            CandidateScore(def = def, matchedKeywords = matched, totalMatches = occurrences)
        }

        val positiveCandidates = candidates.filter { it.distinctKeywordCount > 0 }
        val fallbackBucket = profile.buckets.firstOrNull { it.kind == "INBOX" }
            ?: profile.buckets.first()

        if (positiveCandidates.isEmpty()) {
            return BucketAssignment(
                bucketKind = fallbackBucket.kind,
                tags = emptyList(),
                tier = Tier.CORE,
                confidence = 0.0f,
                score = 0,
                needsReview = true
            )
        }

        val sorted = positiveCandidates.sortedWith(
            compareByDescending<CandidateScore> { it.distinctKeywordCount }
                .thenByDescending { it.totalMatches }
        )
        val winner = sorted.first()
        val runnerUp = sorted.getOrNull(1)
        val tiedTopScore = runnerUp != null &&
            runnerUp.distinctKeywordCount == winner.distinctKeywordCount &&
            runnerUp.totalMatches == winner.totalMatches

        val matchedTags = winner.matchedKeywords
            .map { kw -> keywordToTag(kw) }
            .filter { it.isNotEmpty() }
            .distinct()

        val confidence = when {
            tiedTopScore -> 0.5f
            winner.distinctKeywordCount >= 2 -> 1.0f
            else -> 0.8f
        }

        return BucketAssignment(
            bucketKind = winner.def.kind,
            tags = matchedTags,
            tier = Tier.CORE,
            confidence = confidence,
            score = winner.distinctKeywordCount,
            needsReview = tiedTopScore || winner.def.kind == "INBOX"
        )
    }

    companion object {
        fun matchesKeyword(text: String, keyword: String): Boolean =
            countWordBoundaryMatches(text, keyword) > 0

        fun countWordBoundaryMatches(text: String, keyword: String): Int {
            val trimmed = keyword.trim()
            if (trimmed.isEmpty() || text.isBlank()) return 0
            val pattern = buildWordBoundaryPattern(trimmed)
            return pattern.findAll(text).count()
        }

        private fun buildWordBoundaryPattern(keyword: String): Regex {
            val tokens = keyword.lowercase()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString("\\s+") { Regex.escape(it) }
            return Regex("(?<![a-z0-9])$tokens(?![a-z0-9])", RegexOption.IGNORE_CASE)
        }

        fun keywordToTag(keyword: String): String =
            keyword.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
    }
}

/**
 * Segments dictated speech into sentence-like clauses even when speech
 * recognition omits terminal punctuation.
 */
object TranscriptSegmenter {
    private val terminalSplitRegex = Regex("(?<=[.!?])\\s+|\\r?\\n+")
    private val discourseMarkerSplitRegex = Regex(
        "\\s+(?=(?:" +
            "remind me|don't forget|do not forget|remember to|" +
            "i need to|we need to|need to|have to|must|should|" +
            "and then|after that|finally|secondly|thirdly|" +
            "(?:also\\s+)?(?:call|email|text|message|send|buy|pick up|schedule|book|pay|order|check|follow up|finish|submit|review)" +
            ")\\b)",
        RegexOption.IGNORE_CASE
    )

    private const val UNPUNCTUATED_WORD_THRESHOLD = 14

    fun segment(transcript: String): List<String> {
        val normalized = transcript.trim().replace(Regex("[ \\t]+"), " ")
        if (normalized.isEmpty()) return emptyList()

        val initialChunks = normalized.split(terminalSplitRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = mutableListOf<String>()
        for (chunk in initialChunks) {
            val words = chunk.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size <= UNPUNCTUATED_WORD_THRESHOLD) {
                result += chunk
            } else {
                result += segmentChunk(chunk)
            }
        }
        return result
    }

    private fun segmentChunk(chunk: String): List<String> {
        val rawClauses = chunk.split(discourseMarkerSplitRegex)
            .map { it.trim().trim(',', ';') }
            .filter { it.isNotEmpty() }

        val clauses = mutableListOf<String>()
        for (clause in rawClauses) {
            if (clauses.isNotEmpty() && (clauses.last().endsWith(" to", ignoreCase = true) || clauses.last().endsWith(" also", ignoreCase = true))) {
                clauses[clauses.lastIndex] = clauses.last() + " " + clause
            } else {
                clauses += clause
            }
        }

        val refined = mutableListOf<String>()
        for (clause in clauses) {
            val words = clause.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            if (refined.isNotEmpty() && words.size < 3) {
                refined[refined.lastIndex] = refined.last() + " " + clause
            } else if (words.size <= UNPUNCTUATED_WORD_THRESHOLD * 2) {
                refined += clause
            } else {
                // Fallback windowing for long run-on dictation without discourse markers
                refined += splitLongRunOn(words)
            }
        }
        return refined
    }

    private fun splitLongRunOn(words: List<String>): List<String> {
        val windows = mutableListOf<String>()
        var start = 0
        val conjunctions = setOf("and", "so", "but", "then", "because", "while")
        while (start < words.size) {
            val maxEnd = (start + UNPUNCTUATED_WORD_THRESHOLD).coerceAtMost(words.size)
            if (maxEnd == words.size) {
                windows += words.subList(start, words.size).joinToString(" ")
                break
            }
            // Prefer splitting right before a coordinating conjunction near the window boundary
            var splitAt = maxEnd
            for (idx in maxEnd downTo (start + 8).coerceAtMost(maxEnd)) {
                if (idx < words.size && words[idx].lowercase(Locale.ROOT) in conjunctions) {
                    splitAt = idx
                    break
                }
            }
            windows += words.subList(start, splitAt).joinToString(" ")
            start = splitAt
        }
        return windows
    }
}

/**
 * Extractive stand-in for generative summarisation: segments the transcript
 * (including unpunctuated dictation), scores each segment by signal words,
 * content-word density, and position, and selects the highest-signal
 * sentences (up to 2) in their original order.
 */
class ExtractiveSummarizer : Summarizer {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE

    override suspend fun summarize(transcript: String): TitleSummary {
        val segments = TranscriptSegmenter.segment(transcript)
        if (segments.isEmpty()) {
            return TitleSummary("Untitled note", "")
        }

        val scored = segments.mapIndexed { index, segment ->
            ScoredSegment(
                index = index,
                text = segment,
                score = scoreSegment(segment, index, segments.size)
            )
        }

        val bestForTitle = scored.maxWithOrNull(
            compareBy<ScoredSegment> { it.score }
                .thenBy { it.index }
        ) ?: scored.first()

        val topSegments = scored
            .sortedWith(
                compareByDescending<ScoredSegment> { it.score }
                    .thenBy { it.index }
            )
            .take(2)
            .sortedBy { it.index }
            .map { ensureTerminalPunctuation(it.text) }

        val title = truncateAtWordBoundary(cleanTitle(bestForTitle.text), MAX_TITLE_CHARS)
        val summary = topSegments.joinToString(" ")
        return TitleSummary(title, summary)
    }

    private data class ScoredSegment(
        val index: Int,
        val text: String,
        val score: Double
    )

    companion object {
        internal const val MAX_TITLE_CHARS = 60

        private val stopWords = setOf(
            "a", "an", "the", "and", "or", "but", "if", "then", "so", "to", "of", "in", "on",
            "at", "for", "with", "by", "from", "up", "about", "into", "over", "after", "is",
            "am", "are", "was", "were", "be", "been", "being", "it", "this", "that", "these",
            "those", "i", "we", "you", "they", "he", "she", "my", "our", "your", "just", "like",
            "uh", "um", "yeah", "well", "okay", "so", "very", "really"
        )

        private val fillerOpeners = listOf(
            "testing", "testing one two", "this is a test", "this is just a test",
            "test voice note", "hello", "hey there", "let me see", "is this thing on",
            "um", "uh", "okay so", "well anyway"
        )

        private val fillerOpenerRegex = Regex(
            "^(?:${fillerOpeners.joinToString("|") { Regex.escape(it) }})\\b"
        )

        private val leadingFillerPrefixRegex = Regex(
            "^(?:(?:um|uh|okay\\s+so|well\\s+anyway|so\\s+now)[,\\s]+)*" +
                "(?:this\\s+is\\s+(?:just\\s+)?a\\s+test(?:\\s+voice\\s+note)?(?:[,\\s]+(?:and[,\\s]+)?(?:first[,\\s]+)?)?)?",
            RegexOption.IGNORE_CASE
        )

        private val signalPhrases = listOf(
            "remind me", "need to", "don't forget", "do not forget", "remember to",
            "what if", "we should build", "idea", "important", "key takeaway", "decision",
            "summary", "action item", "call", "email", "buy", "pick up", "schedule",
            "deadline", "tomorrow", "today", "tonight", "next week", "groceries", "shopping", "list"
        )

        internal fun scoreSegment(segment: String, index: Int, totalCount: Int): Double {
            val words = segment.lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9']+"))
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return 0.0

            val contentWords = words.filter { it !in stopWords && it.length > 2 }
            var score = contentWords.distinct().size * 1.0

            for (phrase in signalPhrases) {
                if (HeuristicBucketer.matchesKeyword(segment, phrase)) {
                    score += 3.5
                }
            }

            // Numeric or date/time tokens carry concrete information
            if (words.any { it.any(Char::isDigit) }) {
                score += 1.5
            }

            // Mild lead bias only as a tie-breaker so high-signal later sentences beat filler leads
            if (index == 0 && totalCount > 1) {
                score += 0.25
            }

            val lowerTrimmed = segment.trim().lowercase(Locale.ROOT)
            if (fillerOpenerRegex.containsMatchIn(lowerTrimmed)) {
                score -= 4.0
            }

            return score
        }

        internal fun truncateAtWordBoundary(text: String, maxChars: Int = MAX_TITLE_CHARS): String {
            val normalized = text.trim()
            if (normalized.length <= maxChars) return normalized
            val lastSpace = normalized.substring(0, maxChars + 1).lastIndexOf(' ')
            return if (lastSpace > 0) {
                normalized.substring(0, lastSpace)
                    .trimEnd('.', ',', ';', ':', '-', ' ')
                    .ifBlank { normalized.take(maxChars) }
            } else {
                normalized.take(maxChars)
            }
        }

        private fun cleanTitle(text: String): String {
            val stripped = text.trim().trimEnd('.', '!', '?', ',', ';')
            if (stripped.isBlank()) return "Untitled note"
            val candidate = stripped.replaceFirst(leadingFillerPrefixRegex, "")
                .trimStart(',', ';', ':', '-', ' ')
                .trim()
            val candidateWords = candidate.split(Regex("\\s+")).filter { it.isNotEmpty() }
            return if (candidate.isNotBlank() && candidateWords.size >= 3) {
                candidate
            } else {
                stripped
            }
        }

        private fun ensureTerminalPunctuation(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return trimmed
            return if (trimmed.last() in charArrayOf('.', '!', '?')) trimmed else "$trimmed."
        }
    }
}

/**
 * Pattern-based action extraction for the non-Nano tiers: covers reminder
 * phrases, obligation phrases, imperative openers, and 'call / email / buy'
 * phrasing, plus platform `java.time` date parsing for `dueAtEpochMs`.
 */
class HeuristicActionExtractor(
    private val clock: Clock = Clock.systemDefaultZone()
) : ActionExtractor {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE

    override suspend fun extract(transcript: String): List<ExtractedAction> {
        val segments = TranscriptSegmenter.segment(transcript)
        return segments
            .filter { segment -> isActionSegment(segment) }
            .map { segment ->
                ExtractedAction(
                    text = segment.trim(),
                    dueAtEpochMs = PlatformDateParser.parseDueAtEpochMs(segment, clock)
                )
            }
    }

    companion object {
        private val phraseTriggers = listOf(
            "remind me",
            "need to",
            "don't forget",
            "do not forget",
            "remember to",
            "have to",
            "must",
            "make sure to",
            "be sure to",
            "todo",
            "to-do"
        )

        private val actionVerbs = listOf(
            "call",
            "email",
            "buy",
            "pick up",
            "send",
            "schedule",
            "book",
            "pay",
            "order",
            "text",
            "message",
            "check",
            "follow up",
            "finish",
            "submit",
            "review",
            "renew",
            "cancel",
            "prepare",
            "bring",
            "drop off"
        )

        private val imperativeStarterRegex = Regex(
            "^\\s*(?:please\\s+|also\\s+|then\\s+|and\\s+)*(" +
                actionVerbs.joinToString("|") { Regex.escape(it) } +
                ")\\b",
            RegexOption.IGNORE_CASE
        )

        private val modalActionVerbRegex = Regex(
            "\\b(?:i\\s+|we\\s+)?(?:should|will|otta|gotta|need\\s+to|have\\s+to|must|remind\\s+me\\s+to|remember\\s+to|don't\\s+forget\\s+to)\\s+(" +
                actionVerbs.joinToString("|") { Regex.escape(it) } +
                ")\\b",
            RegexOption.IGNORE_CASE
        )

        private val directCoreVerbRegex = Regex(
            "(?<![a-z0-9])(?:call|email|buy|pick\\s+up)\\s+(?:the\\s+|a\\s+|an\\s+|my\\s+|our\\s+|some\\s+|[a-z0-9])",
            RegexOption.IGNORE_CASE
        )

        fun isActionSegment(segment: String): Boolean {
            if (segment.isBlank()) return false
            if (phraseTriggers.any { HeuristicBucketer.matchesKeyword(segment, it) }) {
                return true
            }
            if (imperativeStarterRegex.containsMatchIn(segment)) {
                return true
            }
            if (modalActionVerbRegex.containsMatchIn(segment)) {
                return true
            }
            if (directCoreVerbRegex.containsMatchIn(segment)) {
                return true
            }
            return false
        }
    }
}

/**
 * Deterministic platform `java.time` parser for relative and calendar date/time
 * phrases in dictated action items (e.g. "tomorrow", "on Thursday", "next Monday",
 * "in 3 days", "at 3pm", "before noon", "on November 15").
 */
object PlatformDateParser {
    private val dayMap = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY
    )

    private val monthMap = mapOf(
        "january" to Month.JANUARY, "jan" to Month.JANUARY,
        "february" to Month.FEBRUARY, "feb" to Month.FEBRUARY,
        "march" to Month.MARCH, "mar" to Month.MARCH,
        "april" to Month.APRIL, "apr" to Month.APRIL,
        "may" to Month.MAY,
        "june" to Month.JUNE, "jun" to Month.JUNE,
        "july" to Month.JULY, "jul" to Month.JULY,
        "august" to Month.AUGUST, "aug" to Month.AUGUST,
        "september" to Month.SEPTEMBER, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER,
        "october" to Month.OCTOBER, "oct" to Month.OCTOBER,
        "november" to Month.NOVEMBER, "nov" to Month.NOVEMBER,
        "december" to Month.DECEMBER, "dec" to Month.DECEMBER
    )

    private val weekdayPhraseRegex = Regex(
        "\\b(?:(on|by|this|next)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        RegexOption.IGNORE_CASE
    )

    private val inDurationRegex = Regex(
        "\\bin\\s+(\\d+|one|two|three|four|five|six|seven)\\s+(day|days|week|weeks|hour|hours)\\b",
        RegexOption.IGNORE_CASE
    )

    private val monthDayRegex = Regex(
        "\\b(?:on\\s+|by\\s+)?(january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sep|sept|october|oct|november|nov|december|dec)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b",
        RegexOption.IGNORE_CASE
    )

    private val isoDateRegex = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")

    private val explicitTimeRegex = Regex(
        "\\b(?:at|by|before)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?\\b|\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)\\b",
        RegexOption.IGNORE_CASE
    )

    fun parseDueAtEpochMs(text: String, clock: Clock = Clock.systemDefaultZone()): Long? {
        val zone: ZoneId = clock.zone
        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate()
        val lower = text.lowercase(Locale.ROOT)

        var resolvedDate: LocalDate? = null
        var explicitHourTime: LocalDateTime? = null

        // 1. Relative duration ("in 2 hours", "in 3 days", "in 2 weeks")
        val inMatch = inDurationRegex.find(lower)
        if (inMatch != null) {
            val amount = parseNumberToken(inMatch.groupValues[1]) ?: 1L
            val unit = inMatch.groupValues[2].lowercase(Locale.ROOT)
            when {
                unit.startsWith("hour") -> explicitHourTime = now.plusHours(amount)
                unit.startsWith("day") -> resolvedDate = today.plusDays(amount)
                unit.startsWith("week") -> resolvedDate = today.plusWeeks(amount)
            }
        }

        // 2. Relative day keywords ("tomorrow", "today", "tonight", "next week")
        if (resolvedDate == null && explicitHourTime == null) {
            when {
                HeuristicBucketer.matchesKeyword(lower, "day after tomorrow") -> {
                    resolvedDate = today.plusDays(2)
                }
                HeuristicBucketer.matchesKeyword(lower, "tomorrow") -> {
                    resolvedDate = today.plusDays(1)
                }
                HeuristicBucketer.matchesKeyword(lower, "tonight") ||
                    HeuristicBucketer.matchesKeyword(lower, "today") -> {
                    resolvedDate = today
                }
                HeuristicBucketer.matchesKeyword(lower, "next week") -> {
                    resolvedDate = today.plusWeeks(1)
                }
            }
        }

        // 3. Weekday phrase ("on Thursday", "by Friday", "next Monday", "Thursday")
        if (resolvedDate == null && explicitHourTime == null) {
            val dayMatch = weekdayPhraseRegex.find(lower)
            if (dayMatch != null) {
                val qualifier = dayMatch.groupValues[1].lowercase(Locale.ROOT)
                val dayName = dayMatch.groupValues[2].lowercase(Locale.ROOT)
                val targetDow = dayMap[dayName]
                if (targetDow != null) {
                    resolvedDate = if (qualifier == "next") {
                        today.with(TemporalAdjusters.next(targetDow))
                    } else {
                        today.with(TemporalAdjusters.nextOrSame(targetDow)).let { candidate ->
                            if (candidate == today) today.with(TemporalAdjusters.next(targetDow)) else candidate
                        }
                    }
                }
            }
        }

        // 4. Calendar month + day ("on November 15", "Dec 3rd")
        if (resolvedDate == null && explicitHourTime == null) {
            val mdMatch = monthDayRegex.find(lower)
            if (mdMatch != null) {
                val month = monthMap[mdMatch.groupValues[1].lowercase(Locale.ROOT)]
                val dayOfMonth = mdMatch.groupValues[2].toIntOrNull()
                if (month != null && dayOfMonth != null && dayOfMonth in 1..31) {
                    val candidate = runCatching { LocalDate.of(today.year, month, dayOfMonth) }.getOrNull()
                    if (candidate != null) {
                        resolvedDate = if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
                    }
                }
            }
        }

        // 5. ISO date ("2026-11-15")
        if (resolvedDate == null && explicitHourTime == null) {
            val isoMatch = isoDateRegex.find(lower)
            if (isoMatch != null) {
                resolvedDate = runCatching { LocalDate.parse(isoMatch.value) }.getOrNull()
            }
        }

        if (explicitHourTime != null) {
            return explicitHourTime.atZone(zone).toInstant().toEpochMilli()
        }

        val resolvedTime = parseTimeOfDay(lower)
        if (resolvedDate == null && resolvedTime != null) {
            // Time-only phrase like "before noon" or "at 3pm" defaults to today (or tomorrow if already past)
            val candidateDateTime = LocalDateTime.of(today, resolvedTime)
            val finalDateTime = if (candidateDateTime.isBefore(now)) {
                candidateDateTime.plusDays(1)
            } else {
                candidateDateTime
            }
            return finalDateTime.atZone(zone).toInstant().toEpochMilli()
        }

        if (resolvedDate == null) return null

        val timeToUse = resolvedTime ?: defaultTimeForPhrase(lower)
        return LocalDateTime.of(resolvedDate, timeToUse)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun parseTimeOfDay(lower: String): LocalTime? {
        if (HeuristicBucketer.matchesKeyword(lower, "noon") ||
            HeuristicBucketer.matchesKeyword(lower, "midday")
        ) {
            return LocalTime.of(12, 0)
        }
        if (HeuristicBucketer.matchesKeyword(lower, "midnight")) {
            return LocalTime.of(23, 59)
        }

        val match = explicitTimeRegex.find(lower)
        if (match != null) {
            val hourStr = match.groupValues[1].ifEmpty { match.groupValues[4] }
            val minuteStr = match.groupValues[2].ifEmpty { match.groupValues[5] }
            val meridiem = match.groupValues[3].ifEmpty { match.groupValues[6] }
                .replace(".", "")
                .lowercase(Locale.ROOT)

            val rawHour = hourStr.toIntOrNull() ?: return null
            val minute = minuteStr.toIntOrNull() ?: 0
            if (minute !in 0..59) return null

            val hour24 = when (meridiem) {
                "pm" -> if (rawHour in 1..11) rawHour + 12 else if (rawHour == 12) 12 else rawHour
                "am" -> if (rawHour == 12) 0 else rawHour
                else -> rawHour
            }
            if (hour24 in 0..23) {
                return LocalTime.of(hour24, minute)
            }
        }

        return when {
            HeuristicBucketer.matchesKeyword(lower, "morning") -> LocalTime.of(9, 0)
            HeuristicBucketer.matchesKeyword(lower, "afternoon") -> LocalTime.of(14, 0)
            HeuristicBucketer.matchesKeyword(lower, "evening") ||
                HeuristicBucketer.matchesKeyword(lower, "tonight") -> LocalTime.of(18, 0)
            else -> null
        }
    }

    private fun defaultTimeForPhrase(lower: String): LocalTime =
        if (HeuristicBucketer.matchesKeyword(lower, "tonight")) {
            LocalTime.of(18, 0)
        } else {
            LocalTime.of(9, 0)
        }

    private fun parseNumberToken(token: String): Long? = when (token.lowercase(Locale.ROOT)) {
        "one" -> 1L
        "two" -> 2L
        "three" -> 3L
        "four" -> 4L
        "five" -> 5L
        "six" -> 6L
        "seven" -> 7L
        else -> token.toLongOrNull()
    }
}
