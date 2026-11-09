package io.github.openrgbremote.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.openrgbremote.sdk.model.ColorArray
import io.github.openrgbremote.sdk.model.MatrixMap

private val MAX_CELL = 28.dp

/** Converts `0xRRGGBB` to an opaque Compose color. */
fun rgbColor(rgb: Int): Color = Color(rgb or (0xFF shl 24))

/**
 * Round LED swatch. The outline keeps black ("off") LEDs visible on dark backgrounds and white
 * ones on light backgrounds.
 */
@Composable
fun LedSwatch(rgb: Int, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(rgbColor(rgb))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/**
 * Horizontal gradient preview of a device's colors. Long LED lists are sampled down so the strip
 * stays smooth; a single color renders as a solid bar.
 */
@Composable
fun ColorStrip(colors: ColorArray, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val shape = RoundedCornerShape(height / 2)
    val samples = sampleColors(colors, maxSamples = 24)
    val base = modifier
        .fillMaxWidth()
        .height(height)
        .clip(shape)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    when (samples.size) {
        0 -> Box(base.background(MaterialTheme.colorScheme.surfaceVariant))
        1 -> Box(base.background(samples.single()))
        else -> Box(base.background(Brush.horizontalGradient(samples)))
    }
}

fun sampleColors(colors: ColorArray, maxSamples: Int): List<Color> {
    if (colors.isEmpty()) return emptyList()
    val count = minOf(colors.size, maxSamples)
    val sampled = List(count) { i -> rgbColor(colors.rgbAt(i * colors.size / count)) }
    return if (sampled.distinct().size == 1) listOf(sampled.first()) else sampled
}

/**
 * Draws a zone's matrix map: one rounded cell per position, colored with the LED's current
 * color, empty positions left blank. Drawn on a canvas because keyboards have 100+ cells.
 */
@Composable
fun LedMatrix(
    map: MatrixMap,
    colors: ColorArray,
    zoneStart: Int,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val aspect = map.width.toFloat() / map.height.coerceAtLeast(1)
    // Cells are capped at MAX_CELL so small matrices (e.g. 3x10 underglow) do not become huge.
    Canvas(
        modifier
            .widthIn(max = MAX_CELL * map.width)
            .fillMaxWidth()
            .aspectRatio(if (aspect.isFinite() && aspect > 0f) aspect else 1f),
    ) {
        val cell = minOf(size.width / map.width, size.height / map.height)
        val gap = cell * 0.12f
        val radius = CornerRadius(cell * 0.2f)
        for (row in 0 until map.height) {
            for (column in 0 until map.width) {
                val led = map.ledAt(row, column) ?: continue
                val index = zoneStart + led
                val rgb = if (index in 0 until colors.size) colors.rgbAt(index) else 0
                val topLeft = Offset(column * cell + gap / 2, row * cell + gap / 2)
                val cellSize = Size(cell - gap, cell - gap)
                drawRoundRect(rgbColor(rgb), topLeft, cellSize, radius)
                drawRoundRect(outline, topLeft, cellSize, radius, style = Stroke(width = 1.dp.toPx()))
            }
        }
    }
}
