// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.icanbenchurcat.voicenotes.ai.DefaultBucketProfileProvider
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.data.NoteTag
import com.icanbenchurcat.voicenotes.data.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only seed dataset and UI controls so the list, detail, buckets, tags,
 * and action items can be exercised end to end without a recorder.
 *
 * Lives strictly in `app/src/debug`; the `app/src/release` counterpart is a
 * no-op and contains no sample data.
 */
object DebugSeedData {

    suspend fun clear(db: AppDatabase) = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }

    suspend fun seed(db: AppDatabase) = withContext(Dispatchers.IO) {
        db.clearAllTables()
        val dao = db.noteDao()

        val defaultBuckets = DefaultBucketProfileProvider.profile().buckets.map {
            Bucket(kind = it.kind, name = it.name)
        }
        dao.insertBuckets(defaultBuckets)

        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L

        // 1. IDEA Note (Tier 2, Sorted)
        val ideaNoteId = dao.insert(
            Note(
                createdAtEpochMs = now - (3 * dayMs),
                audioPath = "/dev/null/idea.m4a",
                transcript = "I had this concept for a modular voice note sorting pipeline where we could package the core open-source app and leave extension points for closed plugins.",
                title = "Modular voice note sorting pipeline",
                summary = "Concept for a modular voice note sorting pipeline with an open core and extension seams for plugins.",
                bucketKind = "IDEA",
                tierUsed = "CORE",
                stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:OK",
                status = NoteStatus.SORTED.name
            )
        )

        // 2. TASK Note (Tier 2, Needs Review with action items and due dates)
        val taskNoteId = dao.insert(
            Note(
                createdAtEpochMs = now - (2 * dayMs),
                audioPath = "/dev/null/task.m4a",
                transcript = "Remember to call the electrician about the kitchen wiring by Friday at 5pm and pick up 14-gauge copper wire from Home Depot.",
                title = "Kitchen electrical work",
                summary = "Call electrician about kitchen wiring by Friday and pick up 14-gauge copper wire.",
                bucketKind = "TASK",
                tierUsed = "CORE",
                stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:OK",
                status = NoteStatus.NEEDS_REVIEW.name
            )
        )
        dao.insertActions(
            listOf(
                ActionItem(
                    noteId = taskNoteId,
                    text = "Call electrician about kitchen wiring",
                    dueAtEpochMs = now + (2 * dayMs),
                    done = false
                ),
                ActionItem(
                    noteId = taskNoteId,
                    text = "Pick up 14-gauge copper wire from Home Depot",
                    dueAtEpochMs = null,
                    done = true
                )
            )
        )

        // 3. LIST Note (Tier 2, Sorted)
        val listNoteId = dao.insert(
            Note(
                createdAtEpochMs = now - (1 * dayMs),
                audioPath = "/dev/null/list.m4a",
                transcript = "Grocery run list: sourdough bread, oat milk, honeycrisp apples, dark roast coffee beans, and unsalted butter.",
                title = "Grocery run items",
                summary = "Sourdough bread, oat milk, honeycrisp apples, coffee beans, and butter.",
                bucketKind = "LIST",
                tierUsed = "CORE",
                stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:OK",
                status = NoteStatus.SORTED.name
            )
        )

        // 4. NOTE (Tier 2, Sorted)
        dao.insert(
            Note(
                createdAtEpochMs = now - (12 * 60 * 60 * 1000L),
                audioPath = "/dev/null/note.m4a",
                transcript = "Interesting quote from the podcast: Simplicity is prerequisite for reliability. We should keep the Tier 1 fallback as lean as possible.",
                title = "Podcast quote on simplicity",
                summary = "Simplicity is prerequisite for reliability; keep Tier 1 fallback lean.",
                bucketKind = "NOTE",
                tierUsed = "CORE",
                stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:OK",
                status = NoteStatus.SORTED.name
            )
        )

        // 5. INBOX (Tier 1 Fallback - Minimal mode, filed by hand)
        dao.insert(
            Note(
                createdAtEpochMs = now - (2 * 60 * 60 * 1000L),
                audioPath = "/dev/null/inbox_tier1.m4a",
                transcript = "",
                title = "Quick voice memo",
                summary = "",
                bucketKind = "INBOX",
                tierUsed = "MINIMAL",
                stageStatus = "transcribe:UNAVAILABLE,title:FALLBACK,actions:SKIPPED,bucket:DEFAULT",
                status = NoteStatus.SORTED.name
            )
        )

        // 6. INBOX (Tier 2 Needs Review - low-confidence classification)
        dao.insert(
            Note(
                createdAtEpochMs = now - (15 * 60 * 1000L),
                audioPath = "/dev/null/ambiguous.m4a",
                transcript = "Maybe we should think about scheduling a check-in sometime next month or maybe not.",
                title = "Check-in discussion",
                summary = "Think about scheduling a check-in sometime next month.",
                bucketKind = "INBOX",
                tierUsed = "CORE",
                stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:LOW_CONFIDENCE",
                status = NoteStatus.NEEDS_REVIEW.name
            )
        )

        // Tags on at least two notes (IDEA, TASK, and LIST)
        val tagNames = listOf("white-label", "product", "renovation", "errands", "groceries")
        dao.insertTags(tagNames.map { Tag(name = it) })
        dao.insertNoteTags(
            listOf(
                NoteTag(noteId = ideaNoteId, tagName = "white-label"),
                NoteTag(noteId = ideaNoteId, tagName = "product"),
                NoteTag(noteId = taskNoteId, tagName = "renovation"),
                NoteTag(noteId = taskNoteId, tagName = "errands"),
                NoteTag(noteId = listNoteId, tagName = "groceries")
            )
        )
    }
}

@Composable
fun DebugSeedToolbar(db: AppDatabase, onReset: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Developer options" }
        ) {
            Text("🛠", style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Seed sample data") },
                onClick = {
                    expanded = false
                    scope.launch {
                        DebugSeedData.seed(db)
                        onReset()
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Seed sample data" }
            )
            DropdownMenuItem(
                text = { Text("Clear all data") },
                onClick = {
                    expanded = false
                    scope.launch {
                        DebugSeedData.clear(db)
                        onReset()
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Clear seed data" }
            )
        }
    }
}
