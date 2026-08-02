package com.atomic.atomicamp.app.ui

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * A [Slider] rotated to run bottom-to-top, so EQ bands can sit side by side across the screen the
 * way a physical graphic equalizer does.
 *
 * The target device is a 1024x600dp in-car head unit: wide, but far too short to stack eleven
 * horizontal sliders vertically. Rotating them trades scarce height for plentiful width.
 *
 * Compose has no vertical slider, so this measures the child with width/height constraints swapped
 * and then rotates it back into place -- the child still believes it is a normal horizontal slider,
 * which keeps its touch handling and accessibility semantics intact.
 */
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    ),
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(-placeable.width, 0)
                }
            },
    )
}
