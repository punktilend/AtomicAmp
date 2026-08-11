package com.atomic.atomicamp.engine.dsp

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessLevelerTest {

    private fun leveler() = LoudnessLeveler()

    @Test
    fun `starts at unity so playback never opens at the wrong level`() {
        assertEquals(1f, leveler().gain, 1e-6f)
    }

    @Test
    fun `a quiet track is gradually boosted`() {
        val leveler = leveler()
        val quiet = LoudnessLeveler.DEFAULT_TARGET_RMS / 4f
        repeat(20) { leveler.update(quiet, elapsedSeconds = 1f) }

        assertTrue("should have boosted", leveler.gain > 1f)
    }

    @Test
    fun `a loud track is gradually cut`() {
        val leveler = leveler()
        val loud = LoudnessLeveler.DEFAULT_TARGET_RMS * 4f
        repeat(20) { leveler.update(loud, elapsedSeconds = 1f) }

        assertTrue("should have cut", leveler.gain < 1f)
    }

    @Test
    fun `gain moves no faster than the tuned rate`() {
        // The property that separates leveling from compression: a huge level error must still
        // only move gain about a dB in a second.
        val leveler = leveler()
        val veryQuiet = LoudnessLeveler.DEFAULT_TARGET_RMS / 100f

        leveler.update(veryQuiet, elapsedSeconds = 1f)
        val afterOneSecond = 20f * kotlin.math.log10(leveler.gain)

        assertTrue(
            "moved ${afterOneSecond}dB in one second, should be about 1",
            afterOneSecond <= LoudnessLeveler.MAX_GAIN_CHANGE_DB_PER_SEC + 0.01f,
        )
    }

    @Test
    fun `correction is bounded in both directions`() {
        val boost = leveler()
        repeat(200) { boost.update(0.00001f, elapsedSeconds = 1f) }
        assertTrue(20f * kotlin.math.log10(boost.gain) <= LoudnessLeveler.MAX_GAIN_DB + 0.01f)

        val cut = leveler()
        repeat(200) { cut.update(0.9f, elapsedSeconds = 1f) }
        assertTrue(20f * kotlin.math.log10(cut.gain) >= LoudnessLeveler.MIN_GAIN_DB - 0.01f)
    }

    @Test
    fun `silence does not drive the gain up`() {
        val leveler = leveler()
        // A quiet intro or gap between tracks must not be mistaken for a quiet recording.
        repeat(100) { leveler.update(0f, elapsedSeconds = 1f) }

        assertEquals("gain must not drift during silence", 1f, leveler.gain, 1e-6f)
    }

    @Test
    fun `a fade to silence holds the gain it had reached`() {
        val leveler = leveler()
        repeat(5) { leveler.update(LoudnessLeveler.DEFAULT_TARGET_RMS / 4f, elapsedSeconds = 1f) }
        val beforeFade = leveler.gain

        repeat(50) { leveler.update(0.0000001f, elapsedSeconds = 1f) }

        assertEquals(beforeFade, leveler.gain, 1e-6f)
    }

    @Test
    fun `boosted peaks are limited rather than allowed to clip`() {
        val leveler = leveler()
        repeat(100) { leveler.update(LoudnessLeveler.DEFAULT_TARGET_RMS / 8f, elapsedSeconds = 1f) }
        assertTrue("precondition: gain is boosting", leveler.gain > 1.5f)

        // A peak that would exceed full scale once amplified.
        val limited = leveler.applyTo(0.9f)
        assertTrue("must not exceed the ceiling", abs(limited) <= LoudnessLeveler.LIMITER_CEILING + 1e-6f)
    }

    @Test
    fun `limiting preserves sign`() {
        val leveler = leveler()
        repeat(100) { leveler.update(LoudnessLeveler.DEFAULT_TARGET_RMS / 8f, elapsedSeconds = 1f) }

        assertTrue("a positive peak must stay positive", leveler.applyTo(0.9f) > 0f)
        assertTrue("a negative peak must stay negative", leveler.applyTo(-0.9f) < 0f)
    }

    @Test
    fun `reset returns to unity`() {
        val leveler = leveler()
        repeat(20) { leveler.update(0.01f, elapsedSeconds = 1f) }
        leveler.reset()

        assertEquals(1f, leveler.gain, 1e-6f)
    }

    @Test
    fun `already-correct audio is left alone`() {
        val leveler = leveler()
        repeat(30) { leveler.update(LoudnessLeveler.DEFAULT_TARGET_RMS, elapsedSeconds = 1f) }

        assertEquals("a track already at target needs no correction", 1f, leveler.gain, 0.02f)
    }

    @Test
    fun `rms is computed over the samples given`() {
        // Full-scale square wave: every sample is 1, so RMS is 1.
        assertEquals(1f, rmsOf(floatArrayOf(1f, -1f, 1f, -1f), 4), 1e-6f)
        assertEquals(0f, rmsOf(floatArrayOf(0f, 0f), 2), 1e-6f)
        assertEquals("no samples means no measurement", 0f, rmsOf(floatArrayOf(), 0), 1e-6f)
    }
}
