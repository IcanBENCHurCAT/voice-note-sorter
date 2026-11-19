// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.data.Note
import java.io.File

object NoteShareHelper {

    /**
     * Formats note content as plain text including title, summary (if present),
     * and transcript.
     */
    fun formatShareText(note: Note): String = buildString {
        if (note.title.isNotBlank()) {
            appendLine(note.title)
        }
        if (note.summary.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine("Summary: ${note.summary}")
        }
        if (note.transcript.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            append("Transcript: ${note.transcript}")
        }
    }.trim()

    /**
     * Creates an Intent to share note text via ACTION_SEND.
     */
    fun createSendTextIntent(note: Note): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title.ifBlank { "Voice note" })
            putExtra(Intent.EXTRA_TEXT, formatShareText(note))
        }
    }

    /**
     * Creates an Intent to share note text via ACTION_SEND wrapped in a chooser.
     */
    fun createShareTextIntent(note: Note, chooserTitle: String = "Share note"): Intent {
        return Intent.createChooser(createSendTextIntent(note), chooserTitle)
    }

    /**
     * Creates an Intent to share note audio via ACTION_SEND with a scoped FileProvider URI.
     * Returns null if audio file does not exist.
     */
    fun createShareAudioIntent(
        context: Context,
        note: Note,
        chooserTitle: String = "Share audio"
    ): Intent? = createShareFileIntent(
        context = context,
        path = note.audioPath,
        mimeType = "audio/*",
        chooserTitle = chooserTitle
    )

    /**
     * Creates an Intent to share note image via ACTION_SEND with a scoped FileProvider URI.
     * Returns null if image file does not exist.
     */
    fun createShareImageIntent(
        context: Context,
        note: Note,
        chooserTitle: String = "Share image"
    ): Intent? = createShareFileIntent(
        context = context,
        path = note.imagePath,
        mimeType = "image/*",
        chooserTitle = chooserTitle
    )

    private fun createShareFileIntent(
        context: Context,
        path: String,
        mimeType: String,
        chooserTitle: String
    ): Intent? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.isFile) return null

        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, chooserTitle)
    }
}
