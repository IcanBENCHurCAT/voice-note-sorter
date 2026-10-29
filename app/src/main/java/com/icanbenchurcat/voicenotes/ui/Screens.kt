package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.data.Note

/** Notes list: filed cards, newest first. The bucket chip and the tier
 *  marker are shown on purpose - the demo value of this app is that the
 *  user (and the developer reading the code) can see what the ladder did. */
@Composable
fun NotesListScreen(notes: List<Note>, onOpen: (Note) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(notes, key = { it.id }) { note ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
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
                    Text(
                        text = "${note.bucketKind} · ${note.tierUsed}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun NoteDetailScreen(note: Note) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = note.title.ifBlank { "Untitled note" },
            style = MaterialTheme.typography.headlineSmall
        )
        Text(text = note.bucketKind, style = MaterialTheme.typography.labelMedium)
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
        if (note.stageStatus.isNotBlank()) {
            Text(
                text = "Pipeline: ${note.stageStatus}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
