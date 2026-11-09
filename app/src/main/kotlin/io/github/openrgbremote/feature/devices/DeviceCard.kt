package io.github.openrgbremote.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.openrgbremote.R
import io.github.openrgbremote.core.designsystem.component.ColorStrip
import io.github.openrgbremote.core.designsystem.component.IconBadge
import io.github.openrgbremote.core.designsystem.component.icon
import io.github.openrgbremote.core.designsystem.component.labelRes
import io.github.openrgbremote.sdk.model.OpenRgbDevice

/** Card on the overview: type icon, name, current mode and a preview of the current colors. */
@Composable
fun DeviceCard(device: OpenRgbDevice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val typeLabel = stringResource(device.type.labelRes)
    val modeName = device.activeMode?.name ?: "–"
    val modeText = stringResource(R.string.device_mode, modeName)
    val counts = buildString {
        append(pluralStringResource(R.plurals.led_count, device.ledCount, device.ledCount))
        append(" · ")
        append(pluralStringResource(R.plurals.zone_count, device.zones.size, device.zones.size))
    }
    val colorSummary = colorSummary(device)
    val description = stringResource(R.string.device_card_description, device.label, typeLabel, modeText) + ", " + counts + ", " + colorSummary

    ElevatedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onClick(); true }
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(device.type.icon)
                Column(Modifier.weight(1f)) {
                    Text(device.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(device.vendor, typeLabel).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(modeText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (device.ledCount > 0) {
                ColorStrip(device.colors)
            } else {
                Text(stringResource(R.string.device_no_leds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(counts, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Current colors: #FF0000, #0000FF and 3 more" style summary for TalkBack. */
@Composable
private fun colorSummary(device: OpenRgbDevice): String {
    val distinct = (0 until device.colors.size).map { device.colors.rgbAt(it) }.distinct()
    val text = when {
        distinct.isEmpty() -> stringResource(R.string.device_no_leds)
        distinct.size <= 3 -> distinct.joinToString { "#%06X".format(it) }
        else -> pluralStringResource(R.plurals.color_count, distinct.size, distinct.size)
    }
    return stringResource(R.string.device_colors_description, text)
}
