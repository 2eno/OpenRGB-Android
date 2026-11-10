package dev.eno2.openrgbremote.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eno2.openrgbremote.core.designsystem.R
import dev.eno2.openrgbremote.core.domain.model.HsvColor
import dev.eno2.openrgbremote.sdk.model.RgbColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val HueColors = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

/**
 * Hue/saturation wheel: the angle is the hue, the distance from the center the saturation.
 * Brightness is not part of the wheel (it has its own slider) but dims it slightly as feedback.
 *
 * TalkBack users get one description here and use the [HsvSliders], which offer the same control.
 */
@Composable
fun ColorWheel(
    color: HsvColor,
    onColorChange: (HsvColor) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(color)
    val change by rememberUpdatedState(onColorChange)
    val finished by rememberUpdatedState(onChangeFinished)
    val outline = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.picker_wheel, color.hue.roundToInt(), (color.saturation * 100).roundToInt())

    Canvas(
        modifier
            .aspectRatio(1f)
            .clearAndSetSemantics { contentDescription = description }
            .pointerInput(Unit) {
                fun pick(position: Offset) {
                    val radius = size.width / 2f
                    val dx = position.x - radius
                    val dy = position.y - radius
                    val hue = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
                    val saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
                    change(current.copy(hue = hue, saturation = saturation))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pick(down.position)
                    drag(down.id) {
                        it.consume()
                        pick(it.position)
                    }
                    finished()
                }
            },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(Brush.sweepGradient(HueColors, center), radius)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius), radius)
        drawCircle(Color.Black.copy(alpha = (1f - color.value) * 0.6f), radius)
        drawCircle(outline, radius, style = Stroke(1.dp.toPx()))

        val angle = Math.toRadians(color.hue.toDouble())
        val thumb = Offset(
            center.x + (cos(angle) * color.saturation * radius).toFloat(),
            center.y + (sin(angle) * color.saturation * radius).toFloat(),
        )
        val thumbRadius = 12.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.35f), thumbRadius + 1.5.dp.toPx(), thumb)
        drawCircle(Color.White, thumbRadius, thumb)
        drawCircle(rgbColor(color.toRgb()), thumbRadius - 3.dp.toPx(), thumb)
    }
}

/** Slider whose track shows what the value does (hue spectrum, saturation or brightness ramp). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradientSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    trackColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(trackColors))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            },
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = valueText
            },
        )
    }
}

/** Hue, saturation and brightness sliders; the precise and accessible alternative to the wheel. */
@Composable
fun HsvSliders(
    color: HsvColor,
    onColorChange: (HsvColor) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GradientSlider(
            label = stringResource(R.string.picker_hue),
            valueText = stringResource(R.string.picker_degrees, color.hue.roundToInt()),
            value = color.hue,
            onValueChange = { onColorChange(color.copy(hue = it)) },
            onValueChangeFinished = onChangeFinished,
            valueRange = 0f..359f,
            trackColors = HueColors,
        )
        GradientSlider(
            label = stringResource(R.string.picker_saturation),
            valueText = stringResource(R.string.picker_percent, (color.saturation * 100).roundToInt()),
            value = color.saturation,
            onValueChange = { onColorChange(color.copy(saturation = it)) },
            onValueChangeFinished = onChangeFinished,
            valueRange = 0f..1f,
            trackColors = listOf(rgbColor(color.copy(saturation = 0f, value = 1f).toRgb()), rgbColor(color.copy(saturation = 1f, value = 1f).toRgb())),
        )
        GradientSlider(
            label = stringResource(R.string.picker_brightness),
            valueText = stringResource(R.string.picker_percent, (color.value * 100).roundToInt()),
            value = color.value,
            onValueChange = { onColorChange(color.copy(value = it)) },
            onValueChangeFinished = onChangeFinished,
            valueRange = 0f..1f,
            trackColors = listOf(Color.Black, rgbColor(color.copy(value = 1f).toRgb())),
        )
    }
}

/**
 * Hex input. While the user types, the field keeps their text; it follows [color] again as soon
 * as the color is changed elsewhere (wheel, sliders, favorites).
 */
@Composable
fun HexField(
    color: HsvColor,
    onColorChange: (HsvColor) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(color.toHex()) }
    val parsed = RgbColor.parseHex(text)?.takeIf { text.trim().removePrefix("#").length == 6 }
    if (parsed != null && parsed.rgb != color.toRgb()) text = color.toHex() // changed elsewhere
    val isError = parsed == null && text.isNotBlank()
    val focus = LocalFocusManager.current

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it == '#' || it.isLetterOrDigit() }.take(7).uppercase()
            RgbColor.parseHex(text)?.takeIf { text.removePrefix("#").length == 6 }?.let {
                if (it.rgb != color.toRgb()) {
                    onColorChange(HsvColor.fromRgb(it.rgb))
                    onChangeFinished()
                }
            }
        },
        label = { Text(stringResource(R.string.picker_hex)) },
        supportingText = if (isError) ({ Text(stringResource(R.string.picker_hex_error)) }) else null,
        isError = isError,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier,
    )
}

/** Wheel, sliders and hex field stacked; used in dialogs. */
@Composable
fun ColorPickerPanel(
    color: HsvColor,
    onColorChange: (HsvColor) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    wheelSize: Dp = 220.dp,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ColorWheel(color, onColorChange, onChangeFinished, Modifier.widthIn(max = wheelSize).fillMaxWidth())
        HsvSliders(color, onColorChange, onChangeFinished, Modifier.fillMaxWidth())
        HexField(color, onColorChange, onChangeFinished, Modifier.fillMaxWidth())
    }
}
