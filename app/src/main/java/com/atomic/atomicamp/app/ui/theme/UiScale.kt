package com.atomic.atomicamp.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes that have to differ between the head unit and a phone.
 *
 * `dp` is defined as 1/160 inch, so on a display that reports its density honestly a given dp is
 * a known physical size. The ATOTO does not: it reports 160 dpi while the panel is roughly
 * 210 ppi, so everything renders about 24% **smaller** there than the same number does on a
 * phone. Every size in this app was originally chosen to cancel that out.
 *
 * Which is exactly why they cannot stay hardcoded now the app runs on both. A 76dp row is 9.2mm
 * on the head unit and 12mm on a phone -- comfortable in a car, and a third taller than a normal
 * list item in the hand, which reads as though accessibility scaling had been left on.
 *
 * So the compensation is applied only where it is needed, and the trigger is the same fact that
 * caused the problem: a display claiming 160 dpi or less is being modest about its real pixel
 * density, and is in practice a dashboard rather than a phone.
 */
@Immutable
data class UiScale(
    val rowMinHeight: Dp,
    val queueRowMinHeight: Dp,
    val transportButtonHeight: Dp,
    val listColumnMinWidth: Dp,
    val railWidth: Dp,
)

/**
 * Deliberately well above the platform's 48dp minimum: on the unit that lands under 6mm, and it
 * gets used at arm's length in a moving vehicle.
 */
val CarUiScale = UiScale(
    rowMinHeight = 76.dp,
    queueRowMinHeight = 56.dp,
    transportButtonHeight = 72.dp,
    // Yields 3 columns across 1280dp of head unit.
    listColumnMinWidth = 380.dp,
    railWidth = 28.dp,
)

/** Generous by phone standards -- this is still a music player used one-handed -- but not comic. */
val PhoneUiScale = UiScale(
    rowMinHeight = 64.dp,
    queueRowMinHeight = 52.dp,
    transportButtonHeight = 56.dp,
    // One column on a phone in portrait, two on a tablet or landscape.
    listColumnMinWidth = 320.dp,
    railWidth = 24.dp,
)

val LocalUiScale = staticCompositionLocalOf { CarUiScale }
