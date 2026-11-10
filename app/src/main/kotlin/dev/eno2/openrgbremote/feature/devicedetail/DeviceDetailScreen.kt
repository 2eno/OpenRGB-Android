package dev.eno2.openrgbremote.feature.devicedetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.EmptyState
import dev.eno2.openrgbremote.core.designsystem.component.IconBadge
import dev.eno2.openrgbremote.core.designsystem.component.LedMatrix
import dev.eno2.openrgbremote.core.designsystem.component.LedSwatch
import dev.eno2.openrgbremote.core.designsystem.component.SectionHeader
import dev.eno2.openrgbremote.core.designsystem.component.icon
import dev.eno2.openrgbremote.core.designsystem.component.labelRes
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.model.Zone
import dev.eno2.openrgbremote.sdk.model.ZoneType
import dev.eno2.openrgbremote.ui.common.ConnectionStatusPill
import dev.eno2.openrgbremote.ui.common.LoadingRow

@Composable
fun DeviceDetailRoute(
    onBack: () -> Unit,
    onOpenColor: (deviceKey: String, zone: Int, led: Int) -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DeviceDetailScreen(
        state,
        DetailActions(
            onBack = onBack,
            onColor = { zone, led -> onOpenColor(state.encodedKey, zone, led) },
            onSelectMode = viewModel::selectMode,
            onModeChange = viewModel::updateActiveMode,
            onSaveMode = viewModel::saveActiveMode,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

data class DetailActions(
    val onBack: () -> Unit = {},
    /** Opens the color screen; zone and led are -1 for "whole device". */
    val onColor: (zone: Int, led: Int) -> Unit = { _, _ -> },
    val onSelectMode: (Int) -> Unit = {},
    val onModeChange: (DeviceMode) -> Unit = {},
    val onSaveMode: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/** Above this many LEDs a linear zone shows a sample plus "+N more" instead of every swatch. */
private const val MAX_SWATCHES = 240

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(state: DeviceDetailUiState, actions: DetailActions) {
    val device = state.device
    val snackbar = remember { SnackbarHostState() }
    val messageText = when (state.message) {
        DetailMessage.MODE_SAVED -> stringResource(R.string.mode_saved)
        DetailMessage.DEVICE_UNREACHABLE -> stringResource(R.string.mode_failed)
        null -> null
    }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            actions.onMessageShown()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(device?.label ?: state.keyName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        floatingActionButton = {
            if (device != null && state.status is ConnectionStatus.Connected) {
                ExtendedFloatingActionButton(
                    onClick = { actions.onColor(-1, -1) },
                    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_color)) },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingRow(stringResource(R.string.devices_loading), Modifier.padding(padding))
            device == null -> Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                EmptyState(Icons.Outlined.LinkOff, stringResource(R.string.detail_unavailable_title), stringResource(R.string.detail_unavailable_text)) {
                    ConnectionStatusPill(state.status)
                }
            }
            else -> DeviceDetailContent(device, state.serverName, state.status, actions, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DeviceDetailContent(device: OpenRgbDevice, serverName: String?, status: ConnectionStatus, actions: DetailActions, modifier: Modifier) {
    val connected = status is ConnectionStatus.Connected
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { HeaderCard(device, serverName, status) }
        item {
            SectionBlock {
                SectionHeader(stringResource(R.string.detail_section_modes))
                ModeChips(device, enabled = connected, onSelect = actions.onSelectMode)
                val active = device.activeMode
                if (active != null && connected) {
                    ModeSettingsCard(active, actions.onModeChange, actions.onSaveMode, Modifier.padding(top = 12.dp))
                }
            }
        }
        item { SectionBlock { SectionHeader(stringResource(R.string.detail_section_zones)) } }
        itemsIndexed(device.zones, key = { index, zone -> "$index-${zone.name}" }) { index, zone ->
            SectionBlock { ZoneCard(device, zone, index, connected, actions.onColor) }
        }
        item { SectionBlock { SectionHeader(stringResource(R.string.detail_section_info)); InfoCard(device, serverName) } }
    }
}

/** Keeps content readable on tablets by limiting its width. */
@Composable
private fun SectionBlock(content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(bottom = 8.dp)) { content() }
}

@Composable
private fun HeaderCard(device: OpenRgbDevice, serverName: String?, status: ConnectionStatus) {
    SectionBlock {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(device.type.icon, size = 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(device.type.labelRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(device.label, style = MaterialTheme.typography.headlineSmall)
                Text(
                    listOfNotNull(device.vendor.takeIf { it.isNotBlank() }, serverName).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConnectionStatusPill(status, Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** One chip per hardware mode; tapping activates the mode with its current parameters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChips(device: OpenRgbDevice, enabled: Boolean, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        device.modes.forEachIndexed { index, mode ->
            val active = index == device.activeModeIndex
            FilterChip(
                selected = active,
                enabled = enabled,
                onClick = {
                    if (!active) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(index)
                    }
                },
                label = { Text(mode.name) },
                leadingIcon = if (active) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ZoneCard(device: OpenRgbDevice, zone: Zone, zoneIndex: Int, connected: Boolean, onColor: (zone: Int, led: Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(zone.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(zone.type.labelRes()) + " · " + pluralStringResource(R.plurals.led_count, zone.ledsCount, zone.ledsCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected && zone.ledsCount > 0) {
                    IconButton(onClick = { onColor(zoneIndex, -1) }) {
                        Icon(Icons.Outlined.Palette, contentDescription = stringResource(R.string.action_color_zone, zone.label))
                    }
                }
            }
            if (zone.isResizable) {
                Text(
                    stringResource(R.string.detail_zone_resizable, zone.ledsMin.toInt(), zone.ledsMax.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.padding(end = 8.dp)) {
                ZoneLeds(device, zone, onLedClick = if (connected) ({ led -> onColor(-1, zone.startIndex + led) }) else null)
            }
        }
    }
}

/**
 * LEDs of a zone. Tapping one opens the color screen for that LED. For TalkBack the zone is one
 * element with a summary; per-zone color is reachable through the zone's palette button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneLeds(device: OpenRgbDevice, zone: Zone, onLedClick: ((Int) -> Unit)?) {
    if (zone.ledsCount == 0) {
        Text(stringResource(R.string.detail_zone_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val zoneColors = (0 until zone.ledsCount).map { i ->
        val index = zone.startIndex + i
        if (index in 0 until device.colors.size) device.colors.rgbAt(index) else 0
    }
    val distinct = zoneColors.distinct()
    val ledText = pluralStringResource(R.plurals.led_count, zone.ledsCount, zone.ledsCount)
    val colorText = pluralStringResource(R.plurals.color_count, distinct.size, distinct.size)
    val summary = ledText + ", " + if (distinct.size <= 3) distinct.joinToString { "#%06X".format(it) } else colorText

    val matrix = zone.matrixMap
    if (zone.type.isMatrix && matrix != null) {
        val description = stringResource(R.string.detail_matrix_description, matrix.height, matrix.width) + ", " + summary
        LedMatrix(matrix, device.colors, zone.startIndex, Modifier.clearAndSetSemantics { contentDescription = description }, onLedClick)
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.clearAndSetSemantics { contentDescription = summary },
        ) {
            zoneColors.take(MAX_SWATCHES).forEachIndexed { index, rgb ->
                // 32dp tap area around the 22dp swatch: dense enough for long strips.
                Box(
                    Modifier.size(32.dp).clip(CircleShape).then(if (onLedClick != null) Modifier.clickable { onLedClick(index) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) { LedSwatch(rgb, size = 22.dp) }
            }
            if (zoneColors.size > MAX_SWATCHES) {
                val more = zoneColors.size - MAX_SWATCHES
                Text(pluralStringResource(R.plurals.more_leds, more, more), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun InfoCard(device: OpenRgbDevice, serverName: String?) {
    val rows = listOf(
        R.string.detail_type to stringResource(device.type.labelRes),
        R.string.detail_vendor to device.vendor,
        R.string.detail_description to device.description,
        R.string.detail_version to device.version,
        R.string.detail_serial to device.serial,
        R.string.detail_location to device.location,
        R.string.detail_server to serverName.orEmpty(),
    ).filter { it.second.isNotBlank() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            rows.forEachIndexed { i, (label, value) ->
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
                if (i < rows.lastIndex) HorizontalDivider()
            }
        }
    }
}

private fun ZoneType.labelRes(): Int = when (this) {
    ZoneType.SINGLE -> R.string.detail_zone_single
    ZoneType.LINEAR -> R.string.detail_zone_linear
    ZoneType.MATRIX -> R.string.detail_zone_matrix
    ZoneType.LINEAR_LOOP -> R.string.detail_zone_ring
    ZoneType.MATRIX_LOOP_X, ZoneType.MATRIX_LOOP_Y -> R.string.detail_zone_matrix_loop
    ZoneType.SEGMENTED -> R.string.detail_zone_segmented
    ZoneType.UNKNOWN -> R.string.detail_zone_other
}
