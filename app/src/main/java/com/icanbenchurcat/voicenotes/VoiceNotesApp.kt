// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import android.app.Application
import com.icanbenchurcat.voicenotes.ai.DefaultBucketProfileProvider
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Bucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VoiceNotesApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Eagerly open the database and seed the default bucket profile so
        // the first capture is not paying schema setup and default buckets
        // are always present. Nothing else starts here: no analytics, no
        // network, no SDK parades. Local-only is the product.
        val db = AppDatabase.get(this)
        appScope.launch {
            val defaultBuckets = DefaultBucketProfileProvider.profile().buckets.map {
                Bucket(kind = it.kind, name = it.name)
            }
            db.noteDao().insertBuckets(defaultBuckets)
        }
    }
}
