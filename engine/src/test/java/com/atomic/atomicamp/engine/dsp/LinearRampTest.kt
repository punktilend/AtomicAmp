package com.atomic.atomicamp.engine.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearRampTest {

    /** 48kHz for 100ms = 4800 frames for a full 0..1 traverse. */
    private fun ramp(initial: Float = 1f, rampMs: Int = 100) =
        LinearRamp(initial).apply { configure(sampleRateHz = 48_000, rampMs = rampMs) }

    @Test
    fun `holds steady when already at target`() {
        val ramp = ramp(initial = 1f)
        repeat(100) { assertEquals(1f, ramp.nextFrame(), 0f) }
        assertTrue(ramp.isAtTarget)
    }

    @Test
    fun `fade out reaches exactly silence and stays there`() {
        val ramp = ramp(initial = 1f)
        ramp.rampTo(0f)
        repeat(4_800) { ramp.nextFrame() }

        assertEquals("should land exactly on zero", 0f, ramp.value, 1e-6f)
        // Must not undershoot into negative gain, which would invert the signal.
        repeat(100) { assertEquals(0f, ramp.nextFrame(), 0f) }
    }

    @Test
    fun `fade in reaches exactly unity and does not overshoot`() {
        val ramp = ramp(initial = 0f)
        ramp.rampTo(1f)
        repeat(4_800) { ramp.nextFrame() }

        assertEquals(1f, ramp.value, 1e-6f)
        repeat(100) { assertEquals("must not exceed unity gain", 1f, ramp.nextFrame(), 0f) }
    }

    @Test
    fun `is still mid-ramp before the configured duration elapses`() {
        val ramp = ramp(initial = 1f)
        ramp.rampTo(0f)
        repeat(2_400) { ramp.nextFrame() }

        assertEquals("halfway through 100ms should be about half gain", 0.5f, ramp.value, 0.01f)
        assertTrue(!ramp.isAtTarget)
    }

    @Test
    fun `reversing mid-ramp returns from wherever it had reached`() {
        val ramp = ramp(initial = 1f)
        ramp.rampTo(0f)
        repeat(1_000) { ramp.nextFrame() }
        val partway = ramp.value

        ramp.rampTo(1f)
        ramp.nextFrame()

        assertTrue("should climb from the partial value, not restart", ramp.value > partway)
    }

    @Test
    fun `snapping jumps immediately and clears any pending ramp`() {
        val ramp = ramp(initial = 1f)
        ramp.rampTo(0f)
        ramp.snapTo(0f)

        assertEquals(0f, ramp.value, 0f)
        assertTrue(ramp.isAtTarget)
    }

    @Test
    fun `a zero length ramp still completes rather than stalling`() {
        val ramp = ramp(initial = 1f, rampMs = 0)
        ramp.rampTo(0f)

        assertEquals("one frame should be enough", 0f, ramp.nextFrame(), 0f)
    }

    @Test
    fun `targets are clamped to a sane gain range`() {
        val ramp = ramp(initial = 0f)
        ramp.rampTo(5f)
        repeat(4_800) { ramp.nextFrame() }
        assertEquals(1f, ramp.value, 1e-6f)

        ramp.rampTo(-2f)
        repeat(4_800) { ramp.nextFrame() }
        assertEquals(0f, ramp.value, 1e-6f)
    }
}
