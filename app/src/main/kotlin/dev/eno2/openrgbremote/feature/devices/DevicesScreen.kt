package dev.eno2.openrgbremote.feature.devices

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.EmptyState
import dev.eno2.openrgbremote.core.designsystem.component.IconBadge
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.ServerOverview
import dev.eno2.openrgbremote.core.domain.model.problem
import dev.eno2.openrgbremote.ui.common.ConnectionStatusPill
import dev.eno2.openrgbremote.ui.common.LoadingRow
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.feature.effects.RunningCard
import dev.eno2.openrgbremote.ui.common.ProblemCard

@Composable
fun DevicesRoute(
    onOpenDevice: (String) -> Unit,
    onScan: () -> Unit,
    onAddManually: () -> Unit,
    onColorAll: () -> Unit,
    onOpenEffect: (EffectId) -> Unit = {},
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DevicesScreen(
        state = state,
        actions = DevicesActions(
            onOpenDevice = onOpenDevice,
            onRefresh = viewModel::refresh,
            onRetry = viewModel::retry,
            onConnect = viewModel::connect,
            onRescanHardware = viewModel::rescanHardware,
            onScan = onScan,
            onAddManually = onAddManually,
            onColorAll = onColorAll,
            onOpenEffect = onOpenEffect,
            onStopEffect = viewModel::stopEffect,
        ),
    )
}

data class DevicesActions(
    val onOpenDevice: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRetry: (Long) -> Unit = {},
    val onConnect: (Long) -> Unit = {},
    val onRescanHardware: (Long) -> Unit = {},
    val onScan: () -> Unit = {},
    val onAddManually: () -> Unit = {},
    val onColorAll: () -> Unit = {},
    val onOpenEffect: (EffectId) -> Unit = {},
    val onStopEffect: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(state: DevicesUiState, actions: DevicesActions) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val anyDevice = state.servers.any { it.devices.isNotEmpty() }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            if (anyDevice) {
                ExtendedFloatingActionButton(
                    onClick = actions.onColorAll,
                    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_color_all)) },
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_devices)) },
                actions = {
                    if (state.servers.isNotEmpty()) {
                        IconButton(onClick = actions.onRefresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingRow(stringResource(R.string.devices_loading), Modifier.padding(padding))
            state.servers.isEmpty() -> NoServers(actions, Modifier.padding(padding))
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = actions.onRefresh,
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    state.runningEffect?.let { running ->
                        item(key = "effect", span = { GridItemSpan(maxLineSpan) }, contentType = "effect") {
                            RunningCard(running, actions.onOpenEffect, actions.onStopEffect, Modifier.animateItem())
                        }
                    }
                    state.servers.forEach { overview -> serverSection(overview, actions) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.serverSection(overview: ServerOverview, actions: DevicesActions) {
    item(key = "server-${overview.server.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "server") {
        ServerHeader(overview, actions)
    }
    items(
        items = overview.devices,
        key = { "device-${it.key.encode()}" },
        contentType = { "device" },
    ) { remote ->
        DeviceCard(remote.device, onClick = { actions.onOpenDevice(remote.key.encode()) }, modifier = Modifier.animateItem())
    }
}

@Composable
private fun ServerHeader(overview: ServerOverview, actions: DevicesActions) {
    val status = overview.status
    Column(Modifier.fillMaxWidth().animateContentSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Outlined.Computer, container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(Modifier.weight(1f)) {
                Text(
                    overview.server.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                val subtitle = buildString {
                    append(overview.server.address)
                    if (status is ConnectionStatus.Connected) {
                        status.serverName?.let { append(" · ").append(it) }
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ConnectionStatusPill(status)
        }
        val problem = status.problem
        when {
            problem != null -> ProblemCard(
                problem = problem,
                detail = (status as? ConnectionStatus.Reconnecting)?.detail ?: (status as? ConnectionStatus.Failed)?.detail,
                onRetry = { actions.onRetry(overview.server.id) },
            )
            status == ConnectionStatus.Offline -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.devices_offline_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { actions.onConnect(overview.server.id) }) { Text(stringResource(R.string.action_connect)) }
                }
            }
            status is ConnectionStatus.Connected && overview.devices.isEmpty() -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.SearchOff, contentDescription = null)
                        Text(stringResource(R.string.devices_none_found_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(stringResource(R.string.devices_none_found_text), style = MaterialTheme.typography.bodyMedium)
                    if (status.protocolVersion >= 5) {
                        OutlinedButton(onClick = { actions.onRescanHardware(overview.server.id) }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.action_rescan_hardware))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoServers(actions: DevicesActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        EmptyState(
            icon = Icons.Outlined.Sensors,
            title = stringResource(R.string.devices_empty_title),
            message = stringResource(R.string.devices_empty_text),
        ) {
            Button(onClick = actions.onScan) { Text(stringResource(R.string.action_scan_network)) }
            OutlinedButton(onClick = actions.onAddManually) { Text(stringResource(R.string.action_enter_manually)) }
        }
    }
}
