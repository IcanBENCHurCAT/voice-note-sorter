// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

/**
 * Domain coordinator / validator for bucket operations.
 *
 * Enforces business rules:
 * - Default buckets (`IDEA`, `TASK`, `LIST`, `NOTE`, `INBOX`) are permanent.
 * - At most 4 custom buckets can exist simultaneously.
 * - Custom bucket names and kinds must be non-empty and well-formed.
 */
class BucketManager(private val dao: NoteDao) {

    companion object {
        const val MAX_CUSTOM_BUCKETS = 4
        val DEFAULT_BUCKET_KINDS = setOf("IDEA", "TASK", "LIST", "NOTE", "INBOX")

        val DEFAULT_BUCKETS = listOf(
            Bucket("IDEA", "Ideas"),
            Bucket("TASK", "Tasks"),
            Bucket("LIST", "Lists"),
            Bucket("NOTE", "Notes"),
            Bucket("INBOX", "Inbox")
        )

        fun sanitizeKind(raw: String): String {
            val slug = raw.trim().replace(Regex("[^a-zA-Z0-9_]"), "_").uppercase()
            return slug.take(20)
        }
    }

    suspend fun seedDefaultsIfEmpty() {
        val existing = dao.allBuckets()
        val existingKinds = existing.map { it.kind }.toSet()
        val missingDefaults = DEFAULT_BUCKETS.filter { it.kind !in existingKinds }
        if (missingDefaults.isNotEmpty()) {
            dao.insertBuckets(missingDefaults)
        }
    }

    suspend fun renameBucket(kind: String, newName: String) {
        val trimmed = newName.trim()
        require(trimmed.isNotBlank()) { "Bucket name cannot be empty" }
        val bucket = dao.bucketByKind(kind) ?: throw IllegalArgumentException("Bucket with kind '$kind' not found")
        dao.renameBucket(kind, trimmed)
    }

    suspend fun addCustomBucket(kind: String, name: String): Bucket {
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "Bucket name cannot be empty" }

        val sanitizedKind = sanitizeKind(kind.ifBlank { trimmedName })
        require(sanitizedKind.isNotBlank()) { "Bucket kind cannot be empty" }

        val all = dao.allBuckets()
        if (all.any { it.kind == sanitizedKind }) {
            throw IllegalArgumentException("Bucket with kind '$sanitizedKind' already exists")
        }

        val customCount = all.count { it.kind !in DEFAULT_BUCKET_KINDS }
        if (customCount >= MAX_CUSTOM_BUCKETS) {
            throw IllegalStateException("Maximum of $MAX_CUSTOM_BUCKETS custom buckets reached")
        }

        val newBucket = Bucket(kind = sanitizedKind, name = trimmedName)
        dao.insertBucket(newBucket)
        return newBucket
    }
}
