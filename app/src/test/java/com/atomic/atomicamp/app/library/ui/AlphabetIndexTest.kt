package com.atomic.atomicamp.app.library.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabetIndexTest {

    @Test
    fun `buckets by first letter, case insensitively`() {
        assertEquals("N", AlphabetIndex.bucketOf("Neon Fields"))
        assertEquals("N", AlphabetIndex.bucketOf("neon fields"))
    }

    @Test
    fun `leading articles are skipped so the library is not buried under T`() {
        assertEquals("B", AlphabetIndex.bucketOf("The Beatles"))
        assertEquals("T", AlphabetIndex.bucketOf("Tool"))
        assertEquals("C", AlphabetIndex.bucketOf("A Certain Ratio"))
        assertEquals("E", AlphabetIndex.bucketOf("An Emotional Fish"))
    }

    @Test
    fun `a name that merely starts with article letters is left alone`() {
        // "Theatre" starts with "The" but is not the article "The ".
        assertEquals("T", AlphabetIndex.bucketOf("Theatre of Tragedy"))
        assertEquals("A", AlphabetIndex.bucketOf("Anthrax"))
    }

    @Test
    fun `diacritics fold to their base letter`() {
        assertEquals("E", AlphabetIndex.bucketOf("Étienne"))
        assertEquals("A", AlphabetIndex.bucketOf("Ångström"))
        assertEquals("O", AlphabetIndex.bucketOf("Øystein"))
    }

    @Test
    fun `numbers and symbols share one bucket`() {
        assertEquals(AlphabetIndex.NON_ALPHA, AlphabetIndex.bucketOf("2Pac"))
        assertEquals(AlphabetIndex.NON_ALPHA, AlphabetIndex.bucketOf("!!!"))
        assertEquals(AlphabetIndex.NON_ALPHA, AlphabetIndex.bucketOf(""))
        assertEquals(AlphabetIndex.NON_ALPHA, AlphabetIndex.bucketOf("   "))
    }

    @Test
    fun `index points at the first entry of each bucket`() {
        val keys = listOf("ABBA", "Air", "Beck", "Björk", "Cream")
        assertEquals(listOf("A" to 0, "B" to 2, "C" to 4), AlphabetIndex.buildIndex(keys))
    }

    @Test
    fun `only buckets that occur are listed`() {
        val index = AlphabetIndex.buildIndex(listOf("Air", "Zappa"))
        assertEquals(listOf("A" to 0, "Z" to 1), index)
    }

    @Test
    fun `the symbol bucket sorts after Z`() {
        val index = AlphabetIndex.buildIndex(listOf("2Pac", "Air", "Zappa"))
        assertEquals(listOf("A" to 1, "Z" to 2, "#" to 0), index)
    }

    @Test
    fun `an empty library yields an empty rail`() {
        assertEquals(emptyList<Pair<String, Int>>(), AlphabetIndex.buildIndex(emptyList()))
    }
}
