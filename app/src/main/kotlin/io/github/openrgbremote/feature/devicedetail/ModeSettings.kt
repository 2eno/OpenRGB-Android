package io.github.openrgbremote.feature.devicedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.openrgbremote.R
import io.github.openrgbremote.core.designsystem.component.ColorPickerPanel
import io.github.openrgbremote.core.designsystem.component.rgbColor
import io.github.openrgbremote.core.domain.model.HsvColor
import io.github.openrgbremote.sdk.model.ColorArray
import io.github.openrgbremote.sdk.model.ColorMode
import io.github.openrgbremote.sdk.model.DeviceMode
import io.github.openrgbremote.sdk.model.ModeDirection
import io.github.openrgbremote.sdk.model.ModeFlags
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Position of [value] between [min] and [max]; OpenRGB allows min > max (inverted speed scales). */
internal fun fractionOf(value: Long, min: Long, max: Long): Float =
    if (min == max) 0f else ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f) + 0f // + 0f turns -0.0 into 0.0

internal fun valueAt(fraction: Float, min: Long, max: Long): Long = (min + fraction * (max - min)).roundToLong()

internal fun directionOptions(mode: DeviceMode): List<Int> = buildList {
    if (mode.hasFlag(ModeFlags.HAS_DIRECTION_LR)) addAll(listOf(ModeDirection.LEFT, ModeDirection.RIGHT))
    if (mode.hasFlag(ModeFlags.HAS_DIRECTION_UD)) addAll(listOf(ModeDirection.UP, ModeDirection.DOWN))
    if (mode.hasFlag(ModeFlags.HAS_DIRECTION_HV)) addAll(listOf(ModeDirection.HORIZONTAL, ModeDirection.VERTICAL))
}

internal fun colorSourceOptions(mode: DeviceMode): List<Int> = buildList {
    if (mode.hasPerLedColor) add(ColorMode.PER_LED)
    if (mode.hasModeSpecificColor) add(ColorMode.MODE_SPECIFIC)
    if (mode.hasRandomColor) add(ColorMode.RANDOM)
}

/**
 * Parameters of the active hardware mode. Every change is applied to the device right away;
 * sliders apply when released so the device is not flooded with mode changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeSettingsCard(mode: DeviceMode, onChange: (DeviceMode) -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val directions = directionOptions(mode)
    val sources = colorSourceOptions(mode)
    val hasSpeed = mode.hasSpeed && mode.speedMin != mode.speedMax
    val hasBrightness = mode.hasBrightness && mode.brightnessMin != mode.brightnessMax
    val showColors = mode.hasModeSpecificColor && mode.colorMode == ColorMode.MODE_SPECIFIC
    val hasAnything = hasSpeed || hasBrightness || directions.isNotEmpty() || sources.size > 1 || showColors || mode.canSave
    var editing by remember(mode.name) { mutableStateOf<Int?>(null) }

    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mode_settings, mode.name), style = MaterialTheme.typography.titleMedium)
            if (!hasAnything) {
                Text(stringResource(R.string.mode_no_settings), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasSpeed) {
                PercentSlider(stringResource(R.string.mode_speed), fractionOf(mode.speed, mode.speedMin, mode.speedMax)) {
                    onChange(mode.copy(speed = valueAt(it, mode.speedMin, mode.speedMax)))
                }
            }
            if (hasBrightness) {
                PercentSlider(stringResource(R.string.mode_brightness), fractionOf(mode.brightness, mode.brightnessMin, mode.brightnessMax)) {
                    onChange(mode.copy(brightness = valueAt(it, mode.brightnessMin, mode.brightnessMax)))
                }
            }
            if (directions.isNotEmpty()) {
                Text(stringResource(R.string.mode_direction), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    directions.forEach { direction ->
                        FilterChip(
                            selected = mode.direction == direction,
                            onClick = { onChange(mode.copy(direction = direction)) },
                            label = { Text(stringResource(directionLabel(direction))) },
                        )
                    }
                }
            }
            if (sources.size > 1) {
                Text(stringResource(R.string.mode_colors), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { source ->
                        FilterChip(
                            selected = mode.colorMode == source,
                            onClick = { onChange(mode.copy(colorMode = source)) },
                            label = { Text(stringResource(colorSourceLabel(source))) },
                        )
                    }
                }
            }
            if (showColors) ModeColors(mode, onChange, onEdit = { editing = it })
            if (mode.canSave) {
                OutlinedButton(onClick = onSave, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.mode_save), Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    editing?.let { index ->
        if (index in 0 until mode.colors.size) {
            ModeColorDialog(
                index = index,
                initial = mode.colors.rgbAt(index),
                onDismiss = { editing = null },
                onConfirm = { rgb ->
                    val colors = mode.colors.toIntArray().also { it[index] = rgb }
                    onChange(mode.copy(colors = ColorArray.of(colors)))
                    editing = null
                },
            )
        }
    }
}

@Composable
private fun PercentSlider(label: String, fraction: Float, onFinished: (Float) -> Unit) {
    // Local draft while dragging; the device state replaces it once the change is applied.
    var draft by remember(fraction) { mutableFloatStateOf(fraction) }
    val percent = stringResource(io.github.openrgbremote.core.designsystem.R.string.picker_percent, (draft * 100).roundToInt())
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(percent, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onFinished(draft) },
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = percent
            },
        )
    }
}

@Composable
private fun ModeColors(mode: DeviceMode, onChange: (DeviceMode) -> Unit, onEdit: (Int) -> Unit) {
    val count = mode.colors.size
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(count) { index ->
                val rgb = mode.colors.rgbAt(index)
                val label = stringResource(R.string.mode_color_edit, index + 1, "#%06X".format(rgb))
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = label) { onEdit(index) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(rgbColor(rgb)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
        }
        if (count > maxOf(mode.colorsMin, 1L)) {
            IconButton(onClick = { onChange(mode.copy(colors = ColorArray.of(mode.colors.toIntArray().copyOf(count - 1)))) }) {
                Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.mode_color_remove))
            }
        }
        if (count < mode.colorsMax) {
            IconButton(onClick = {
                val last = if (count > 0) mode.colors.rgbAt(count - 1) else 0xFFFFFF
                onChange(mode.copy(colors = ColorArray.of(mode.colors.toIntArray() + last)))
            }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.mode_color_add))
            }
        }
    }
}

@Composable
private fun ModeColorDialog(index: Int, initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var color by remember { mutableStateOf(HsvColor.fromRgb(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mode_color_dialog, index + 1)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ColorPickerPanel(color, onColorChange = { color = it }, onChangeFinished = {})
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color.toRgb()) }) { Text(stringResource(R.string.action_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun directionLabel(direction: Int): Int = when (direction) {
    ModeDirection.LEFT -> R.string.mode_dir_left
    ModeDirection.RIGHT -> R.string.mode_dir_right
    ModeDirection.UP -> R.string.mode_dir_up
    ModeDirection.DOWN -> R.string.mode_dir_down
    ModeDirection.HORIZONTAL -> R.string.mode_dir_horizontal
    else -> R.string.mode_dir_vertical
}

private fun colorSourceLabel(source: Int): Int = when (source) {
    ColorMode.PER_LED -> R.string.mode_colors_per_led
    ColorMode.MODE_SPECIFIC -> R.string.mode_colors_specific
    else -> R.string.mode_colors_random
}
