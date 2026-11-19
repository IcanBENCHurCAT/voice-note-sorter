// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DeterministicTranscriptCleanerTest {

    private val cleaner = DeterministicTranscriptCleaner()

    @Test
    fun status_isAlwaysAvailable() = runTest {
        assertEquals(CapabilityStatus.AVAILABLE, cleaner.status())
    }

    @Test
    fun blankOrWhitespaceOnly_returnsEmptyString() = runTest {
        assertEquals("", cleaner.proofread(""))
        assertEquals("", cleaner.proofread("   \t\n  "))
    }

    @Test
    fun collapsesWhitespace_capitalizesSentenceStarts_andAddsTerminalPunctuation() = runTest {
        val raw = "  remind   me\tto\ncall   sarah tomorrow.  also buy\t\t3 apples!  did you confirm?  yes i did  "
        val expected = "Remind me to call sarah tomorrow. Also buy 3 apples! Did you confirm? Yes i did."

        assertEquals(expected, cleaner.proofread(raw))
    }

    @Test
    fun preservesExistingTerminalPunctuation_decimals_properNouns_andLeadingNumbers() = runTest {
        assertEquals("3.14 is pi. Next value is 42.", cleaner.proofread("3.14 is pi. next value is 42"))
        assertEquals("3 apples and 4 oranges.", cleaner.proofread("3 apples and 4 oranges"))
        assertEquals("Call Dr. Chen at 3:30 PM!", cleaner.proofread("call Dr. Chen at 3:30 PM!"))
        assertEquals("Is invoice 4812 paid?", cleaner.proofread("is invoice 4812 paid?"))
        assertEquals("\"Wait... What?\" She asked.", cleaner.proofread("\"wait... what?\" she asked"))
        assertEquals("\"Hello world.\"", cleaner.proofread("\"hello world.\""))
    }

    @Test
    fun doesNotAlterAddOrRemoveAnyWords() = runTest {
        val raw = "  meeting   with Dr. Chen at 10am   about M10 bolts and $1,250 budget  "
        val cleaned = cleaner.proofread(raw)

        val rawWords = raw.trim().split(Regex("\\s+")).map { it.lowercase(Locale.ROOT) }
        val cleanedWords = cleaned.trimEnd('.').split(Regex("\\s+")).map { it.lowercase(Locale.ROOT) }
        assertEquals(rawWords, cleanedWords)
    }

    @Test
    fun proofread_isIdempotent() = runTest {
        val raw = "  check Q4 roadmap with Alex.   confirm 2pm sync  "
        val first = cleaner.proofread(raw)
        val second = cleaner.proofread(first)
        assertEquals(first, second)
    }
}
