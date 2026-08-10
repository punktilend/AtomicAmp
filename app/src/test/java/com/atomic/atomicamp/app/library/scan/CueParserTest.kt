package com.atomic.atomicamp.app.library.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueParserTest {

    /** Verbatim from a real EAC rip, CRLF and all. */
    private val realSheet = listOf(
        """REM DATE 1989""",
        """REM DISCID 9307ED0C""",
        """REM COMMENT "ExactAudioCopy v0.99pb4"""",
        """PERFORMER "NoFX"""",
        """TITLE "S&M Airlines"""",
        """FILE "NoFX - S&M Airlines.flac" WAVE""",
        """  TRACK 01 AUDIO""",
        """    TITLE "Day To Daze"""",
        """    PERFORMER "NoFX"""",
        """    INDEX 01 00:00:00""",
        """  TRACK 02 AUDIO""",
        """    TITLE "Five Feet Under"""",
        """    PERFORMER "NoFX"""",
        """    INDEX 00 01:58:06""",
        """    INDEX 01 01:58:13""",
        """  TRACK 03 AUDIO""",
        """    TITLE "Professional Crastination"""",
        """    PERFORMER "NoFX"""",
        """    INDEX 00 04:40:13""",
        """    INDEX 01 04:40:43""",
    ).joinToString("\r\n")

    @Test
    fun `reads disc level fields`() {
        val cue = CueParser.parse(realSheet)

        assertEquals("NoFX - S&M Airlines.flac", cue.audioFileName)
        assertEquals("S&M Airlines", cue.albumTitle)
        assertEquals("NoFX", cue.albumPerformer)
        assertEquals(1989, cue.year)
    }

    @Test
    fun `reads every audio track`() {
        val cue = CueParser.parse(realSheet)

        assertEquals(3, cue.tracks.size)
        assertEquals("Day To Daze", cue.tracks[0].title)
        assertEquals("Five Feet Under", cue.tracks[1].title)
        assertEquals("Professional Crastination", cue.tracks[2].title)
    }

    @Test
    fun `uses INDEX 01 rather than the pregap at INDEX 00`() {
        val cue = CueParser.parse(realSheet)

        // 01:58:13 -> 118s + 13/75s, not the 01:58:06 pregap.
        assertEquals(118_173L, cue.tracks[1].startMs)
    }

    @Test
    fun `each track ends where the next begins`() {
        val cue = CueParser.parse(realSheet)

        assertEquals(0L, cue.tracks[0].startMs)
        assertEquals(cue.tracks[1].startMs, cue.tracks[0].endMs)
        assertEquals(cue.tracks[2].startMs, cue.tracks[1].endMs)
    }

    @Test
    fun `the final track has no end, since the cue does not know the file length`() {
        val cue = CueParser.parse(realSheet)

        assertNull(cue.tracks.last().endMs)
    }

    @Test
    fun `frames convert at 75 per second`() {
        assertEquals(0L, CueParser.parseCueTime("00:00:00"))
        assertEquals(1_000L, CueParser.parseCueTime("00:01:00"))
        assertEquals(60_000L, CueParser.parseCueTime("01:00:00"))
        // 74 frames is just under a second; it must not round up to one.
        assertEquals(986L, CueParser.parseCueTime("00:00:74"))
    }

    @Test
    fun `minutes are not capped at sixty`() {
        // A full-disc rip runs well past an hour.
        assertEquals(75L * 60_000L, CueParser.parseCueTime("75:00:00"))
    }

    @Test
    fun `malformed times are rejected rather than guessed at`() {
        assertNull(CueParser.parseCueTime("01:02"))
        assertNull(CueParser.parseCueTime(""))
        assertNull(CueParser.parseCueTime("aa:bb:cc"))
        assertNull(CueParser.parseCueTime("-1:00:00"))
    }

    @Test
    fun `data tracks are skipped, being unplayable`() {
        val cue = CueParser.parse(
            """
            FILE "mixed.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Real Song"
                INDEX 01 00:00:00
              TRACK 02 MODE1/2352
                TITLE "Data"
                INDEX 01 03:00:00
            """.trimIndent(),
        )

        assertEquals(1, cue.tracks.size)
        assertEquals("Real Song", cue.tracks[0].title)
    }

    @Test
    fun `a track without a start index is dropped rather than placed at zero`() {
        val cue = CueParser.parse(
            """
            FILE "x.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Has Index"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "No Index"
            """.trimIndent(),
        )

        assertEquals(1, cue.tracks.size)
        assertEquals("Has Index", cue.tracks[0].title)
    }

    @Test
    fun `track performer overrides the disc performer for that track`() {
        val cue = CueParser.parse(
            """
            PERFORMER "Various Artists"
            FILE "comp.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Guest Spot"
                PERFORMER "Someone Else"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "House Band"
                INDEX 01 02:00:00
            """.trimIndent(),
        )

        assertEquals("Someone Else", cue.tracks[0].performer)
        assertEquals("falls back to the disc performer", "Various Artists", cue.tracks[1].performer)
    }

    @Test
    fun `a titleless track still gets a usable name`() {
        val cue = CueParser.parse(
            """
            FILE "x.flac" WAVE
              TRACK 07 AUDIO
                INDEX 01 00:00:00
            """.trimIndent(),
        )

        assertEquals("Track 7", cue.tracks[0].title)
    }

    @Test
    fun `filenames containing spaces survive the trailing type keyword`() {
        val cue = CueParser.parse("""FILE "Some Band - The Album (1999).flac" WAVE""")
        assertEquals("Some Band - The Album (1999).flac", cue.audioFileName)
    }

    @Test
    fun `a sheet naming several files is not treated as one splittable file`() {
        // Real case from the library: the album is already one FLAC per track, and each track's
        // INDEX 01 restarts at zero. Splitting on these would stack every track at 0:00.
        val cue = CueParser.parse(
            """
            PERFORMER "NoFX"
            TITLE "Ribbed"
            FILE "01 - Green Corn.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Green Corn"
                INDEX 01 00:00:00
            FILE "02 - The Malachi Crunch.flac" WAVE
              TRACK 02 AUDIO
                TITLE "The Malachi Crunch"
                INDEX 01 00:00:00
            """.trimIndent(),
        )

        assertEquals(2, cue.audioFileNames.size)
        assertTrue("must not be used for splitting", !cue.isSingleFile)
        assertNull(cue.audioFileName)
    }

    @Test
    fun `a single file sheet is splittable`() {
        val cue = CueParser.parse(realSheet)

        assertTrue(cue.isSingleFile)
        assertEquals("NoFX - S&M Airlines.flac", cue.audioFileName)
    }

    @Test
    fun `an unparseable sheet yields no tracks instead of throwing`() {
        val cue = CueParser.parse("this is not a cue sheet at all")
        assertTrue(cue.tracks.isEmpty())
    }
}
