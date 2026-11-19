// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.NoOpEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddingCodecAndSchemaTest {

    @Test
    fun encodeAndDecode_roundTripsFloatsInLittleEndianOrder() {
        val original = floatArrayOf(0.25f, -1.5f, 3.14159f, 0.0f)
        val blob = EmbeddingCodec.encode(original)
        assertEquals(original.size * 4, blob.size)

        val decoded = EmbeddingCodec.decode(blob, original.size)
        assertNotNull(decoded)
        assertEquals(original.toList(), decoded!!.toList())
    }

    @Test
    fun decode_returnsNullOnInvalidDimensionOrMismatchedBlobSize() {
        val blob = EmbeddingCodec.encode(floatArrayOf(1f, 2f))
        assertNull(EmbeddingCodec.decode(blob, 0))
        assertNull(EmbeddingCodec.decode(blob, -1))
        assertNull(EmbeddingCodec.decode(blob, 3))
        assertNull(EmbeddingCodec.decode(byteArrayOf(1, 2, 3), 1))
    }

    @Test
    fun cosineSimilarity_handlesNormalAndEdgeVectors() {
        assertEquals(
            1.0f,
            EmbeddingCodec.cosineSimilarity(floatArrayOf(1f, 0f, 0f), floatArrayOf(2f, 0f, 0f))!!,
            1e-5f
        )
        assertEquals(
            0.0f,
            EmbeddingCodec.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))!!,
            1e-5f
        )
        assertEquals(
            -1.0f,
            EmbeddingCodec.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f))!!,
            1e-5f
        )
        assertNull(EmbeddingCodec.cosineSimilarity(floatArrayOf(), floatArrayOf()))
        assertNull(EmbeddingCodec.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f)))
        assertNull(EmbeddingCodec.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)))
        assertNull(EmbeddingCodec.cosineSimilarity(floatArrayOf(Float.NaN, 1f), floatArrayOf(1f, 1f)))
    }

    @Test
    fun noteEmbedding_equalsAndHashCodeCompareBlobContents() {
        val a = NoteEmbedding(
            noteId = 1L,
            modelId = "m1",
            dimension = 2,
            vectorBlob = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            updatedAtEpochMs = 100L
        )
        val b = NoteEmbedding(
            noteId = 1L,
            modelId = "m1",
            dimension = 2,
            vectorBlob = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            updatedAtEpochMs = 100L
        )
        val c = b.copy(vectorBlob = byteArrayOf(9, 2, 3, 4, 5, 6, 7, 8))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun noOpEmbedder_reportsNoopModelIdUnavailableAndNullVector() = runBlocking {
        val embedder = NoOpEmbedder()
        assertEquals("noop", embedder.modelId)
        assertEquals(CapabilityStatus.UNAVAILABLE, embedder.status())
        assertNull(embedder.embed("sample text"))
    }

    @Test
    fun exportedRoomSchemaV3_existsAndIncludesNoteEmbeddingsTable() {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "app/schemas").exists()) {
            dir = dir.parentFile
        }
        assertNotNull("Expected app/schemas directory to exist", dir)
        val schemaFile = File(dir!!, "app/schemas/com.icanbenchurcat.voicenotes.data.AppDatabase/3.json")
        assertTrue("Expected Room schema v3 at ${schemaFile.absolutePath}", schemaFile.exists())

        val json = schemaFile.readText()
        assertTrue(json.contains("\"version\": 3"))
        assertTrue(json.contains("\"tableName\": \"note_embeddings\""))
        assertTrue(json.contains("\"columnName\": \"noteId\""))
        assertTrue(json.contains("\"columnName\": \"modelId\""))
        assertTrue(json.contains("\"columnName\": \"dimension\""))
        assertTrue(json.contains("\"columnName\": \"vectorBlob\""))
        assertTrue(json.contains("\"columnName\": \"updatedAtEpochMs\""))
    }
}
