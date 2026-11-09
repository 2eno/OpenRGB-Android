package io.github.openrgbremote.feature.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.openrgbremote.R
import io.github.openrgbremote.core.designsystem.component.IconBadge
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.core.domain.model.ServerOverview
import io.github.openrgbremote.ui.common.ConnectionStatusPill
import io.github.openrgbremote.ui.common.LoadingRow

@Composable
fun ServersRoute(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onScan: () -> Unit,
    onAddManually: () -> Unit,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val servers by viewModel.state.collectAsStateWithLifecycle()
    ServersScreen(
        servers = servers,
        actions = ServerListActions(
            onBack = onBack,
            onEdit = onEdit,
            onScan = onScan,
            onAddManually = onAddManually,
            onAutoConnect = viewModel::setAutoConnect,
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            onDelete = viewModel::delete,
        ),
    )
}

data class ServerListActions(
    val onBack: () -> Unit = {},
    val onEdit: (Long) -> Unit = {},
    val onScan: () -> Unit = {},
    val onAddManually: () -> Unit = {},
    val onAutoConnect: (Server, Boolean) -> Unit = { _, _ -> },
    val onConnect: (Long) -> Unit = {},
    val onDisconnect: (Long) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(servers: List<ServerOverview>?, actions: ServerListActions) {
    var addMenu by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Server?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.servers_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_pc)) },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_scan_network)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        onClick = { addMenu = false; actions.onScan() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_enter_manually)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { addMenu = false; actions.onAddManually() },
                    )
                }
            }
        },
    ) { padding ->
        when {
            servers == null -> LoadingRow(stringResource(R.string.devices_loading), Modifier.padding(padding))
            servers.isEmpty() -> Text(stringResource(R.string.servers_empty), Modifier.padding(padding).padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(servers, key = { it.server.id }) { overview ->
                    ServerCard(overview, actions, onDelete = { toDelete = overview.server }, modifier = Modifier.widthIn(max = 720.dp).animateItem())
                }
            }
        }
    }
    toDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.servers_delete_title, server.name)) },
            text = { Text(stringResource(R.string.servers_delete_text)) },
            confirmButton = {
                TextButton(onClick = { actions.onDelete(server.id); toDelete = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ServerCard(overview: ServerOverview, actions: ServerListActions, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val server = overview.server
    var menu by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column {
            Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Outlined.Computer)
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(server.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConnectionStatusPill(overview.status)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = { menu = false; actions.onEdit(server.id) },
                        )
                        if (overview.status == ConnectionStatus.Offline) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_connect)) },
                                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                                onClick = { menu = false; actions.onConnect(server.id) },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_disconnect)) },
                                leadingIcon = { Icon(Icons.Outlined.LinkOff, contentDescription = null) },
                                onClick = { menu = false; actions.onDisconnect(server.id) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            ListItem(
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                headlineContent = { Text(stringResource(R.string.servers_auto_connect)) },
                trailingContent = { Switch(checked = server.autoConnect, onCheckedChange = { actions.onAutoConnect(server, it) }) },
            )
        }
    }
}
