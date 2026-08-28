package com.atomic.atomicamp.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The density rule decides both how large the UI is drawn and whether playback resumes by itself
 * at power-on, so it is worth pinning against the real numbers rather than left as a guess.
 */
class DeviceProfileTest {

    @Test
    fun theAtotoCountsAsAHeadUnit() {
        // What the unit actually reports, read off its own diagnostics screen.
        assertTrue(DeviceProfile.isLowDensity(160))
    }

    @Test
    fun phonesDoNot() {
        // xhdpi through xxxhdpi covers essentially every phone in use.
        listOf(320, 420, 480, 560, 640).forEach {
            assertFalse("$it dpi should not be treated as a head unit", DeviceProfile.isLowDensity(it))
        }
    }

    @Test
    fun tabletsDoNot() {
        assertFalse(DeviceProfile.isLowDensity(240))
    }

    /** A genuinely low-density display gets the larger sizing, which is the right call for it. */
    @Test
    fun lowDensityDisplaysDo() {
        assertTrue(DeviceProfile.isLowDensity(120))
    }
}
