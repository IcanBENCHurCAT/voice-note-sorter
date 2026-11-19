// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import com.icanbenchurcat.voicenotes.ai.CapabilityBusyException
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.Embedder
import com.icanbenchurcat.voicenotes.ai.NoOpEmbedder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Seam for notes search querying.
 *
 * Fronts Room SQLite Full-Text Search (FTS4) on Tier 1/2 ([NoOpEmbedder]),
 * and layers cosine-similarity vector ranking over active-model [NoteEmbedding]
 * rows when an [Embedder] is [CapabilityStatus.AVAILABLE] (ADR 0007).
 */
interface SearchRepository {
    fun search(query: String): Flow<List<Note>>
}

class DefaultSearchRepository(
    private val dao: NoteDao,
    private val embedder: Embedder = NoOpEmbedder(),
    private val minVectorSimilarity: Float = DEFAULT_MIN_VECTOR_SIMILARITY
) : SearchRepository {

    override fun search(query: String): Flow<List<Note>> {
        if (query.isBlank()) {
            return dao.observeAll()
        }

        val sanitized = FtsQuerySanitizer.sanitize(query)
        val ftsFlow: Flow<List<Note>> = if (sanitized != null) {
            dao.search(sanitized)
        } else {
            flowOf(emptyList())
        }

        return flow {
            val queryVector = tryEmbedQueryIfAvailable(query)
            if (queryVector == null) {
                emitAll(ftsFlow)
            } else {
                val activeModelId = embedder.modelId
                emitAll(
                    combine(
                        dao.observeAll(),
                        ftsFlow,
                        dao.observeEmbeddingsForModel(activeModelId)
                    ) { allNotes, ftsMatches, embeddings ->
                        rankHybrid(
                            allNotes = allNotes,
                            ftsMatches = ftsMatches,
                            embeddings = embeddings,
                            activeModelId = activeModelId,
                            queryVector = queryVector
                        )
                    }
                )
            }
        }
    }

    private suspend fun tryEmbedQueryIfAvailable(query: String): FloatArray? {
        if (query.none { it.isLetterOrDigit() }) return null
        return try {
            if (embedder.status() != CapabilityStatus.AVAILABLE) {
                return null
            }
            val vector = embedder.embed(query)
            if (vector == null || vector.isEmpty()) null else vector
        } catch (e: CancellationException) {
            throw e
        } catch (_: CapabilityBusyException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun rankHybrid(
        allNotes: List<Note>,
        ftsMatches: List<Note>,
        embeddings: List<NoteEmbedding>,
        activeModelId: String,
        queryVector: FloatArray
    ): List<Note> {
        val ftsIds = ftsMatches.mapTo(HashSet(ftsMatches.size)) { it.id }
        val embeddingsByNoteId = embeddings
            .asSequence()
            .filter { it.modelId == activeModelId }
            .associateBy { it.noteId }

        val notesById = LinkedHashMap<Long, Note>(allNotes.size + ftsMatches.size)
        for (note in allNotes) notesById[note.id] = note
        for (note in ftsMatches) notesById.putIfAbsent(note.id, note)

        val scored = ArrayList<ScoredNote>(notesById.size)
        for (note in notesById.values) {
            val isFts = note.id in ftsIds
            val embedding = embeddingsByNoteId[note.id]
            val vector = embedding?.let { EmbeddingCodec.decode(it.vectorBlob, it.dimension) }
            val similarity = vector?.let { EmbeddingCodec.cosineSimilarity(queryVector, it) }

            val passesVector = similarity != null && similarity >= minVectorSimilarity
            if (!isFts && !passesVector) continue

            val simContribution = (similarity ?: 0f).coerceAtLeast(0f)
            val score = (if (isFts) FTS_WEIGHT else 0f) + VECTOR_WEIGHT * simContribution
            scored += ScoredNote(
                note = note,
                score = score,
                isFts = isFts,
                similarity = similarity ?: Float.NEGATIVE_INFINITY
            )
        }

        scored.sortWith(
            compareByDescending<ScoredNote> { it.score }
                .thenByDescending { it.isFts }
                .thenByDescending { it.similarity }
                .thenByDescending { it.note.createdAtEpochMs }
        )
        return scored.map { it.note }
    }

    private data class ScoredNote(
        val note: Note,
        val score: Float,
        val isFts: Boolean,
        val similarity: Float
    )

    companion object {
        const val DEFAULT_MIN_VECTOR_SIMILARITY = 0.25f
        private const val FTS_WEIGHT = 0.5f
        private const val VECTOR_WEIGHT = 0.5f
    }
}
