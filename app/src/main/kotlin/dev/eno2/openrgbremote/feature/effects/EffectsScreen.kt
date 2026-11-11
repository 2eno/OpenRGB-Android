package dev.eno2.openrgbremote.feature.effects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.SectionHeader
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.effects.EffectId

@Composable
fun EffectsRoute(onOpen: (EffectId) -> Unit, onOpenDevices: () -> Unit, viewModel: EffectsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EffectsScreen(state, onOpen, onStop = viewModel::stop, onOpenDevices = onOpenDevices)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsScreen(state: EffectsUiState, onOpen: (EffectId) -> Unit, onStop: () -> Unit, onOpenDevices: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_effects)) }) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 220.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(state.running != null) {
                    state.running?.let { RunningCard(it, onOpen, onStop, Modifier.padding(top = 8.dp)) }
                }
            }
            items(state.cards, key = { it.effect }) { card -> EffectCardView(card, onOpen) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SectionHeader(stringResource(R.string.effects_hardware_title))
                    HardwareHint(onOpenDevices)
                }
            }
        }
    }
}

/** The running effect with its stats and a stop button; also shown above the device list. */
@Composable
internal fun RunningCard(running: EffectState.Running, onOpen: (EffectId) -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val name = stringResource(effectNameRes(running.config.effect))
    Card(
        onClick = { onOpen(running.config.effect) },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EffectPreview(running.config.effect, running.config.params, MaterialTheme.shapes.small, Modifier.fillMaxWidth().height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(R.string.effects_running_on, name), style = MaterialTheme.typography.titleMedium)
                    val detail = if (running.deviceCount == 0) {
                        stringResource(R.string.effects_waiting)
                    } else {
                        stringResource(
                            R.string.effects_stats,
                            pluralStringResource(R.plurals.effect_device_count, running.deviceCount, running.deviceCount),
                            running.stats.fps.coerceAtMost(running.stats.targetFps),
                            running.stats.targetFps,
                        )
                    }
                    Text(detail, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Text(stringResource(R.string.action_stop))
                }
            }
            if (running.microphoneMissing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MicOff, contentDescription = null)
                    Text(stringResource(R.string.effect_mic_denied), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun EffectCardView(card: EffectCard, onOpen: (EffectId) -> Unit) {
    val runningLabel = stringResource(R.string.effects_running)
    ElevatedCard(
        onClick = { onOpen(card.effect) },
        modifier = Modifier.fillMaxWidth().semantics { if (card.running) stateDescription = runningLabel },
    ) {
        Column {
            EffectPreview(card.effect, card.params, MaterialTheme.shapes.medium, Modifier.fillMaxWidth().height(56.dp))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(effectNameRes(card.effect)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (card.running) Icon(Icons.Outlined.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(effectDescriptionRes(card.effect)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HardwareHint(onOpenDevices: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.DeveloperBoard, contentDescription = null)
                Text(stringResource(R.string.effects_hardware_text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = onOpenDevices, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_to_devices)) }
        }
    }
}
