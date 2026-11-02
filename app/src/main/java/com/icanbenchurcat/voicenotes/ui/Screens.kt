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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

    Column(modifier = Modifier.fillMaxSize()) {
        if (deviceTier != null) {
            Text(
                text = "Device capability tier: ${deviceTier.name}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (notes.isEmpty()) {
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
                    text = "No notes need review.",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteDetailScreen(
    note: Note,
    actions: List<ActionItem> = emptyList(),
    tags: List<String> = emptyList(),
    transcriberStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    availableBuckets: List<String> = DefaultBucketProfileProvider.profile().buckets.map { it.kind },
    onConfirmOrChangeBucket: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = note.title.ifBlank { "Untitled note" },
            style = MaterialTheme.typography.headlineSmall
        )
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
