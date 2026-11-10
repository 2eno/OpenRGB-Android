package dev.eno2.openrgbremote.feature.servers

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.EmptyState
import dev.eno2.openrgbremote.core.designsystem.component.IconBadge
import dev.eno2.openrgbremote.core.domain.model.DiscoveredServer
import dev.eno2.openrgbremote.core.domain.usecase.ScanResultItem
import dev.eno2.openrgbremote.core.domain.usecase.ScanState
import dev.eno2.openrgbremote.sdk.protocol.ProtocolConstants

@Composable
fun ScanRoute(
    onBack: () -> Unit,
    onSelect: (DiscoveredServer) -> Unit,
    onAddManually: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScanScreen(state, onBack, viewModel::scan, onSelect, onAddManually)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    state: ScanState,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onSelect: (DiscoveredServer) -> Unit,
    onAddManually: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = onRescan, enabled = !state.running) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.scan_again))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScanContent(state, onSelect, onAddManually, onRescan, Modifier.widthIn(max = 720.dp))
        }
    }
}

/** Progress, results and help texts of a scan; also embedded in the onboarding. */
@Composable
fun ScanContent(
    state: ScanState,
    onSelect: (DiscoveredServer) -> Unit,
    onAddManually: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.noLocalNetwork) {
            EmptyState(Icons.Outlined.WifiOff, stringResource(R.string.scan_no_network_title), stringResource(R.string.scan_no_network_text)) {
                OutlinedButton(onClick = onAddManually) { Text(stringResource(R.string.action_enter_manually)) }
            }
            return@Column
        }

        val network = state.network
        val headline = when {
            network == null -> stringResource(R.string.scan_preparing)
            state.running -> stringResource(R.string.scan_running, network)
            else -> stringResource(R.string.scan_done, network)
        }
        Text(headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.running) {
            if (state.total > 0) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        state.found.forEach { item -> ScanResultRow(item, onSelect) }

        if (!state.running && state.found.isEmpty()) {
            EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.scan_none_title), stringResource(R.string.scan_none_text)) {
                OutlinedButton(onClick = onRescan) { Text(stringResource(R.string.scan_again)) }
            }
        }
        OutlinedButton(onClick = onAddManually, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.action_enter_manually))
        }
    }
}

@Composable
private fun ScanResultRow(item: ScanResultItem, onSelect: (DiscoveredServer) -> Unit) {
    val server = item.server
    val address = if (server.port == ProtocolConstants.DEFAULT_PORT) server.host else "${server.host}:${server.port}"
    // The headline already shows the address when the PC sent no host name; do not repeat it.
    val location = if (server.suggestedName == server.host) null else address
    val supporting = if (server.isConfirmed) {
        stringResource(R.string.scan_confirmed, listOfNotNull(location, server.serverName).joinToString(" · ").ifEmpty { "OpenRGB" }, server.protocolVersion ?: 0)
    } else {
        listOfNotNull(location, stringResource(R.string.scan_unconfirmed)).joinToString("\n")
    }
    val enabled = !item.alreadySaved
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.scan_add_description, server.suggestedName)) {
                onSelect(server)
            },
            leadingContent = { IconBadge(if (server.isConfirmed) Icons.Outlined.Computer else Icons.AutoMirrored.Outlined.HelpOutline) },
            headlineContent = { Text(server.suggestedName) },
            supportingContent = { Text(supporting) },
            trailingContent = {
                if (item.alreadySaved) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.scan_saved), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = null)
                }
            },
        )
    }
}
