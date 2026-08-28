package com.atomic.atomicamp.engine

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics

/**
 * Whether this device is a dashboard or something held in a hand.
 *
 * Several defaults genuinely differ between the two, and getting them from one signal keeps them
 * from drifting apart. Resuming playback by itself at power-on is the behaviour a head unit is
 * expected to have and is faintly alarming on a phone; sizing that suits arm's length in a car
 * looks like accessibility scaling in the hand.
 *
 * The test is density, because that is the fact that actually differs. The ATOTO reports 160 dpi
 * while its panel is roughly 210 ppi, so a dp is about 24% smaller there than the definition says.
 * Phones report 320-560 and tablets 240 or more; anything claiming mdpi or below is either a
 * dashboard or a screen viewed from far enough away that the same choices suit it.
 *
 * Android's own car UI mode would be the obvious signal and is not usable here: the ATOTO's AICE
 * firmware does not set it, which is of a piece with it shipping no document picker either.
 */
object DeviceProfile {

    fun isHeadUnitLike(context: Context): Boolean {
        val configuration = context.resources.configuration
        if (isCarUiMode(configuration)) return true
        return isLowDensity(context.resources.displayMetrics.densityDpi)
    }

    /** Pure, so the rule can be tested without a device. */
    fun isLowDensity(densityDpi: Int): Boolean = densityDpi <= DisplayMetrics.DENSITY_MEDIUM

    fun isCarUiMode(configuration: Configuration): Boolean =
        (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_CAR
}
