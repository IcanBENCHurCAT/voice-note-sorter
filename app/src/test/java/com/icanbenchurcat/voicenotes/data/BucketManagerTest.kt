// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BucketManagerTest {

    private lateinit var dao: InMemoryFakeNoteDao
    private lateinit var bucketManager: BucketManager

    @Before
    fun setUp() {
        dao = InMemoryFakeNoteDao()
        bucketManager = BucketManager(dao)
    }

    @Test
    fun seedDefaultsIfEmpty_seedsAll5DefaultBuckets() = runTest {
        assertEquals(0, dao.allBuckets().size)

        bucketManager.seedDefaultsIfEmpty()

        val seeded = dao.allBuckets()
        assertEquals(5, seeded.size)
        val kinds = seeded.map { it.kind }.toSet()
        assertEquals(setOf("IDEA", "TASK", "LIST", "NOTE", "INBOX"), kinds)

        val taskBucket = dao.bucketByKind("TASK")
        assertNotNull(taskBucket)
        assertEquals("Tasks", taskBucket?.name)
    }

    @Test
    fun seedDefaultsIfEmpty_isIdempotent_doesNotDuplicateOrOverwrite() = runTest {
        bucketManager.seedDefaultsIfEmpty()
        assertEquals(5, dao.allBuckets().size)

        // Seed again
        bucketManager.seedDefaultsIfEmpty()
        assertEquals(5, dao.allBuckets().size)
    }

    @Test
    fun renameBucket_updatesBucketNameInDaoAndFlow() = runTest {
        bucketManager.seedDefaultsIfEmpty()

        bucketManager.renameBucket("TASK", "Action Items")

        val updated = dao.bucketByKind("TASK")
        assertNotNull(updated)
        assertEquals("Action Items", updated?.name)

        val observed = dao.observeAllBuckets().first()
        val taskInFlow = observed.first { it.kind == "TASK" }
        assertEquals("Action Items", taskInFlow.name)
    }

    @Test
    fun renameBucket_rejectsBlankNameOrNonExistentBucket() = runTest {
        bucketManager.seedDefaultsIfEmpty()

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                bucketManager.renameBucket("TASK", "   ")
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                bucketManager.renameBucket("UNKNOWN", "New Name")
            }
        }
    }

    @Test
    fun addCustomBucket_upTo4CustomBuckets_succeeds() = runTest {
        bucketManager.seedDefaultsIfEmpty()

        val b1 = bucketManager.addCustomBucket("JOURNAL", "Daily Journal")
        val b2 = bucketManager.addCustomBucket("RECIPE", "Recipes")
        val b3 = bucketManager.addCustomBucket("MEETING", "Meeting Minutes")
        val b4 = bucketManager.addCustomBucket("RESEARCH", "Research Papers")

        assertEquals("JOURNAL", b1.kind)
        assertEquals("Daily Journal", b1.name)

        val all = dao.allBuckets()
        // 5 defaults + 4 custom = 9 total
        assertEquals(9, all.size)

        val observed = dao.observeAllBuckets().first()
        assertEquals(9, observed.size)
    }

    @Test
    fun addCustomBucket_rejectsFifthCustomBucket() = runTest {
        bucketManager.seedDefaultsIfEmpty()

        bucketManager.addCustomBucket("C1", "Custom 1")
        bucketManager.addCustomBucket("C2", "Custom 2")
        bucketManager.addCustomBucket("C3", "Custom 3")
        bucketManager.addCustomBucket("C4", "Custom 4")

        // 5th custom bucket must throw IllegalStateException
        val exception = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                bucketManager.addCustomBucket("C5", "Custom 5")
            }
        }
        assertTrue(exception.message!!.contains("Maximum of 4 custom buckets reached"))
    }

    @Test
    fun addCustomBucket_rejectsDuplicateKindOrEmptyName() = runTest {
        bucketManager.seedDefaultsIfEmpty()

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                bucketManager.addCustomBucket("TASK", "Duplicate Task")
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                bucketManager.addCustomBucket("VALID", "   ")
            }
        }
    }

    @Test
    fun movingNotesBetweenBuckets_updatesNoteBucketKindAndStatus() = runTest {
        bucketManager.seedDefaultsIfEmpty()

        val noteId = dao.insert(
            Note(
                id = 1L,
                createdAtEpochMs = 1000L,
                audioPath = "/audio/1.m4a",
                title = "Brainstorming",
                bucketKind = "INBOX",
                status = NoteStatus.NEEDS_REVIEW.name
            )
        )

        // Move note to custom bucket
        bucketManager.addCustomBucket("PROJECT", "Projects")
        dao.confirmOrChangeBucket(noteId, "PROJECT", NoteStatus.SORTED.name)

        val updatedNote = dao.byId(noteId)
        assertNotNull(updatedNote)
        assertEquals("PROJECT", updatedNote?.bucketKind)
        assertEquals(NoteStatus.SORTED.name, updatedNote?.status)

        // Move note to default bucket
        dao.confirmOrChangeBucket(noteId, "IDEA", NoteStatus.SORTED.name)
        val movedNote = dao.byId(noteId)
        assertEquals("IDEA", movedNote?.bucketKind)
    }
}
