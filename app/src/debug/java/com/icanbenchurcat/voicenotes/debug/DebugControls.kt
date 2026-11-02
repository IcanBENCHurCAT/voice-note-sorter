// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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

        val baseTimeMs = 1_730_470_000_000L

        // 1. IDEA bucket (Tier 3 FULL, tagged)
        val ideaNoteId = dao.insert(
            Note(
                createdAtEpochMs = baseTimeMs + 50_000L,
                audioPath = "/data/user/0/com.icanbenchurcat.voicenotes/files/seed_idea.m4a",
                transcript = "What if we built an offline contractor field-notes profile where site recordings automatically sort into punch-list items, change orders, and material requests?",
                title = "Offline contractor field-notes profile",
                summary = "White-label bucket profile sorting site voice notes into punch-list items, change orders, and material requests.",
                bucketKind = "IDEA",
                tierUsed = "FULL",
                stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:OK",
                status = NoteStatus.SORTED.name
            )
        )

        // 2. TASK bucket (Tier 3 FULL, action items some done and some not, tagged)
        val taskNoteId = dao.insert(
            Note(
                createdAtEpochMs = baseTimeMs + 40_000L,
                audioPath = "/data/user/0/com.icanbenchurcat.voicenotes/files/seed_task.m4a",
                transcript = "Remind me to call the electrician before noon, send the updated floor plan PDF to Dana, and pick up cabinet hinges at the hardware store.",
                title = "Renovation follow-ups for today",
                summary = "Call electrician before noon, send floor plan PDF to Dana, and buy cabinet hinges.",
                bucketKind = "TASK",
                tierUsed = "FULL",
                stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:OK",
                status = NoteStatus.SORTED.name
            )
        )

        // 3. LIST bucket (Tier 2 CORE, tagged)
        val listNoteId = dao.insert(
            Note(
                createdAtEpochMs = baseTimeMs + 30_000L,
                audioPath = "/data/user/0/com.icanbenchurcat.voicenotes/files/seed_list.m4a",
                transcript = "Weekend shopping list: espresso beans, oat milk, sourdough flour, cast iron seasoning oil, and AA batteries.",
                title = "Weekend shopping list: espresso beans, oat milk",
                summary = "Weekend shopping list: espresso beans, oat milk, sourdough flour, cast iron seasoning oil, and AA batteries.",
                bucketKind = "LIST",
                tierUsed = "CORE",
                stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:OK",
                status = NoteStatus.SORTED.name
            )
        )

        // 4. NOTE bucket (Tier 2 CORE, flagged Needs Review after low-confidence / default filing)
        dao.insert(
            Note(
                createdAtEpochMs = baseTimeMs + 20_000L,
                audioPath = "/data/user/0/com.icanbenchurcat.voicenotes/files/seed_note.m4a",
                transcript = "Architecture meeting recap: keep the open-core app in a single module until the closed Pro repository exists, and guard every ML Kit call behind the ai package interfaces.",
                title = "Architecture meeting recap: keep the open-core",
                summary = "Architecture meeting recap: keep the open-core app in a single module until the closed Pro repository exists.",
                bucketKind = "NOTE",
                tierUsed = "CORE",
                stageStatus = "transcribe:OK,title:OK,actions:OK,bucket:OK",
                status = NoteStatus.NEEDS_REVIEW.name
            )
        )

        // 5. INBOX bucket (Tier 1 MINIMAL, no transcript)
        dao.insert(
            Note(
                createdAtEpochMs = baseTimeMs + 10_000L,
                audioPath = "/data/user/0/com.icanbenchurcat.voicenotes/files/seed_tier1_manual.m4a",
                transcript = "",
                title = "Voice note 2026-11-01 08:15",
                summary = "",
                bucketKind = "INBOX",
                tierUsed = "MINIMAL",
                stageStatus = "transcribe:UNAVAILABLE,title:FALLBACK,actions:SKIPPED,bucket:DEFAULT",
                status = NoteStatus.RECORDED.name
            )
        )

        // Action items on the TASK note (some done, some not)
        dao.insertActions(
            listOf(
                ActionItem(
                    noteId = taskNoteId,
                    text = "Call the electrician before noon",
                    dueAtEpochMs = baseTimeMs + 86_400_000L,
                    done = true
                ),
                ActionItem(
                    noteId = taskNoteId,
                    text = "Send the updated floor plan PDF to Dana",
                    dueAtEpochMs = baseTimeMs + 172_800_000L,
                    done = false
                ),
                ActionItem(
                    noteId = taskNoteId,
                    text = "Pick up cabinet hinges at the hardware store",
                    dueAtEpochMs = null,
                    done = false
                )
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
fun DebugSeedToolbar(db: AppDatabase) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { scope.launch { DebugSeedData.seed(db) } },
            modifier = Modifier.semantics { contentDescription = "Seed sample data" }
        ) {
            Text("Seed sample data")
        }
        OutlinedButton(
            onClick = { scope.launch { DebugSeedData.clear(db) } },
            modifier = Modifier.semantics { contentDescription = "Clear seed data" }
        ) {
            Text("Clear data")
        }
    }
}
