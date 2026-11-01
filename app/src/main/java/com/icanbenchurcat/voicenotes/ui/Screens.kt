// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.Note

/** Notes list: filed cards, newest first. The bucket chip and the tier
 *  marker are shown on purpose - the demo value of this app is that the
 *  user (and the developer reading the code) can see what the ladder did. */
@Composable
fun NotesListScreen(notes: List<Note>, onOpen: (Note) -> Unit) {
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(notes, key = { it.id }) { note ->
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
                    Text(
                        text = "${note.bucketKind} · ${note.tierUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.semantics { contentDescription = metaDesc }
                    )
                }
            }
        }
    }
}

@Composable
fun NoteDetailScreen(
    note: Note,
    actions: List<ActionItem> = emptyList(),
    tags: List<String> = emptyList()
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
        Text(
            text = note.bucketKind,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.semantics { contentDescription = bucketDesc }
        )
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
