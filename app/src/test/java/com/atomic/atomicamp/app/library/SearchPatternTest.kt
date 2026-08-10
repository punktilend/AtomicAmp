package com.atomic.atomicamp.app.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the LIKE-pattern escaping used by library search. Mirrors the transformation in
 * `LibraryRepository.search`, which can't be exercised here directly because it needs a database.
 *
 * Worth pinning down: an unescaped `%` silently turns a specific search into "match everything",
 * which looks like working software rather than a bug.
 */
class SearchPatternTest {

    /** Same escaping as LibraryRepository.search; the DAO query declares ESCAPE '\'. */
    private fun pattern(query: String): String {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    @Test
    fun `ordinary text is wrapped in wildcards`() {
        assertEquals("%Portishead%", pattern("Portishead"))
    }

    @Test
    fun `a literal percent is escaped so it does not match everything`() {
        assertEquals("%100\\%%", pattern("100%"))
    }

    @Test
    fun `a literal underscore is escaped so it does not match any single character`() {
        assertEquals("%track\\_01%", pattern("track_01"))
    }

    @Test
    fun `backslashes are escaped before the characters that use them`() {
        // Escaping backslash last would double-escape what earlier steps produced.
        assertEquals("%a\\\\b%", pattern("a\\b"))
    }

    @Test
    fun `a query that is only wildcards still matches literally`() {
        assertEquals("%\\%\\_%", pattern("%_"))
    }

    @Test
    fun `spaces and punctuation are left alone`() {
        assertEquals("%Sgt. Pepper's%", pattern("Sgt. Pepper's"))
    }
}
