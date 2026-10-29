package com.icanbenchurcat.voicenotes.data

import androidx.room.Dao
import androidx.room.Insert
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

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): Note?

    @Query(
        "SELECT notes.* FROM notes JOIN notes_fts ON notes.id = notes_fts.rowid " +
            "WHERE notes_fts MATCH :query ORDER BY notes.createdAtEpochMs DESC"
    )
    fun search(query: String): Flow<List<Note>>

    @Insert suspend fun insertActions(items: List<ActionItem>)

    @Query("SELECT * FROM action_items WHERE noteId = :noteId")
    suspend fun actionsFor(noteId: Long): List<ActionItem>
}
