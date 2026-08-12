package com.atomic.atomicamp.app.library.lyrics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun parsesTimestampsIntoMilliseconds() {
        val lyrics = LrcParser.parse(
            """
            [00:01.00]First
            [00:12.34]Second
            [01:05.5]Third
            [02:00]Fourth
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        assertEquals(listOf(1_000L, 12_340L, 65_500L, 120_000L), lyrics.lines.map { it.timeMs })
        assertEquals("First", lyrics.lines[0].text)
    }

    /** Colon as the fraction separator turns up in the wild alongside the dot. */
    @Test
    fun acceptsColonFractionSeparator() {
        val lyrics = LrcParser.parse("[00:10:50]Line")
        assertEquals(10_500L, lyrics.lines.single().timeMs)
    }

    /** A repeated chorus is written once with several stamps, and must expand and sort. */
    @Test
    fun expandsMultipleTimestampsOnOneLine() {
        val lyrics = LrcParser.parse(
            """
            [00:30.00][01:30.00][02:30.00]Chorus
            [01:00.00]Verse
            """.trimIndent(),
        )
        assertEquals(4, lyrics.lines.size)
        assertEquals(listOf(30_000L, 60_000L, 90_000L, 150_000L), lyrics.lines.map { it.timeMs })
        assertEquals("Verse", lyrics.lines[1].text)
    }

    @Test
    fun ignoresMetadataTagsButKeepsRealLines() {
        val lyrics = LrcParser.parse(
            """
            [ar:Willie Nelson]
            [ti:Shotgun Willie]
            [00:05.00]Actual words
            """.trimIndent(),
        )
        assertEquals(1, lyrics.lines.size)
        assertEquals("Actual words", lyrics.lines.single().text)
    }

    @Test
    fun appliesOffset() {
        val lyrics = LrcParser.parse(
            """
            [offset:+500]
            [00:10.00]Shifted
            """.trimIndent(),
        )
        assertEquals(9_500L, lyrics.lines.single().timeMs)
    }

    /** A file with no timestamps is still lyrics, just not synced ones. */
    @Test
    fun fallsBackToUnsyncedText() {
        val lyrics = LrcParser.parse("Just some words\nOn two lines")
        assertFalse(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(-1, lyrics.indexAt(30_000))
    }

    @Test
    fun indexAtFindsCurrentLine() {
        val lyrics = LrcParser.parse("[00:00.00]A\n[00:10.00]B\n[00:20.00]C")
        assertEquals(-1, lyrics.indexAt(-1))
        assertEquals(0, lyrics.indexAt(0))
        assertEquals(0, lyrics.indexAt(9_999))
        assertEquals(1, lyrics.indexAt(10_000))
        assertEquals(2, lyrics.indexAt(999_999))
    }

    @Test
    fun blankInputIsEmpty() {
        assertTrue(LrcParser.parse("   ").isEmpty)
    }

    @Test
    fun findsSidecarBesideTheAudio() {
        val audio = File(folder.root, "01 Green Corn.flac").apply { writeText("x") }
        File(folder.root, "01 Green Corn.lrc").writeText("[00:02.00]Hello")

        val lyrics = LyricsLoader.forAudioFile(audio)!!
        assertTrue(lyrics.synced)
        assertEquals("Hello", lyrics.lines.single().text)
    }

    @Test
    fun sidecarWinsOverEmbedded() {
        val audio = File(folder.root, "song.flac").apply { writeText("x") }
        File(folder.root, "song.lrc").writeText("[00:01.00]From sidecar")

        val lyrics = LyricsLoader.forAudioFile(audio, embedded = "[00:01.00]From tag")!!
        assertEquals("From sidecar", lyrics.lines.single().text)
    }

    @Test
    fun fallsBackToEmbeddedWhenNoSidecar() {
        val audio = File(folder.root, "song.flac").apply { writeText("x") }
        val lyrics = LyricsLoader.forAudioFile(audio, embedded = "[00:01.00]From tag")!!
        assertEquals("From tag", lyrics.lines.single().text)
    }

    @Test
    fun nullWhenThereAreNoLyricsAnywhere() {
        val audio = File(folder.root, "song.flac").apply { writeText("x") }
        assertNull(LyricsLoader.forAudioFile(audio))
    }
}
