// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

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
