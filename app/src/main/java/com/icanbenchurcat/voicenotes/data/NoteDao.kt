// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert suspend fun insert(note: Note): Long
    @Update suspend fun update(note: Note)

    @Query("SELECT * FROM notes ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE bucketKind = :kind ORDER BY createdAtEpochMs DESC")
    fun observeByBucket(kind: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE status = :status ORDER BY createdAtEpochMs DESC")
    fun observeByStatus(status: String): Flow<List<Note>>

    @Query("UPDATE notes SET bucketKind = :bucketKind, status = :status WHERE id = :noteId")
    suspend fun confirmOrChangeBucket(
        noteId: Long,
        bucketKind: String,
        status: String = "SORTED"
    )

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): Note?

    @Query(
        "SELECT notes.* FROM notes JOIN notes_fts ON notes.id = notes_fts.rowid " +
            "WHERE notes_fts MATCH :query ORDER BY notes.createdAtEpochMs DESC"
    )
    fun search(query: String): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActions(items: List<ActionItem>)

    @Query("DELETE FROM action_items WHERE noteId = :noteId")
    suspend fun deleteActionsForNote(noteId: Long)

    @Query("SELECT * FROM action_items WHERE noteId = :noteId ORDER BY id ASC")
    suspend fun actionsFor(noteId: Long): List<ActionItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBuckets(buckets: List<Bucket>)

    @Query("SELECT * FROM buckets ORDER BY kind ASC")
    suspend fun allBuckets(): List<Bucket>

    @Query("SELECT * FROM buckets WHERE kind = :kind LIMIT 1")
    suspend fun bucketByKind(kind: String): Bucket?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<Tag>)

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun allTags(): List<Tag>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteTags(noteTags: List<NoteTag>)

    @Query("DELETE FROM note_tags WHERE noteId = :noteId")
    suspend fun deleteNoteTagsForNote(noteId: Long)

    @Query("SELECT tagName FROM note_tags WHERE noteId = :noteId ORDER BY tagName ASC")
    suspend fun tagsFor(noteId: Long): List<String>

    @Query(
        "SELECT notes.* FROM notes JOIN note_tags ON notes.id = note_tags.noteId " +
            "WHERE note_tags.tagName = :tagName ORDER BY notes.createdAtEpochMs DESC"
    )
    fun observeByTag(tagName: String): Flow<List<Note>>
}
