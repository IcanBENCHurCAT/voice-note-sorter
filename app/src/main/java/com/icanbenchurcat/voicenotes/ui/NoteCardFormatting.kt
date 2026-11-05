// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoteCardFormatting {

    /**
     * Deduplicates summary text when it is identical to the title, or strips
     * the title prefix if the summary repeats the title at the beginning.
     */
    fun deduplicateSummary(title: String, summary: String): String {
        val trimmedTitle = title.trim()
        val trimmedSummary = summary.trim()

        if (trimmedSummary.isEmpty()) return ""
        if (trimmedTitle.isEmpty()) return trimmedSummary

        // 1. If summary is identical or identical ignoring case and trailing punctuation
        val normTitle = normalize(trimmedTitle)
        val normSummary = normalize(trimmedSummary)
        if (normTitle == normSummary) {
            return ""
        }

        // 2. If summary starts with title (e.g. "Buy groceries. Also pick up milk." with title "Buy groceries")
        if (trimmedSummary.startsWith(trimmedTitle, ignoreCase = true)) {
            val remainder = trimmedSummary.substring(trimmedTitle.length).trim()
            val strippedRemainder = remainder.trimStart('.', ',', ':', ';', '-', '—', ' ').trim()
            return strippedRemainder
        }

        return trimmedSummary
    }

    private fun normalize(str: String): String =
        str.trim().trimEnd('.', '!', '?', ',', ':', ';', '-', ' ').lowercase(Locale.ROOT)

    /**
     * Formats an epoch timestamp in milliseconds into a concise, human-readable date/time string.
     */
    fun formatTimestamp(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0L) return ""
        val diffMs = nowMs - epochMs
        val diffSec = diffMs / 1000
        val diffMin = diffSec / 60
        val diffHours = diffMin / 60
        val diffDays = diffHours / 24

        return when {
            diffMin < 1 -> "Just now"
            diffMin < 60 -> "${diffMin}m ago"
            diffHours < 24 -> "${diffHours}h ago"
            diffDays == 1L -> "Yesterday"
            diffDays < 7 -> "${diffDays}d ago"
            else -> {
                val format = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                format.format(Date(epochMs))
            }
        }
    }
}
