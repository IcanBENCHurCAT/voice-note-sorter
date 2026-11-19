// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import com.icanbenchurcat.voicenotes.ai.DefaultBucketProfileProvider
import com.icanbenchurcat.voicenotes.data.Bucket
import java.text.SimpleDateFormat
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    /**
     * Formats a due date epoch timestamp in milliseconds for task items across
     * both NoteDetailScreen and TasksScreen.
     */
    fun formatDueDate(dueAtEpochMs: Long): String =
        formatDueDate(dueAtEpochMs, Locale.getDefault(), TimeZone.getDefault())

    internal fun formatDueDate(
        dueAtEpochMs: Long,
        locale: Locale,
        timeZone: TimeZone
    ): String {
        if (dueAtEpochMs <= 0L) return ""
        val format = SimpleDateFormat("MMM d, h:mm a", locale).apply {
            this.timeZone = timeZone
        }
        return format.format(Date(dueAtEpochMs))
    }

    /**
     * Formats the calendar date portion of a due timestamp for picker buttons.
     */
    fun formatDueDateOnly(dueAtEpochMs: Long): String =
        formatDueDateOnly(dueAtEpochMs, Locale.getDefault(), TimeZone.getDefault())

    internal fun formatDueDateOnly(
        dueAtEpochMs: Long,
        locale: Locale,
        timeZone: TimeZone
    ): String {
        if (dueAtEpochMs <= 0L) return ""
        val format = SimpleDateFormat("MMM d, yyyy", locale).apply {
            this.timeZone = timeZone
        }
        return format.format(Date(dueAtEpochMs))
    }

    /**
     * Formats the time-of-day portion of a due timestamp for picker buttons.
     */
    fun formatDueTimeOnly(dueAtEpochMs: Long): String =
        formatDueTimeOnly(dueAtEpochMs, Locale.getDefault(), TimeZone.getDefault())

    internal fun formatDueTimeOnly(
        dueAtEpochMs: Long,
        locale: Locale,
        timeZone: TimeZone
    ): String {
        if (dueAtEpochMs <= 0L) return ""
        val format = SimpleDateFormat("h:mm a", locale).apply {
            this.timeZone = timeZone
        }
        return format.format(Date(dueAtEpochMs))
    }

    /**
     * Converts a local-zone due epoch timestamp (or today if unset) into the
     * UTC midnight milliseconds expected by Material 3 `DatePickerState`.
     */
    fun toDatePickerInitialUtcMillis(
        dueAtEpochMs: Long?,
        clock: Clock = Clock.systemDefaultZone()
    ): Long {
        val localDate = dueAtEpochMs
            ?.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(clock.zone).toLocalDate() }
            ?: LocalDate.now(clock)
        return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /**
     * Applies a UTC midnight date selection from Material 3 `DatePickerState`
     * while preserving any previously chosen local time (or 9:00 AM by default).
     */
    fun mergePickedDate(
        existingDueAtEpochMs: Long?,
        utcSelectedDateMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val pickedDate = Instant.ofEpochMilli(utcSelectedDateMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
        val preservedTime = existingDueAtEpochMs
            ?.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            ?: LocalTime.of(9, 0)
        return LocalDateTime.of(pickedDate, preservedTime)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Applies a local hour/minute selection from Material 3 `TimePickerState`
     * while preserving any previously chosen local date (or today by default).
     */
    fun mergePickedTime(
        existingDueAtEpochMs: Long?,
        hour: Int,
        minute: Int,
        clock: Clock = Clock.systemDefaultZone()
    ): Long {
        val baseDate = existingDueAtEpochMs
            ?.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(clock.zone).toLocalDate() }
            ?: LocalDate.now(clock)
        val pickedTime = LocalTime.of(hour, minute)
        return LocalDateTime.of(baseDate, pickedTime)
            .atZone(clock.zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Formats a raw comma-separated stage status string (e.g. "transcribe:OK,bucket:OK")
     * into a human-friendly list of stage and status pairs.
     */
    fun formatPipelineStages(rawStageStatus: String): List<Pair<String, String>> {
        if (rawStageStatus.isBlank()) return emptyList()
        return rawStageStatus.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val stage = parts[0].trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                val status = parts[1].trim()
                Pair(stage, status)
            } else {
                null
            }
        }
    }

    /**
     * Collapses a list of tags if its size exceeds [maxVisible], returning the visible tags
     * (up to [maxVisible]) and the count of collapsed overflow tags.
     */
    fun collapseTags(tags: List<String>, maxVisible: Int = 3): Pair<List<String>, Int> {
        return if (tags.size > maxVisible) {
            Pair(tags.take(maxVisible), tags.size - maxVisible)
        } else {
            Pair(tags, 0)
        }
    }

    /**
     * Resolves the human-facing display name for [bucketKind] using [buckets] first,
     * falling back to [DefaultBucketProfileProvider] defaults and title-casing unknown slugs.
     */
    fun formatBucketDisplayName(bucketKind: String, buckets: List<Bucket> = emptyList()): String {
        val matched = buckets.firstOrNull { it.kind.equals(bucketKind, ignoreCase = true) }?.name
        if (!matched.isNullOrBlank()) return matched

        val defaultMatched = DefaultBucketProfileProvider.profile().buckets
            .firstOrNull { it.kind.equals(bucketKind, ignoreCase = true) }?.name
        if (!defaultMatched.isNullOrBlank()) return defaultMatched

        val trimmed = bucketKind.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.replace('_', ' ')
            .lowercase(Locale.ROOT)
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
    }
}
