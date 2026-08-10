package com.atomic.atomicamp.app.library.ui

/**
 * Maps sort keys to the letter rail shown beside a long list.
 *
 * Pure logic, kept out of the composable so the bucketing rules can be tested: getting them wrong
 * sends a jump to the wrong place, which is worse than having no rail at all.
 */
object AlphabetIndex {

    /** Everything that isn't a letter groups under one heading, sorted after Z. */
    const val NON_ALPHA = "#"

    /**
     * The letter a sort key belongs under.
     *
     * Leading articles are skipped, because a library sorted with them intact buries most of the
     * collection under "T". Diacritics fold to their base letter so "Étienne" indexes under E.
     */
    fun bucketOf(sortKey: String): String {
        val stripped = stripLeadingArticle(sortKey.trim())
        val first = stripped.firstOrNull() ?: return NON_ALPHA
        val folded = foldDiacritic(first).uppercaseChar()
        return if (folded in 'A'..'Z') folded.toString() else NON_ALPHA
    }

    /**
     * Buckets present in [sortKeys], in rail order, each paired with the index of its first entry.
     * Only buckets that actually occur are returned — a rail full of letters that jump nowhere is
     * a worse target than a short one.
     */
    fun buildIndex(sortKeys: List<String>): List<Pair<String, Int>> {
        val firstOccurrence = LinkedHashMap<String, Int>()
        sortKeys.forEachIndexed { index, key ->
            firstOccurrence.putIfAbsent(bucketOf(key), index)
        }
        return firstOccurrence.entries
            .sortedWith(compareBy({ it.key == NON_ALPHA }, { it.key }))
            .map { it.key to it.value }
    }

    private fun stripLeadingArticle(value: String): String {
        for (article in listOf("the ", "a ", "an ")) {
            if (value.length > article.length && value.regionMatches(0, article, 0, article.length, ignoreCase = true)) {
                return value.substring(article.length).trimStart()
            }
        }
        return value
    }

    private fun foldDiacritic(c: Char): Char = when (c.lowercaseChar()) {
        'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'ô', 'ö', 'õ', 'ø' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'ñ' -> 'n'
        'ç' -> 'c'
        else -> c
    }
}
