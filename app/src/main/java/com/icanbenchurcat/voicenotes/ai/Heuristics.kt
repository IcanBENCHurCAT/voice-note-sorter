// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

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
            def.keywords.any { kw -> lower.contains(kw.lowercase()) }
        } ?: profile.buckets.firstOrNull { it.kind == "INBOX" }
        ?: profile.buckets.first()

        val matchedTags = match.keywords
            .filter { kw -> lower.contains(kw.lowercase()) }
            .map { kw -> keywordToTag(kw) }
            .filter { it.isNotEmpty() }
            .distinct()

        return BucketAssignment(match.kind, tags = matchedTags, tier = Tier.CORE)
    }

    companion object {
        fun keywordToTag(keyword: String): String =
            keyword.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
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

/** Pattern-based action extraction for the non-Nano tiers. */
class HeuristicActionExtractor : ActionExtractor {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE

    override suspend fun extract(transcript: String): List<ExtractedAction> {
        val triggers = listOf("remind me", "need to", "don't forget", "remember to")
        return transcript.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { s -> triggers.any { t -> s.lowercase().contains(t) } }
            .map { ExtractedAction(it) }
    }
}
