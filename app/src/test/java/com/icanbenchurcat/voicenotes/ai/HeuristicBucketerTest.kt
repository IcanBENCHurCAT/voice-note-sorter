// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicBucketerTest {

    private val bucketer = HeuristicBucketer()
    private val defaultProfile = DefaultBucketProfileProvider.profile()

    @Test
    fun multiBucketScoring_picksHighestScoringBucketRegardlessOfProfileDeclarationOrder() = runTest {
        // DefaultBucketProfileProvider declares IDEA first and TASK second.
        // A transcript with one IDEA keyword ("idea") and two TASK keywords ("remind me", "call")
        // must resolve to TASK rather than stopping at IDEA via firstOrNull.
        val transcript = "I had an idea earlier, please remind me to call the contractor tomorrow."

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertEquals("TASK", assignment.bucketKind)
        assertEquals(listOf("call", "remind-me"), assignment.tags.sorted())
        assertEquals(2, assignment.score)
        assertFalse(assignment.needsReview)
        assertTrue(assignment.confidence >= 0.8f)
    }

    @Test
    fun multiBucketScoring_breaksDistinctCountTieUsingTotalOccurrences() = runTest {
        // 1 distinct IDEA keyword ("idea" once) vs 1 distinct TASK keyword ("call" twice)
        val transcript = "Good idea: call the supplier in the morning and call the inspector at noon."

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertEquals("TASK", assignment.bucketKind)
        assertEquals(listOf("call"), assignment.tags)
        assertFalse(assignment.needsReview)
    }

    @Test
    fun multiBucketScoring_flagsExactTopScoreTieForReview() = runTest {
        // 1 distinct IDEA keyword ("idea" once) and 1 distinct TASK keyword ("call" once)
        val transcript = "One idea is to call the architect."

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertTrue(assignment.needsReview)
        assertTrue(assignment.confidence < 0.7f)
    }

    @Test
    fun wordBoundaryMatching_rejectsSubstringFalsePositives() = runTest {
        // 'recall' contains 'call', 'buyer' contains 'buy', 'ideal' contains 'idea', 'enlist' contains 'list'
        val transcript = "The buyer could not recall the ideal brochure to enlist."

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertEquals("INBOX", assignment.bucketKind)
        assertTrue(assignment.tags.isEmpty())
        assertEquals(0, assignment.score)
        assertEquals(0.0f, assignment.confidence, 0.001f)
        assertTrue(assignment.needsReview)
    }

    @Test
    fun noSignalTranscript_landsInInboxAndIsFlaggedNeedsReview() = runTest {
        val transcript = "Walked through the courtyard after lunch and noticed the jacaranda trees blooming."

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertEquals("INBOX", assignment.bucketKind)
        assertTrue(assignment.tags.isEmpty())
        assertEquals(0, assignment.score)
        assertEquals(0.0f, assignment.confidence, 0.001f)
        assertTrue(assignment.needsReview)
    }

    @Test
    fun emptyOrBlankTranscript_landsInInboxAndIsFlaggedNeedsReview() = runTest {
        for (transcript in listOf("", "   ")) {
            val assignment = bucketer.assign(transcript, defaultProfile)

            assertEquals("INBOX", assignment.bucketKind)
            assertTrue(assignment.tags.isEmpty())
            assertEquals(0, assignment.score)
            assertEquals(0.0f, assignment.confidence, 0.001f)
            assertTrue(assignment.needsReview)
        }
    }

    @Test
    fun repoTaskDictation_filesAsTask() = runTest {
        val transcript =
            "So now I'd like to file issues on the repo for this app so that we can have our agents pick up new tasks."

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertEquals("TASK", assignment.bucketKind)
        assertEquals(listOf("file", "pick-up", "tasks"), assignment.tags.sorted())
        assertFalse(assignment.needsReview)
        assertTrue(assignment.confidence >= 0.8f)
    }

    @Test
    fun voiceNoteAndTranscriptionDictation_filesAsNote() = runTest {
        val transcript = "this is a test voice note, we'd like to make sure transcription works"

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertEquals("NOTE", assignment.bucketKind)
        assertEquals(listOf("note", "transcription", "voice-note"), assignment.tags.sorted())
        assertFalse(assignment.needsReview)
        assertTrue(assignment.confidence >= 0.8f)
    }

    @Test
    fun groceryDictation_filesAsList() = runTest {
        val transcript = "Shopping list for groceries, milk, and coffee beans."

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertEquals("LIST", assignment.bucketKind)
        assertEquals(listOf("groceries", "list", "shopping"), assignment.tags.sorted())
        assertFalse(assignment.needsReview)
    }

    @Test
    fun explicitReminder_filesAsTask() = runTest {
        val transcript = "Remind me to pick up the prescription and email the accountant."

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertEquals("TASK", assignment.bucketKind)
        assertEquals(listOf("email", "pick-up", "remind-me"), assignment.tags.sorted())
        assertFalse(assignment.needsReview)
    }

    @Test
    fun whatIfIdea_filesAsIdea() = runTest {
        val transcript = "what if we built a local voice sorter"

        val assignment = bucketer.assign(transcript, defaultProfile)

        assertEquals("IDEA", assignment.bucketKind)
        assertEquals(listOf("what-if"), assignment.tags)
        assertFalse(assignment.needsReview)
    }
}
