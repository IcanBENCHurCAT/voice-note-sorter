// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

/**
 * The capability ladder contracts, published as the `:extension-api`
 * module (ADR 0002 Trigger #1; ADR 0011). Every on-device AI feature is
 * a capability to probe, never a boolean to assume. UI and pipeline
 * code depend on these interfaces only.
 *
 * This module holds contracts only: no implementations, no UI, and no
 * ML Kit references. The open core's implementations live in `:app`;
 * a downstream module supplies its own implementations through the
 * same interfaces and registers them in [ExtensionRegistry].
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

interface Proofreader {
    suspend fun status(): CapabilityStatus
    /** Cleans punctuation, capitalization, and grammar of [rawTranscript]
     *  into a working transcript without altering facts, names, or numbers.
     *  Throws [CapabilityBusyException] when the backing service asks us to
     *  back off; the pipeline demotes instead of surfacing an error. */
    suspend fun proofread(rawTranscript: String): String
}

interface ImageTranscriber {
    suspend fun status(): CapabilityStatus
    /** Transcribe handwritten or printed text from an image file on disk.
     *  Throws CapabilityBusyException when the backing service asks us
     *  to back off; the pipeline demotes instead of surfacing an error. */
    suspend fun transcribeImage(imagePath: String): Transcript
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
    suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE

    /** Filing is configuration-driven: bucket sets come from a
     *  BucketProfileProvider so white-label builds can swap them. */
    suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment
}

data class BucketAssignment(
    val bucketKind: String,
    val tags: List<String>,
    val tier: Tier,
    val confidence: Float = 1.0f,
    val score: Int = 1,
    val needsReview: Boolean = false
)

data class BucketProfile(val buckets: List<BucketDef>)

data class BucketDef(val kind: String, val name: String, val keywords: List<String>)

interface Embedder {
    /** Stable identifier for the active embedding model and vector space. */
    val modelId: String
    suspend fun status(): CapabilityStatus
    /** Generates an embedding vector for the text. Returns null when unsupported on the active tier. */
    suspend fun embed(text: String): FloatArray?
}

class CapabilityBusyException : Exception("Capability busy or over quota; demote and retry later")

/** Extension point: downstream modules supply their own bucket profiles
 *  without touching the core pipeline. */
interface BucketProfileProvider {
    fun profile(): BucketProfile
}
