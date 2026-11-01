// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import io.github.alox1d.dateparser.DateParser
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * Tier 2 filing: deterministic keyword heuristics over the transcript.
 * No model, no magic, and fully unit-testable - which is why the ladder's
 * tests concentrate here. Tier 3 replaces this with model classification
 * behind the same Bucketer interface.
 */
class HeuristicBucketer : Bucketer {
    override suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment {
        val lower = transcript.lowercase()
        val match = profile.buckets.firstOrNull { def ->
            def.keywords.any { kw -> lower.contains(kw) }
        } ?: profile.buckets.firstOrNull { it.kind == "INBOX" }
        ?: profile.buckets.first()
        return BucketAssignment(match.kind, tags = emptyList(), tier = Tier.CORE)
    }
}

/** Extractive stand-in for generative summarisation: the first sentence
 *  is the title, the first two are the summary. Embarrassing, effective,
 *  and honest about which tier produced it. */
class ExtractiveSummarizer : Summarizer {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE

    override suspend fun summarize(transcript: String): TitleSummary {
        val sentences = transcript.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val title = (sentences.firstOrNull() ?: "Untitled note").take(60)
        val summary = sentences.take(2).joinToString(" ")
        return TitleSummary(title, summary)
    }
}

/**
 * Pattern-based action extraction for the non-Nano tiers, with offline due-date annotations.
 * Date-only phrases retain the reference clock time in [timeZone]. A past time stays past;
 * this annotates actions rather than scheduling reminders. Recognized ranges, recurrence,
 * multiple date matches and unresolved temporal markers leave the due date unset.
 */
class HeuristicActionExtractor(
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ActionExtractor {
    private val dateParser = DateParser()
    // Version 0.1.3 can return a partial point while leaving these temporal markers behind.
    // These only reject unresolved input; DateParser remains responsible for resolving dates.
    private val unresolvedTemporalMarkers = listOf(
        Regex("""(?<![\d.:])\d{1,2}:\d{2}(?!\d)"""),
        Regex("""(?i)\b(?:between|from|с)\s+\d{1,2}\s+(?:and|to|до)\s+\d{1,2}\b"""),
        Regex(
            """(?i)\b(?:daily|weekly|monthly|yearly|annually|hourly|nightly|ежедневно|еженедельно|ежемесячно|ежегодно)\b(?=\s*(?:at\b|в\b|[.!?,;:]|$))"""
        ),
        Regex("""(?i)\b(?:UTC|GMT|EST|EDT|CST|CDT|MST|MDT|PST|PDT|CET|CEST|EET|EEST|MSK)\b"""),
    )

    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE

    override suspend fun extract(transcript: String): List<ExtractedAction> {
        val reference = clock.now()
        val triggers = listOf("remind me", "need to", "don't forget", "remember to")
        return transcript.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { s -> triggers.any { t -> s.lowercase().contains(t) } }
            .map { text ->
                val match = dateParser.parse(text, reference, timeZone).singleOrNull()
                val dueAt = match?.takeIf { candidate ->
                    candidate.endDate == null && candidate.endInstant == null && candidate.recurrence == null &&
                        unresolvedTemporalMarkers.none { it.containsMatchIn(candidate.remainingText) }
                }?.instant?.toEpochMilliseconds()
                ExtractedAction(text, dueAtEpochMs = dueAt)
            }
    }
}
