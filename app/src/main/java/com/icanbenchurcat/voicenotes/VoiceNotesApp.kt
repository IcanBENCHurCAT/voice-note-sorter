package com.icanbenchurcat.voicenotes

import android.app.Application
import com.icanbenchurcat.voicenotes.data.AppDatabase

class VoiceNotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Eagerly open the database so the first capture is not paying
        // schema setup. Nothing else starts here: no analytics, no
        // network, no SDK parades. Local-only is the product.
        AppDatabase.get(this)
    }
}
