// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.fixtures

import com.icanbenchurcat.voicenotes.ai.ActionExtractor
import com.icanbenchurcat.voicenotes.ai.BucketAssignment
import com.icanbenchurcat.voicenotes.ai.BucketProfile
import com.icanbenchurcat.voicenotes.ai.Bucketer
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.ExtractedAction
import com.icanbenchurcat.voicenotes.ai.Summarizer
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.TitleSummary
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.ai.Transcript
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteDao
import com.icanbenchurcat.voicenotes.data.NoteTag
import com.icanbenchurcat.voicenotes.data.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeTranscriber(
    private val result: Transcript? = null,
    private val exception: Exception? = null,
    private val currentStatus: CapabilityStatus = CapabilityStatus.AVAILABLE
) : Transcriber {
    var calls = 0
        private set

    override suspend fun status(): CapabilityStatus = currentStatus
    override suspend fun transcribe(audioPath: String): Transcript {
        calls++
        if (exception != null) throw exception
        return result ?: Transcript("fake transcript", Tier.CORE)
    }
}

class FakeSummarizer(
    private val currentStatus: CapabilityStatus = CapabilityStatus.AVAILABLE,
    private val result: TitleSummary = TitleSummary("title", "summary"),
    private val exception: Exception? = null
) : Summarizer {
    var calls = 0
        private set

    override suspend fun status(): CapabilityStatus = currentStatus
    override suspend fun summarize(transcript: String): TitleSummary {
        calls++
        if (exception != null) throw exception
        return result
    }
}

class FakeActionExtractor(
    private val currentStatus: CapabilityStatus = CapabilityStatus.AVAILABLE,
    private val items: List<ExtractedAction> = emptyList(),
    private val exception: Exception? = null
) : ActionExtractor {
    var calls = 0
        private set

    override suspend fun status(): CapabilityStatus = currentStatus
    override suspend fun extract(transcript: String): List<ExtractedAction> {
        calls++
        if (exception != null) throw exception
        return items
    }
}

class FakeBucketer(
    private val assignment: BucketAssignment = BucketAssignment("INBOX", emptyList(), Tier.CORE),
    private val exception: Exception? = null
) : Bucketer {
    override suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment {
        if (exception != null) throw exception
        return assignment
    }
}

/**
 * In-memory [NoteDao] fake that records every intermediate [Note] update
 * so tests can assert per-stage persistence and idempotency.
 */
class InMemoryFakeNoteDao : NoteDao {
    private var nextNoteId = 1L
    private var nextActionId = 1L
    val notes = mutableMapOf<Long, Note>()
    val updateSnapshots = mutableListOf<Note>()
    private val actions = mutableListOf<ActionItem>()
    private val buckets = mutableMapOf<String, Bucket>()
    private val tags = mutableSetOf<String>()
    private val noteTags = mutableSetOf<NoteTag>()

    override suspend fun insert(note: Note): Long {
        val id = if (note.id == 0L) nextNoteId++ else note.id
        notes[id] = note.copy(id = id)
        return id
    }

    override suspend fun update(note: Note) {
        notes[note.id] = note
        updateSnapshots += note
    }

    override suspend fun delete(note: Note) {
        notes.remove(note.id)
    }

    override suspend fun deleteNoteById(id: Long) {
        notes.remove(id)
    }

    override suspend fun allAudioPaths(): List<String> =
        notes.values.map { it.audioPath }

    override fun observeAll(): Flow<List<Note>> = flowOf(notes.values.toList())

    override fun observeByBucket(kind: String): Flow<List<Note>> =
        flowOf(notes.values.filter { it.bucketKind == kind })

    override fun observeByStatus(status: String): Flow<List<Note>> =
        flowOf(notes.values.filter { it.status == status })

    override suspend fun confirmOrChangeBucket(noteId: Long, bucketKind: String, status: String) {
        val existing = notes[noteId] ?: return
        notes[noteId] = existing.copy(bucketKind = bucketKind, status = status)
    }

    override suspend fun byId(id: Long): Note? = notes[id]

    override fun search(query: String): Flow<List<Note>> {
        val q = query.lowercase().trim()
        val matching = notes.values.filter { note ->
            note.title.lowercase().contains(q) ||
                note.transcript.lowercase().contains(q) ||
                note.summary.lowercase().contains(q)
        }
        return flowOf(matching)
    }

    override suspend fun insertActions(items: List<ActionItem>) {
        items.forEach { item ->
            val id = if (item.id == 0L) nextActionId++ else item.id
            actions += item.copy(id = id)
        }
    }

    override suspend fun deleteActionsForNote(noteId: Long) {
        actions.removeAll { it.noteId == noteId }
    }

    override suspend fun deleteAllActions() {
        actions.clear()
    }

    override suspend fun actionsFor(noteId: Long): List<ActionItem> =
        actions.filter { it.noteId == noteId }.sortedBy { it.id }

    override suspend fun insertBuckets(buckets: List<Bucket>) {
        buckets.forEach { this.buckets[it.kind] = it }
    }

    override suspend fun allBuckets(): List<Bucket> =
        buckets.values.sortedBy { it.kind }

    override suspend fun bucketByKind(kind: String): Bucket? = buckets[kind]

    override suspend fun insertTags(tags: List<Tag>) {
        tags.forEach { this.tags += it.name }
    }

    override suspend fun allTags(): List<Tag> =
        tags.sorted().map { Tag(it) }

    override suspend fun deleteAllTags() {
        tags.clear()
    }

    override suspend fun insertNoteTags(noteTags: List<NoteTag>) {
        this.noteTags.addAll(noteTags)
    }

    override suspend fun deleteNoteTagsForNote(noteId: Long) {
        noteTags.removeAll { it.noteId == noteId }
    }

    override suspend fun deleteAllNoteTags() {
        noteTags.clear()
    }

    override suspend fun tagsFor(noteId: Long): List<String> =
        noteTags.filter { it.noteId == noteId }.map { it.tagName }.sorted()

    override fun observeByTag(tagName: String): Flow<List<Note>> {
        val matchingIds = noteTags.filter { it.tagName == tagName }.map { it.noteId }.toSet()
        return flowOf(notes.values.filter { it.id in matchingIds })
    }

    override suspend fun deleteAllNoteRows() {
        notes.clear()
    }
}
