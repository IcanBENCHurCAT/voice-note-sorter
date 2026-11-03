// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQuerySanitizerTest {

    @Test
    fun blankOrEmptyReturnsNull() {
        assertNull(FtsQuerySanitizer.sanitize(""))
        assertNull(FtsQuerySanitizer.sanitize("   "))
        assertNull(FtsQuerySanitizer.sanitize("\t\n"))
    }

    @Test
    fun standardWordsAppendsPrefixAsterisk() {
        assertEquals("coffee*", FtsQuerySanitizer.sanitize("coffee"))
        assertEquals("coffee* beans*", FtsQuerySanitizer.sanitize("coffee beans"))
    }

    @Test
    fun malformedPunctuationAndQuotesAreStripped() {
        // Unclosed quotes, asterisks, hyphens, colons, parentheses
        assertEquals("coffee*", FtsQuerySanitizer.sanitize("\"coffee"))
        assertEquals("coffee*", FtsQuerySanitizer.sanitize("coffee\""))
        assertEquals("coffee*", FtsQuerySanitizer.sanitize("coffee*"))
        assertEquals("coffee* beans*", FtsQuerySanitizer.sanitize("\"coffee\" (beans):"))
        assertEquals("test*", FtsQuerySanitizer.sanitize("***test***"))
    }

    @Test
    fun onlyPunctuationReturnsNull() {
        assertNull(FtsQuerySanitizer.sanitize("\""))
        assertNull(FtsQuerySanitizer.sanitize("\"\"\""))
        assertNull(FtsQuerySanitizer.sanitize("***"))
        assertNull(FtsQuerySanitizer.sanitize("!@#$%^&*()_+"))
    }

    @Test
    fun booleanKeywordsAreFilteredSafely() {
        // Bare AND / OR / NOT / NEAR would cause FTS syntax errors
        assertNull(FtsQuerySanitizer.sanitize("AND"))
        assertNull(FtsQuerySanitizer.sanitize("OR"))
        assertNull(FtsQuerySanitizer.sanitize("NOT"))
        assertNull(FtsQuerySanitizer.sanitize("NEAR"))
        assertEquals("coffee* beans*", FtsQuerySanitizer.sanitize("coffee AND beans"))
        assertEquals("meeting* notes*", FtsQuerySanitizer.sanitize("meeting OR NOT notes"))
    }

    @Test
    fun unicodeWordsPreserved() {
        assertEquals("café*", FtsQuerySanitizer.sanitize("café"))
        assertEquals("résumé* update*", FtsQuerySanitizer.sanitize("résumé update"))
    }
}
