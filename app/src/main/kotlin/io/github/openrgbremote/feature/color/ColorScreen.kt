package io.github.openrgbremote.feature.color

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.openrgbremote.R
import io.github.openrgbremote.core.designsystem.component.ColorWheel
import io.github.openrgbremote.core.designsystem.component.EmptyState
import io.github.openrgbremote.core.designsystem.component.HexField
import io.github.openrgbremote.core.designsystem.component.HsvSliders
import io.github.openrgbremote.core.designsystem.component.SectionHeader
import io.github.openrgbremote.core.designsystem.component.icon
import io.github.openrgbremote.core.designsystem.component.rgbColor
import io.github.openrgbremote.core.domain.model.DeviceKey
import io.github.openrgbremote.core.domain.model.HsvColor
import io.github.openrgbremote.ui.common.LoadingRow

@Composable
fun ColorRoute(onBack: () -> Unit, viewModel: ColorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ColorScreen(
        state = state,
        actions = ColorActions(
            onBack = onBack,
            onColorChange = viewModel::onColorChange,
            onChangeFinished = viewModel::onChangeFinished,
            onPickColor = viewModel::onPickColor,
            onApply = viewModel::apply,
            onUndo = viewModel::undo,
            onLivePreview = viewModel::setLivePreview,
            onToggleDevice = viewModel::toggleDevice,
            onToggleZone = viewModel::toggleZone,
            onToggleFavorite = viewModel::toggleFavorite,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

data class ColorActions(
    val onBack: () -> Unit = {},
    val onColorChange: (HsvColor) -> Unit = {},
    val onChangeFinished: () -> Unit = {},
    val onPickColor: (Int) -> Unit = {},
    val onApply: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onLivePreview: (Boolean) -> Unit = {},
    val onToggleDevice: (DeviceKey) -> Unit = {},
    val onToggleZone: (Int) -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/** Common lighting colors, including warm white and "off". */
val QuickColors = listOf(0xFFFFFF, 0xFFB46B, 0xFF0000, 0xFF6A00, 0xFFD000, 0x00FF00, 0x00FFC8, 0x00A2FF, 0x0000FF, 0x8A2BE2, 0xFF00C8, 0x000000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorScreen(state: ColorUiState, actions: ColorActions) {
    val snackbar = remember { SnackbarHostState() }
    val messageText = when (state.message) {
        ColorMessage.NONE_SELECTED -> stringResource(R.string.color_none_selected)
        ColorMessage.NOT_REACHED -> stringResource(R.string.color_not_reached)
        null -> null
    }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            actions.onMessageShown()
        }
    }
    val ledLabel = state.ledLabel
    val title = when {
        ledLabel != null -> stringResource(R.string.color_title_led, state.singleDeviceLabel.orEmpty(), ledLabel)
        state.singleDeviceLabel != null -> state.singleDeviceLabel
        else -> stringResource(R.string.color_title_all)
    }
    val hasTargets = state.ledLabel != null || state.devices.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    if (hasTargets) {
                        IconButton(onClick = actions.onUndo, enabled = state.canUndo) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.color_undo))
                        }
                        IconButton(onClick = actions.onToggleFavorite) {
                            if (state.isFavorite) {
                                Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.color_favorite_remove), tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(Icons.Outlined.StarBorder, contentDescription = stringResource(R.string.color_favorite_add))
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (hasTargets && !state.livePreview) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                        Button(onClick = actions.onApply) { Text(stringResource(R.string.color_apply)) }
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingRow(stringResource(R.string.devices_loading), Modifier.padding(padding))
            !hasTargets -> Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                EmptyState(Icons.Outlined.LinkOff, stringResource(R.string.color_no_devices_title), stringResource(R.string.color_no_devices_text))
            }
            else -> BoxWithConstraints(Modifier.padding(padding).imePadding().fillMaxSize()) {
                if (maxWidth >= 720.dp) {
                    // Tablet/landscape: wheel on the left, everything else scrolls on the right.
                    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ColorPreview(state.rgb)
                            ColorWheel(state.color, actions.onColorChange, actions.onChangeFinished, Modifier.widthIn(max = 360.dp).fillMaxWidth())
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                            Controls(state, actions)
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ColorPreview(state.rgb)
                        ColorWheel(state.color, actions.onColorChange, actions.onChangeFinished, Modifier.widthIn(max = 280.dp).fillMaxWidth())
                        Controls(state, actions)
                    }
                }
            }
        }
    }
}

/** Large swatch with the hex code, readable on any color. */
@Composable
private fun ColorPreview(rgb: Int) {
    val color = rgbColor(rgb)
    val hex = "#%06X".format(rgb)
    val description = stringResource(R.string.color_current, hex)
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(MaterialTheme.shapes.large)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(hex, color = if (color.luminance() > 0.4f) Color.Black else Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Controls(state: ColorUiState, actions: ColorActions) {
    Column(Modifier.fillMaxWidth()) {
        HsvSliders(state.color, actions.onColorChange, actions.onChangeFinished, Modifier.fillMaxWidth())
        HexField(state.color, actions.onColorChange, actions.onChangeFinished, Modifier.fillMaxWidth().padding(top = 8.dp))

        SectionHeader(stringResource(R.string.color_quick))
        SwatchRow(QuickColors, state.rgb, actions.onPickColor)

        SectionHeader(stringResource(R.string.color_favorites))
        if (state.favorites.isEmpty()) {
            Text(stringResource(R.string.color_favorites_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SwatchRow(state.favorites, state.rgb, actions.onPickColor)
        }

        if (state.devices.isNotEmpty()) {
            SectionHeader(stringResource(R.string.color_targets))
            DeviceChips(state.devices, actions.onToggleDevice)
        }
        if (state.zones.isNotEmpty()) {
            SectionHeader(stringResource(R.string.color_zones))
            ZoneChips(state.zones, actions.onToggleZone)
        }

        ListItem(
            modifier = Modifier.padding(top = 8.dp),
            headlineContent = { Text(stringResource(R.string.color_live)) },
            supportingContent = { Text(stringResource(R.string.color_live_supporting)) },
            trailingContent = { Switch(checked = state.livePreview, onCheckedChange = actions.onLivePreview) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(colors: List<Int>, selectedRgb: Int, onPick: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { rgb ->
            val color = rgbColor(rgb)
            val selected = rgb == selectedRgb
            val label = stringResource(R.string.color_item, "#%06X".format(rgb))
            // 48dp touch target around a 36dp swatch.
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = label) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onPick(rgb)
                    }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = if (color.luminance() > 0.4f) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceChips(devices: List<TargetDevice>, onToggle: (DeviceKey) -> Unit) {
    val noPreview = stringResource(R.string.color_mode_only)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        devices.forEach { device ->
            FilterChip(
                selected = device.selected,
                onClick = { onToggle(device.key) },
                label = { Text(if (device.livePreview) device.label else "${device.label} ($noPreview)") },
                leadingIcon = {
                    Icon(if (device.selected) Icons.Filled.Check else device.type.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneChips(zones: List<TargetZone>, onToggle: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        zones.forEach { zone ->
            FilterChip(
                selected = zone.selected,
                onClick = { onToggle(zone.index) },
                label = { Text(zone.label) },
                leadingIcon = if (zone.selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}
