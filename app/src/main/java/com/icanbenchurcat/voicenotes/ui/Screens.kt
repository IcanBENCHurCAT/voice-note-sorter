// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import com.icanbenchurcat.voicenotes.audio.AudioPlaybackState
import com.icanbenchurcat.voicenotes.audio.PlaybackStatus
import com.icanbenchurcat.voicenotes.audio.rememberAudioPlayerState

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
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus

/** Notes list: filed cards, newest first. The bucket chip and the tier
 *  marker are shown on purpose - the demo value of this app is that the
 *  user (and the developer reading the code) can see what the ladder did. */
@Composable
fun NotesListScreen(
    notes: List<Note>,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    deviceTier: Tier? = null,
    onManageBuckets: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpen: (Note) -> Unit
) {
    var filterNeedsReviewOnly by rememberSaveable { mutableStateOf(false) }
    val needsReviewCount = notes.count { it.status == NoteStatus.NEEDS_REVIEW.name }
    val displayedNotes = if (filterNeedsReviewOnly) {
        notes.filter { it.status == NoteStatus.NEEDS_REVIEW.name }
    } else {
        notes
    }

    val isSearching = searchQuery.isNotBlank()
    val isLibraryEmpty = notes.isEmpty() && !isSearching

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !filterNeedsReviewOnly,
                    onClick = { filterNeedsReviewOnly = false },
                    label = { Text("All (${notes.size})") },
                    modifier = Modifier.semantics { contentDescription = "Filter all notes" }
                )
                FilterChip(
                    selected = filterNeedsReviewOnly,
                    onClick = { filterNeedsReviewOnly = !filterNeedsReviewOnly },
                    label = { Text("Needs review ($needsReviewCount)") },
                    modifier = Modifier.semantics { contentDescription = "Filter Needs Review notes" }
                )
            }
            if (onManageBuckets != null) {
                OutlinedButton(
                    onClick = onManageBuckets,
                    modifier = Modifier.semantics { contentDescription = "Manage buckets" }
                ) {
                    Text(stringResource(R.string.action_manage_buckets))
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
                    } else {
                        "No notes need review."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(displayedNotes, key = { it.id }) { note ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable { onOpen(note) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = note.title.ifBlank { "Untitled note" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (note.summary.isNotBlank()) {
                            Text(
                                text = note.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2
                            )
                        }
                        val metaDesc = stringResource(R.string.cd_note_meta, note.bucketKind, note.tierUsed)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${note.bucketKind} · ${note.tierUsed}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.semantics { contentDescription = metaDesc }
                            )
                            if (note.status == NoteStatus.NEEDS_REVIEW.name) {
                                Text(
                                    text = "Needs review",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Needs review badge"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioPlaybackControl(
    audioPlaybackState: AudioPlaybackState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.audio_playback_title),
                style = MaterialTheme.typography.titleSmall
            )

            if (!audioPlaybackState.isFileAvailable || audioPlaybackState.errorMessage != null) {
                Text(
                    text = audioPlaybackState.errorMessage ?: stringResource(R.string.audio_missing_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isPlaying = audioPlaybackState.status == PlaybackStatus.PLAYING
                    val playPauseDesc = if (isPlaying) {
                        stringResource(R.string.cd_pause_audio)
                    } else {
                        stringResource(R.string.cd_play_audio)
                    }

                    FilledTonalButton(
                        onClick = { audioPlaybackState.togglePlayPause() },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { contentDescription = playPauseDesc }
                    ) {
                        Text(if (isPlaying) "Pause" else "Play")
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        LinearProgressIndicator(
                            progress = { audioPlaybackState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = audioPlaybackState.elapsedFormatted,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = audioPlaybackState.durationFormatted,
                                style = MaterialTheme.typography.labelSmall
                            )
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
    onDeleteNote: () -> Unit = {},
    audioPlaybackState: AudioPlaybackState = rememberAudioPlayerState(note.audioPath),
    onSaveEdit: (title: String, transcript: String) -> Unit = { _, _ -> },
    onShareText: () -> Unit = {},
    onShareAudio: () -> Unit = {},
    hasAudioFile: Boolean = false,
    onManageBuckets: (() -> Unit)? = null
) {
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var editTitle by rememberSaveable(note.id, isEditing) { mutableStateOf(note.title) }
    var editTranscript by rememberSaveable(note.id, isEditing) { mutableStateOf(note.transcript) }
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
        if (isEditing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        isEditing = false
                        editTitle = note.title
                        editTranscript = note.transcript
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Cancel editing note" }
                ) {
                    Text(stringResource(R.string.action_cancel_edit))
                }
                OutlinedButton(
                    onClick = {
                        isEditing = false
                        onSaveEdit(editTitle, editTranscript)
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Save edited note" }
                ) {
                    Text(stringResource(R.string.action_save_note))
                }
            }

            OutlinedTextField(
                value = editTitle,
                onValueChange = { editTitle = it },
                label = { Text(stringResource(R.string.edit_title_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .semantics { contentDescription = "Edit title input" }
            )

            OutlinedTextField(
                value = editTranscript,
                onValueChange = { editTranscript = it },
                label = { Text(stringResource(R.string.edit_transcript_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .weight(1f, fill = false)
                    .semantics { contentDescription = "Edit transcript input" },
                minLines = 4
            )
        } else {
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            editTitle = note.title
                            editTranscript = note.transcript
                            isEditing = true
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { contentDescription = "Edit note" }
                    ) {
                        Text(stringResource(R.string.action_edit_note))
                    }
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
            }

            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onShareText,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Share note text" }
                ) {
                    Text(stringResource(R.string.action_share_text))
                }
                if (hasAudioFile) {
                    OutlinedButton(
                        onClick = onShareAudio,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { contentDescription = "Share note audio" }
                    ) {
                        Text(stringResource(R.string.action_share_audio))
                    }
                }
            }

            val bucketDesc = stringResource(R.string.cd_note_bucket, note.bucketKind)
            Row(
                modifier = Modifier.padding(top = 8.dp),
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
                    modifier = Modifier.padding(top = 16.dp)
                )
                actions.forEach { item ->
                    val marker = if (item.done) "[x]" else "[ ]"
                    Text(
                        text = "$marker ${item.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
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
        AudioPlaybackControl(
            audioPlaybackState = audioPlaybackState,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}
