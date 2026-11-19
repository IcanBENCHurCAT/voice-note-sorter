// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Serializes embedding vectors as little-endian IEEE-754 float blobs for Room
 * persistence and computes cosine similarity between equal-dimension vectors.
 */
object EmbeddingCodec {
    private const val BYTES_PER_FLOAT = 4

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * BYTES_PER_FLOAT)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) {
            buffer.putFloat(value)
        }
        return buffer.array()
    }

    fun decode(blob: ByteArray, expectedDimension: Int): FloatArray? {
        if (expectedDimension <= 0 || blob.size != expectedDimension * BYTES_PER_FLOAT) {
            return null
        }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(expectedDimension) { buffer.float }
    }

    /**
     * Computes cosine similarity in `[-1.0, 1.0]` between [a] and [b], returning `null`
     * when vectors are empty, differ in dimension, have zero norm, or contain non-finite floats.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float? {
        if (a.isEmpty() || a.size != b.size) return null
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            if (!av.isFinite() || !bv.isFinite()) return null
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 0.0 || !denom.isFinite()) return null
        return (dot / denom).toFloat().coerceIn(-1f, 1f)
    }
}
