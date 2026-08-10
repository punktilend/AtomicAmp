package com.atomic.atomicamp.engine.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeCurveTest {

    @Test
    fun `starts with the outgoing track alone`() {
        assertEquals(1f, CrossfadeCurve.outgoingGain(0f), 1e-6f)
        assertEquals(0f, CrossfadeCurve.incomingGain(0f), 1e-6f)
    }

    @Test
    fun `ends with the incoming track alone`() {
        assertEquals(0f, CrossfadeCurve.outgoingGain(1f), 1e-6f)
        assertEquals(1f, CrossfadeCurve.incomingGain(1f), 1e-6f)
    }

    @Test
    fun `holds constant power across the whole overlap`() {
        // The reason for an equal-power curve rather than a linear one: without this the middle
        // of every transition sags in volume.
        for (step in 0..100) {
            val progress = step / 100f
            assertEquals(
                "power should stay flat at progress=$progress",
                1f,
                CrossfadeCurve.combinedPower(progress),
                1e-5f,
            )
        }
    }

    @Test
    fun `a linear crossfade would sag, which is what this avoids`() {
        // Demonstrates the problem being solved: linear gains at the midpoint sum to 0.5 power.
        val linearPower = 0.5f * 0.5f + 0.5f * 0.5f
        assertTrue("linear midpoint is quieter", linearPower < 0.6f)
        assertEquals("equal-power midpoint is not", 1f, CrossfadeCurve.combinedPower(0.5f), 1e-5f)
    }

    @Test
    fun `both gains are equal at the midpoint`() {
        val out = CrossfadeCurve.outgoingGain(0.5f)
        val incoming = CrossfadeCurve.incomingGain(0.5f)
        assertEquals(out, incoming, 1e-6f)
    }

    @Test
    fun `gains move monotonically`() {
        var previousOut = CrossfadeCurve.outgoingGain(0f)
        var previousIn = CrossfadeCurve.incomingGain(0f)
        for (step in 1..100) {
            val progress = step / 100f
            val out = CrossfadeCurve.outgoingGain(progress)
            val incoming = CrossfadeCurve.incomingGain(progress)
            assertTrue("outgoing must never rise", out <= previousOut + 1e-6f)
            assertTrue("incoming must never fall", incoming >= previousIn - 1e-6f)
            previousOut = out
            previousIn = incoming
        }
    }

    @Test
    fun `progress outside the overlap is clamped rather than extrapolated`() {
        assertEquals(1f, CrossfadeCurve.outgoingGain(-5f), 1e-6f)
        assertEquals(0f, CrossfadeCurve.outgoingGain(5f), 1e-6f)
        assertEquals(0f, CrossfadeCurve.incomingGain(-5f), 1e-6f)
        assertEquals(1f, CrossfadeCurve.incomingGain(5f), 1e-6f)
    }

    @Test
    fun `gains never exceed unity, which would clip`() {
        for (step in 0..100) {
            val progress = step / 100f
            assertTrue(CrossfadeCurve.outgoingGain(progress) <= 1f)
            assertTrue(CrossfadeCurve.incomingGain(progress) <= 1f)
            assertTrue(CrossfadeCurve.outgoingGain(progress) >= 0f)
            assertTrue(CrossfadeCurve.incomingGain(progress) >= 0f)
        }
    }
}
