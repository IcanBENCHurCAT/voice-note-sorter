package com.icanbenchurcat.voicenotes.ai

/**
 * The capability ladder. Every on-device AI feature is a capability to
 * probe, never a boolean to assume. UI and pipeline code depend on these
 * interfaces only; ML Kit is referenced solely from the Nano
 * implementations in this package.
 */

/** Which rung of the ladder a capability resolved to at runtime. */
enum class Tier { FULL, CORE, MINIMAL }

/** Availability of a single capability, mirroring ML Kit GenAI states. */
enum class CapabilityStatus { AVAILABLE, DOWNLOADABLE, UNAVAILABLE, BUSY }

data class Transcript(val text: String, val tier: Tier)

interface Transcriber {
    suspend fun status(): CapabilityStatus
    /** Transcribe an audio file. Throws CapabilityBusyException when the
     *  backing service asks us to back off; the pipeline demotes instead
     *  of surfacing an error. */
    suspend fun transcribe(audioPath: String): Transcript
}

interface Summarizer {
    suspend fun status(): CapabilityStatus
    /** Returns a title and a short summary for a transcript. */
    suspend fun summarize(transcript: String): TitleSummary
}

data class TitleSummary(val title: String, val summary: String)

interface ActionExtractor {
    suspend fun status(): CapabilityStatus
    suspend fun extract(transcript: String): List<ExtractedAction>
}

data class ExtractedAction(val text: String, val dueAtEpochMs: Long? = null)

interface Bucketer {
    /** Filing is configuration-driven: bucket sets come from a
     *  BucketProfileProvider so white-label builds can swap them. */
    suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment
}

data class BucketAssignment(val bucketKind: String, val tags: List<String>, val tier: Tier)

data class BucketProfile(val buckets: List<BucketDef>)

data class BucketDef(val kind: String, val name: String, val keywords: List<String>)

class CapabilityBusyException : Exception("Capability busy or over quota; demote and retry later")

/** Extension point: downstream modules (Pro, white-label) supply their
 *  own bucket profiles without touching the core pipeline. */
interface BucketProfileProvider {
    fun profile(): BucketProfile
}

object DefaultBucketProfileProvider : BucketProfileProvider {
    override fun profile() = BucketProfile(
        listOf(
            BucketDef("IDEA", "Ideas", listOf("idea", "what if", "we should build")),
            BucketDef("TASK", "Tasks", listOf("remind me", "need to", "don't forget", "buy", "call")),
            BucketDef("LIST", "Lists", listOf("list", "groceries", "shopping")),
            BucketDef("NOTE", "Notes", emptyList()),
            BucketDef("INBOX", "Inbox", emptyList())
        )
    )
}
