package com.atomic.atomicamp.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's theme, built for a dashboard rather than a phone.
 *
 * Three choices drive everything here, and each is about the device rather than taste:
 *
 *  - **Dark, always.** This is a screen at eye level in a dark car. The stock Material light
 *    scheme the app was inheriting puts a near-white panel in the driver's peripheral vision at
 *    night, which is glare on the glass and a wrecked dark adaptation. There is no light variant
 *    on purpose; a head unit is not a phone that follows the system at 2am.
 *  - **Amber accent.** Instrument clusters have been amber for decades for a good reason: it
 *    stays legible at low brightness and carries little blue light, so it costs the least night
 *    vision. Album art supplies the colour in a music player, so the chrome stays neutral.
 *  - **Larger type than Material's defaults.** The panel reports density 1.0, so every dp renders
 *    *physically smaller* here than the same dp on a phone -- the same trap that puts a 48dp
 *    touch target under 6mm on this unit. Type has to scale up to be read at arm's length.
 */
private val CarDarkColors = darkColorScheme(
    primary = Color(0xFFFFB35C),
    onPrimary = Color(0xFF3A2500),
    primaryContainer = Color(0xFF553600),
    onPrimaryContainer = Color(0xFFFFDDB3),

    secondary = Color(0xFFD7C3A5),
    onSecondary = Color(0xFF3A2F1B),
    secondaryContainer = Color(0xFF52452F),
    onSecondaryContainer = Color(0xFFF4DFC0),

    // Near-black rather than pure black: a true #000 panel shows every backlight blotch on an
    // automotive LCD, and the slight lift keeps elevation readable.
    background = Color(0xFF0D0F11),
    onBackground = Color(0xFFE7E9EB),
    surface = Color(0xFF15181B),
    onSurface = Color(0xFFE7E9EB),
    surfaceVariant = Color(0xFF262B30),
    onSurfaceVariant = Color(0xFFC5CBD1),

    outline = Color(0xFF8B939B),
    outlineVariant = Color(0xFF3A4046),

    error = Color(0xFFFFB4A8),
    onError = Color(0xFF561E14),
)

/**
 * Scaled for density 1.0 at arm's length. Body text carries the track titles that get read while
 * driving, so it takes the biggest jump relative to Material's phone-sized defaults.
 */
private val CarTypography = Typography(
    titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AtomicAmpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CarDarkColors,
        typography = CarTypography,
        content = content,
    )
}
