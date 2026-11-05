// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Seam for notes search querying.
 *
 * Fronts Room SQLite Full-Text Search (FTS4) in MVP / Tier 1/2,
 * and serves as the extension seam for v1.1 semantic/vector search (Spec Decision 7).
 */
interface SearchRepository {
    fun search(query: String): Flow<List<Note>>
}

class DefaultSearchRepository(
    private val dao: NoteDao
) : SearchRepository {
    override fun search(query: String): Flow<List<Note>> {
        val sanitized = FtsQuerySanitizer.sanitize(query)
        return if (sanitized != null) {
            dao.search(sanitized)
        } else if (query.isNotBlank()) {
            flowOf(emptyList())
        } else {
            dao.observeAll()
        }
    }
}
