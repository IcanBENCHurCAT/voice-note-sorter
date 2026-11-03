// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

/**
 * Sanitizes raw user input into a safe FTS4 query string.
 *
 * SQLite FTS4 syntax supports phrases in double quotes and prefix matching with '*',
 * but malformed input such as unclosed quotes, standalone asterisks, or bare boolean operators
 * (AND, OR, NOT, NEAR) can trigger parse errors in the SQLite FTS engine.
 *
 * This sanitizer tokenizes words, strips unsafe punctuation/quotes, and formats them
 * into prefix-matching terms (e.g. `word*`) joined by spaces (implicit AND).
 */
object FtsQuerySanitizer {

    private val DISALLOWED_CHARS = Regex("""[^\p{L}\p{N}\s]""")

    /**
     * Sanitizes [rawQuery] into a valid SQLite FTS4 match query, or null if the query is blank
     * or contains no valid search tokens.
     */
    fun sanitize(rawQuery: String): String? {
        val cleaned = rawQuery.replace(DISALLOWED_CHARS, " ")
        val tokens = cleaned
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            // Filter out standalone SQLite FTS boolean keywords that cause syntax errors if not formatted
            .filterNot { token ->
                token.equals("AND", ignoreCase = true) ||
                    token.equals("OR", ignoreCase = true) ||
                    token.equals("NOT", ignoreCase = true) ||
                    token.equals("NEAR", ignoreCase = true)
            }

        if (tokens.isEmpty()) return null

        // Prefix match each token so typing e.g. "groce" matches "grocery"
        return tokens.joinToString(" ") { "$it*" }
    }
}
