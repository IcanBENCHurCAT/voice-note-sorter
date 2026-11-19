// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.Embedder
import com.icanbenchurcat.voicenotes.ai.NoOpEmbedder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteEmbeddingRoomInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun noteDeletionCoordinator_deletesEmbeddingsOnSingleAndAllDelete() = runBlocking {
        val id1 = dao.insert(Note(createdAtEpochMs = 1000L, audioPath = "", title = "First", transcript = "Alpha"))
        val id2 = dao.insert(Note(createdAtEpochMs = 2000L, audioPath = "", title = "Second", transcript = "Beta"))

        dao.upsertEmbedding(
            NoteEmbedding(id1, "model-v1", 2, EmbeddingCodec.encode(floatArrayOf(1f, 0f)), 1000L)
        )
        dao.upsertEmbedding(
            NoteEmbedding(id2, "model-v1", 2, EmbeddingCodec.encode(floatArrayOf(0f, 1f)), 2000L)
        )

        val coordinator = NoteDeletionCoordinator(dao)
        assertTrue(coordinator.deleteNote(id1))
        assertNull(dao.embeddingForNote(id1))
        assertEquals(listOf(id2), dao.embeddingsForModel("model-v1").map { it.noteId })

        coordinator.deleteAllNotes()
        assertNull(dao.embeddingForNote(id2))
        assertTrue(dao.embeddingsForModel("model-v1").isEmpty())
    }

    @Test
    fun hybridSearchInRoom_filtersByModelIdAndFallsBackToFts4WithNoOpEmbedder() = runBlocking {
        val ftsId = dao.insert(
            Note(createdAtEpochMs = 1000L, audioPath = "", title = "Coffee shop", transcript = "Espresso beans")
        )
        val semanticId = dao.insert(
            Note(createdAtEpochMs = 2000L, audioPath = "", title = "Morning latte", transcript = "Steamed oat milk")
        )
        val wrongModelId = dao.insert(
            Note(createdAtEpochMs = 3000L, audioPath = "", title = "Unrelated note", transcript = "Tax paperwork")
        )

        dao.upsertEmbedding(
            NoteEmbedding(semanticId, "active-v2", 2, EmbeddingCodec.encode(floatArrayOf(0.9f, 0.436f)), 2000L)
        )
        dao.upsertEmbedding(
            NoteEmbedding(wrongModelId, "old-v1", 2, EmbeddingCodec.encode(floatArrayOf(1f, 0f)), 3000L)
        )

        val activeEmbedder = object : Embedder {
            override val modelId: String = "active-v2"
            override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE
            override suspend fun embed(text: String): FloatArray = floatArrayOf(1f, 0f)
        }

        val hybridResults = DefaultSearchRepository(dao, activeEmbedder).search("coffee").first()
        assertEquals(listOf(ftsId, semanticId), hybridResults.map { it.id })

        val ftsOnlyResults = DefaultSearchRepository(dao, NoOpEmbedder()).search("coffee").first()
        assertEquals(listOf(ftsId), ftsOnlyResults.map { it.id })
    }
}
