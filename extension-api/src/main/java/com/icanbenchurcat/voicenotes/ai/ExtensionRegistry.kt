// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

/**
 * The composition seam for capability implementations (ADR 0002;
 * extraction recorded in ADR 0011).
 *
 * The host application registers one implementation per capability
 * contract at startup, from its composition root. The open core
 * registers its free implementations (see VoiceNotesApp in `:app`).
 * A downstream module that replaces an implementation registers its
 * own through the same seam, in its own composition root, without
 * modifying core code.
 *
 * Registration is last-write-wins and is expected to happen once,
 * before any consumer reads the registry. Every property is null
 * until the matching implementation is registered; consumers that
 * require an implementation must handle the unregistered case or
 * register a default of their own.
 */
object ExtensionRegistry {

    @Volatile
    var transcriber: Transcriber? = null
        private set

    @Volatile
    var proofreader: Proofreader? = null
        private set

    @Volatile
    var imageTranscriber: ImageTranscriber? = null
        private set

    @Volatile
    var summarizer: Summarizer? = null
        private set

    @Volatile
    var actionExtractor: ActionExtractor? = null
        private set

    @Volatile
    var bucketer: Bucketer? = null
        private set

    @Volatile
    var embedder: Embedder? = null
        private set

    @Volatile
    var bucketProfileProvider: BucketProfileProvider? = null
        private set

    /**
     * Register implementations for one or more capabilities. A null
     * argument leaves the current registration for that capability
     * untouched, so a composition root can register exactly the
     * implementations it constructs.
     */
    fun register(
        transcriber: Transcriber? = null,
        proofreader: Proofreader? = null,
        imageTranscriber: ImageTranscriber? = null,
        summarizer: Summarizer? = null,
        actionExtractor: ActionExtractor? = null,
        bucketer: Bucketer? = null,
        embedder: Embedder? = null,
        bucketProfileProvider: BucketProfileProvider? = null
    ) {
        if (transcriber != null) this.transcriber = transcriber
        if (proofreader != null) this.proofreader = proofreader
        if (imageTranscriber != null) this.imageTranscriber = imageTranscriber
        if (summarizer != null) this.summarizer = summarizer
        if (actionExtractor != null) this.actionExtractor = actionExtractor
        if (bucketer != null) this.bucketer = bucketer
        if (embedder != null) this.embedder = embedder
        if (bucketProfileProvider != null) this.bucketProfileProvider = bucketProfileProvider
    }
}
