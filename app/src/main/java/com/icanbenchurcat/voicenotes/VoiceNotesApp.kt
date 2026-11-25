// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import android.app.Application
import com.icanbenchurcat.voicenotes.ai.DefaultBucketProfileProvider
import com.icanbenchurcat.voicenotes.ai.ExtensionRegistry
import com.icanbenchurcat.voicenotes.ai.NanoActionExtractor
import com.icanbenchurcat.voicenotes.ai.NanoBucketer
import com.icanbenchurcat.voicenotes.ai.NanoImageTranscriber
import com.icanbenchurcat.voicenotes.ai.NanoProofreader
import com.icanbenchurcat.voicenotes.ai.NanoSummarizer
import com.icanbenchurcat.voicenotes.ai.NanoTranscriber
import com.icanbenchurcat.voicenotes.ai.NoOpEmbedder
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.BucketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VoiceNotesApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Register the open core's free capability implementations at
        // the extension seam (ADR 0002, ADR 0011). These are the same
        // primary implementations the sorting pipeline constructs
        // today. Registration only: existing consumers (SortingWorker,
        // the search repository) still construct and own their
        // instances exactly as before, and nothing reads the registry
        // yet. Downstream modules replace implementations by
        // registering their own from their composition root.
        ExtensionRegistry.register(
            transcriber = NanoTranscriber(this),
            proofreader = NanoProofreader(),
            imageTranscriber = NanoImageTranscriber(this),
            summarizer = NanoSummarizer(),
            actionExtractor = NanoActionExtractor(),
            bucketer = NanoBucketer(),
            embedder = NoOpEmbedder(),
            bucketProfileProvider = DefaultBucketProfileProvider
        )
        // Eagerly open the database and seed the default bucket profile so
        // the first capture is not paying schema setup and default buckets
        // are always present. Nothing else starts here: no analytics, no
        // network, no SDK parades. Local-only is the product.
        val db = AppDatabase.get(this)
        appScope.launch {
            val bucketManager = BucketManager(db.noteDao())
            bucketManager.seedDefaultsIfEmpty()
        }
    }
}
