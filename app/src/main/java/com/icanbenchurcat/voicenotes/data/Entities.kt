package com.icanbenchurcat.voicenotes.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMs: Long,
    val audioPath: String,
    val transcript: String = "",
    val title: String = "",
    val summary: String = "",
    val bucketKind: String = "INBOX",
    /** FULL / CORE / MINIMAL - the tier that actually processed the note. */
    val tierUsed: String = "MINIMAL",
    /** Comma-separated per-stage status, e.g. "transcribe:OK,bucket:OK". */
    val stageStatus: String = ""
)

@Entity(tableName = "buckets")
data class Bucket(
    @PrimaryKey val kind: String,
    val name: String
)

@Entity(tableName = "action_items")
data class ActionItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val text: String,
    val dueAtEpochMs: Long? = null,
    val done: Boolean = false
)

@Entity(tableName = "tags")
data class Tag(@PrimaryKey val name: String)

@Entity(tableName = "note_tags", primaryKeys = ["noteId", "tagName"])
data class NoteTag(val noteId: Long, val tagName: String)

/** Full-text index over the human-readable fields; search works on
 *  every tier, semantic search (Tier 3) layers on top in v1.1. */
@Fts4(contentEntity = Note::class)
@Entity(tableName = "notes_fts")
data class NoteFts(
    val title: String,
    val transcript: String,
    val summary: String
)
