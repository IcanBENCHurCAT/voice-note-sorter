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
        if (deviceTier != null) {
            Text(
                text = "Device capability: $deviceTier",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
    onConfirmOrChangeBucket: (String) -> Unit = {},
    onDeleteNote: () -> Unit = {},
    audioPlaybackState: AudioPlaybackState = rememberAudioPlayerState(note.audioPath)
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
        if (availableBuckets.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableBuckets.forEach { bucketKind ->
                    FilterChip(
                        selected = note.bucketKind == bucketKind && note.status == NoteStatus.SORTED.name,
                        onClick = { onConfirmOrChangeBucket(bucketKind) },
                        label = { Text(bucketKind) },
                        modifier = Modifier.semantics {
                            contentDescription = "File in $bucketKind"
                        }
                    )
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
        AudioPlaybackControl(
            audioPlaybackState = audioPlaybackState,
            modifier = Modifier.padding(top = 16.dp)
        )

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
