// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

/**
 * The open core's free default implementations of the capability
 * contracts. The contracts themselves (Tier, CapabilityStatus, and the
 * capability interfaces) live in the `:extension-api` module (ADR 0011);
 * this file keeps the Tier 1 / no-op defaults that ship in `:app`.
 * ML Kit is referenced solely from the Nano implementations in this
 * package, never from the contracts.
 */

/** Tier 1: no image OCR/transcription. The attached image is kept on the
 *  note and marked NEEDS_REVIEW for manual entry and filing. */
object NullImageTranscriber : ImageTranscriber {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.UNAVAILABLE
    override suspend fun transcribeImage(imagePath: String): Transcript = Transcript("", Tier.MINIMAL)
}

class NoOpEmbedder : Embedder {
    override val modelId: String = "noop"
    override suspend fun status(): CapabilityStatus = CapabilityStatus.UNAVAILABLE
    override suspend fun embed(text: String): FloatArray? = null
}

object DefaultBucketProfileProvider : BucketProfileProvider {
    override fun profile() = BucketProfile(
        listOf(
            BucketDef(
                "IDEA",
                "Ideas",
                listOf(
                    "idea", "what if", "we should build", "brainstorm",
                    "concept", "proposal", "prototype", "feature idea", "design idea"
                )
            ),
            BucketDef(
                "TASK",
                "Tasks",
                listOf(
                    "remind me", "need to", "don't forget", "do not forget",
                    "remember to", "have to", "must", "buy", "call",
                    "pick up", "send", "email", "file", "create", "open",
                    "write", "fix", "debug", "investigate", "submit",
                    "finish", "follow up", "todo", "to-do", "tasks"
                )
            ),
            BucketDef(
                "LIST",
                "Lists",
                listOf(
                    "list", "groceries", "shopping", "checklist",
                    "packing list", "wish list", "reading list", "inventory"
                )
            ),
            BucketDef(
                "NOTE",
                "Notes",
                listOf(
                    "note", "voice note", "memo", "journal", "summary",
                    "observation", "thoughts", "reflection", "recording",
                    "transcription", "meeting notes", "status update", "recap"
                )
            ),
            BucketDef("INBOX", "Inbox", emptyList())
        )
    )
}
