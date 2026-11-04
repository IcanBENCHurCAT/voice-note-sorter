// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.DefaultBucketProfileProvider
import com.icanbenchurcat.voicenotes.ai.Tier
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.ActionItemWithNote
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.data.NoteTag

/** Notes list: filed cards, newest first. The bucket chip and the tier
 *  marker are shown on purpose - the demo value of this app is that the
 *  user (and the developer reading the code) can see what the ladder did. */
@Composable
fun NotesListScreen(
    notes: List<Note>,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    deviceTier: Tier? = null,
    buckets: List<Bucket> = emptyList(),
    tasks: List<ActionItemWithNote> = emptyList(),
    tags: List<String> = emptyList(),
    noteTags: List<NoteTag> = emptyList(),
    onManageBuckets: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpen: (Note) -> Unit
) {
    var selectedBucketKind by rememberSaveable { mutableStateOf<String?>(null) }
    var filterNeedsReviewOnly by rememberSaveable { mutableStateOf(false) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }

    val needsReviewCount = notes.count { it.status == NoteStatus.NEEDS_REVIEW.name }

    val displayedNotes = notes.filter { note ->
        val matchesReview = !filterNeedsReviewOnly || note.status == NoteStatus.NEEDS_REVIEW.name
        val matchesBucket = selectedBucketKind == null || note.bucketKind == selectedBucketKind
        val matchesTag = selectedTag == null || noteTags.any { it.noteId == note.id && it.tagName == selectedTag }
        matchesReview && matchesBucket && matchesTag
    }

    val isSearching = searchQuery.isNotBlank()
    val isLibraryEmpty = notes.isEmpty() && !isSearching

    // Precalculate task counts per note (done / total)
    val taskCountsByNote = remember(tasks) {
        tasks.groupBy { it.noteId }.mapValues { (_, items) ->
            val doneCount = items.count { it.done }
            Pair(doneCount, items.size)
        }
    }

    // Precalculate tags per note
    val tagsByNote = remember(noteTags) {
        noteTags.groupBy { it.noteId }.mapValues { (_, items) ->
            items.map { it.tagName }.sorted()
        }
    }

    // Active buckets list (fallback to defaults if DB buckets not loaded yet)
    val effectiveBuckets = if (buckets.isNotEmpty()) {
        buckets
    } else {
        DefaultBucketProfileProvider.profile().buckets.map { Bucket(kind = it.kind, name = it.name) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tierText = when (deviceTier) {
                Tier.FULL -> stringResource(R.string.tier_badge_full)
                Tier.CORE -> stringResource(R.string.tier_badge_core)
                Tier.MINIMAL, null -> stringResource(R.string.tier_badge_basic)
            }
            Text(
                text = "Device capability: $tierText",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable(enabled = onOpenSettings != null) { onOpenSettings?.invoke() }
            )
            if (onOpenSettings != null) {
                TextButton(onClick = onOpenSettings) {
                    Text(
                        text = stringResource(R.string.action_settings),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        if (isLibraryEmpty) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No voice notes yet. Tap Record to capture a thought.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "Search notes" },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            trailingIcon = {
                if (isSearching) {
                    TextButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.semantics {
                            contentDescription = "Clear search"
                        }
                    ) {
                        Text("✕")
                    }
                }
            }
        )

        // Horizontal filter bar: All, Needs Review, Bucket chips, and Manage Buckets
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isAllSelected = selectedBucketKind == null && !filterNeedsReviewOnly && selectedTag == null
                FilterChip(
                    selected = isAllSelected,
                    onClick = {
                        selectedBucketKind = null
                        filterNeedsReviewOnly = false
                        selectedTag = null
                    },
                    label = { Text("All (${notes.size})") },
                    modifier = Modifier.semantics { contentDescription = "Filter all notes" }
                )
                FilterChip(
                    selected = filterNeedsReviewOnly,
                    onClick = {
                        filterNeedsReviewOnly = !filterNeedsReviewOnly
                        if (filterNeedsReviewOnly) {
                            selectedBucketKind = null
                            selectedTag = null
                        }
                    },
                    label = { Text("Needs review ($needsReviewCount)") },
                    modifier = Modifier.semantics { contentDescription = "Filter Needs Review notes" }
                )
                effectiveBuckets.forEach { b ->
                    val bucketCount = notes.count { it.bucketKind == b.kind }
                    val isBucketSelected = selectedBucketKind == b.kind
                    FilterChip(
                        selected = isBucketSelected,
                        onClick = {
                            selectedBucketKind = if (isBucketSelected) null else b.kind
                            if (selectedBucketKind != null) {
                                filterNeedsReviewOnly = false
                            }
                        },
                        label = { Text("${b.name} ($bucketCount)") },
                        modifier = Modifier.semantics { contentDescription = "Filter by bucket ${b.name}" }
                    )
                }
            }
            if (onManageBuckets != null) {
                OutlinedButton(
                    onClick = onManageBuckets,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .semantics { contentDescription = "Manage buckets" }
                ) {
                    Text(stringResource(R.string.action_manage_buckets))
                }
            }
        }

        // Tag filter chips (if tags exist in library)
        if (tags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tags:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                tags.forEach { tagName ->
                    val isSelected = selectedTag == tagName
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedTag = if (isSelected) null else tagName
                            if (selectedTag != null) {
                                filterNeedsReviewOnly = false
                            }
                        },
                        label = { Text("#$tagName") },
                        modifier = Modifier.semantics { contentDescription = "Filter by tag $tagName" }
                    )
                }
            }
        }

        if (displayedNotes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSearching) {
                        stringResource(R.string.search_no_matches)
                    } else if (filterNeedsReviewOnly) {
                        "No notes need review."
                    } else if (selectedBucketKind != null) {
                        "No notes in this bucket."
                    } else if (selectedTag != null) {
                        "No notes with tag #$selectedTag."
                    } else {
                        "No notes found."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(displayedNotes, key = { it.id }) { note ->
                val deduplicatedSummary = remember(note.title, note.summary) {
                    NoteCardFormatting.deduplicateSummary(note.title, note.summary)
                }
                val formattedTime = remember(note.createdAtEpochMs) {
                    NoteCardFormatting.formatTimestamp(note.createdAtEpochMs)
                }
                val noteTasks = taskCountsByNote[note.id]
                val noteTagList = tagsByNote[note.id] ?: emptyList()

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable { onOpen(note) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = note.title.ifBlank { "Untitled note" },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (formattedTime.isNotBlank()) {
                                Text(
                                    text = formattedTime,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }

                        if (deduplicatedSummary.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = deduplicatedSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val metaDesc = stringResource(R.string.cd_note_meta, note.bucketKind, note.tierUsed)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = "${note.bucketKind} · ${note.tierUsed}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                border = null,
                                modifier = Modifier.semantics { contentDescription = metaDesc }
                            )

                            if (note.status == NoteStatus.NEEDS_REVIEW.name) {
                                SuggestionChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = "Needs review",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        labelColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    border = null,
                                    modifier = Modifier.semantics { contentDescription = "Needs review badge" }
                                )
                            }

                            if (noteTasks != null && noteTasks.second > 0) {
                                val (doneCount, totalCount) = noteTasks
                                SuggestionChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = "Tasks $doneCount/$totalCount",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    border = null,
                                    modifier = Modifier.semantics { contentDescription = "Tasks: $doneCount of $totalCount completed" }
                                )
                            }
                        }

                        if (noteTagList.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                noteTagList.forEach { tag ->
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteDetailScreen(
    note: Note,
    actions: List<ActionItem> = emptyList(),
    tags: List<String> = emptyList(),
    transcriberStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    availableBuckets: List<String> = DefaultBucketProfileProvider.profile().buckets.map { it.kind },
    buckets: List<Bucket> = emptyList(),
    onConfirmOrChangeBucket: (String) -> Unit = {},
    onToggleActionDone: ((Long, Boolean) -> Unit)? = null,
    onDeleteNote: () -> Unit = {},
    onManageBuckets: (() -> Unit)? = null
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.dialog_delete_note_title)) },
            text = { Text(stringResource(R.string.dialog_delete_note_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteNote()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.semantics { contentDescription = "Confirm delete note" }
                ) {
                    Text(stringResource(R.string.dialog_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                    modifier = Modifier.semantics { contentDescription = "Cancel delete note" }
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = note.title.ifBlank { "Untitled note" },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f, fill = false)
            )
            OutlinedButton(
                onClick = { showDeleteConfirmDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Delete note" }
            ) {
                Text(stringResource(R.string.action_delete_note))
            }
        }
        val bucketDesc = stringResource(R.string.cd_note_bucket, note.bucketKind)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = note.bucketKind,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { contentDescription = bucketDesc }
            )
            if (note.status == NoteStatus.NEEDS_REVIEW.name) {
                Text(
                    text = "Needs review",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { contentDescription = "Needs review status" }
                )
            }
        }
        if (note.status == NoteStatus.NEEDS_REVIEW.name) {
            OutlinedButton(
                onClick = { onConfirmOrChangeBucket(note.bucketKind) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { contentDescription = "Confirm bucket filing" }
            ) {
                Text("Confirm filing (${note.bucketKind})")
            }
        }
        val effectiveBuckets = remember(availableBuckets, buckets) {
            if (buckets.isNotEmpty()) {
                buckets.map { it.kind to it.name }
            } else {
                availableBuckets.map { it to it }
            }
        }
        if (effectiveBuckets.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                effectiveBuckets.forEach { (bucketKind, bucketName) ->
                    FilterChip(
                        selected = note.bucketKind == bucketKind && note.status == NoteStatus.SORTED.name,
                        onClick = { onConfirmOrChangeBucket(bucketKind) },
                        label = { Text(bucketName) },
                        modifier = Modifier.semantics {
                            contentDescription = "File in $bucketKind"
                        }
                    )
                }
                if (onManageBuckets != null) {
                    TextButton(
                        onClick = onManageBuckets,
                        modifier = Modifier.semantics { contentDescription = "Manage buckets" }
                    ) {
                        Text(stringResource(R.string.action_manage_buckets))
                    }
                }
            }
        }
        if (tags.isNotEmpty()) {
            Text(
                text = "Tags: ${tags.joinToString(", ") { "#$it" }}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (note.transcript.isNotBlank()) {
            Text(
                text = note.transcript,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            Text(
                text = "No transcript yet. Playback is available; this note was filed by hand (Tier 1).",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            if (transcriberStatus == CapabilityStatus.DOWNLOADABLE) {
                Text(
                    text = "On-device speech model is downloadable; note can be upgraded once downloaded.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        if (actions.isNotEmpty()) {
            Text(
                text = "Action items",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            actions.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(enabled = onToggleActionDone != null) {
                            onToggleActionDone?.invoke(item.id, !item.done)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.done,
                        onCheckedChange = { done ->
                            onToggleActionDone?.invoke(item.id, done)
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Toggle action item: ${item.text}"
                        }
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) {
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (item.dueAtEpochMs != null && item.dueAtEpochMs > 0L) {
                            val formattedDue = NoteCardFormatting.formatDueDate(item.dueAtEpochMs)
                            if (formattedDue.isNotBlank()) {
                                Text(
                                    text = "Due: $formattedDue",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (note.stageStatus.isNotBlank()) {
            val pipelineDesc = stringResource(R.string.cd_note_pipeline, note.stageStatus)
            Text(
                text = "Pipeline: ${note.stageStatus}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .semantics { contentDescription = pipelineDesc }
            )
        }
    }
}
