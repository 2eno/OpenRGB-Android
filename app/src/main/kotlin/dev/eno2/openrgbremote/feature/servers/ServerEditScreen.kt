package dev.eno2.openrgbremote.feature.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.domain.model.SshAuth
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import dev.eno2.openrgbremote.core.domain.usecase.HostError
import dev.eno2.openrgbremote.core.domain.usecase.PortError
import dev.eno2.openrgbremote.ui.common.ProblemCard

@Composable
fun ServerEditRoute(onDone: () -> Unit, viewModel: ServerEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedId) { if (state.savedId != null) onDone() }
    ServerEditScreen(state, viewModel.formActions(), onBack = onDone)
}

data class ServerFormActions(
    val onName: (String) -> Unit = {},
    val onHost: (String) -> Unit = {},
    val onPort: (String) -> Unit = {},
    val onAutoConnect: (Boolean) -> Unit = {},
    val onTest: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onUseJumpHost: (Boolean) -> Unit = {},
    val onJumpHost: (String) -> Unit = {},
    val onJumpPort: (String) -> Unit = {},
    val onJumpUser: (String) -> Unit = {},
    val onJumpAuth: (SshAuth) -> Unit = {},
    val onJumpPassword: (String) -> Unit = {},
    val onTrustHostKey: () -> Unit = {},
    val onRejectHostKey: () -> Unit = {},
)

fun ServerEditViewModel.formActions() = ServerFormActions(
    onName = ::onNameChange,
    onHost = ::onHostChange,
    onPort = ::onPortChange,
    onAutoConnect = ::onAutoConnectChange,
    onTest = ::testConnection,
    onSave = ::save,
    onUseJumpHost = ::onUseJumpHostChange,
    onJumpHost = ::onJumpHostChange,
    onJumpPort = ::onJumpPortChange,
    onJumpUser = ::onJumpUserChange,
    onJumpAuth = ::onJumpAuthChange,
    onJumpPassword = ::onJumpPasswordChange,
    onTrustHostKey = ::trustHostKey,
    onRejectHostKey = ::rejectHostKey,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(state: ServerEditUiState, actions: ServerFormActions, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.edit_title_edit else R.string.edit_title_add)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ServerForm(state, actions, Modifier.widthIn(max = 600.dp))
        }
    }
}

/** The add/edit form; also embedded in the onboarding. */
@Composable
fun ServerForm(state: ServerEditUiState, actions: ServerFormActions, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.test) {
        when (state.test) {
            is TestState.Success -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            is TestState.Failure -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }
    val context = LocalContext.current
    // Android 13+ confirms copies itself; older versions get a short toast.
    val onCopied: (String) -> Unit = { text -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.edit_jump_switch)) },
            supportingContent = { Text(stringResource(R.string.edit_jump_switch_supporting)) },
            trailingContent = { Switch(checked = state.useJumpHost, onCheckedChange = actions.onUseJumpHost) },
        )
        AnimatedVisibility(state.useJumpHost) {
            JumpHostFields(state, actions, onCopied)
        }
        val viaJump = state.useJumpHost
        OutlinedTextField(
            value = state.host,
            onValueChange = actions.onHost,
            label = { Text(stringResource(if (viaJump) R.string.edit_host_via_jump else R.string.edit_host)) },
            supportingText = {
                // Same address as the jump host: OpenRGB runs there, and 127.0.0.1 would let it stay
                // off the network (plain "openrgb --server").
                val sameAsJump = viaJump && state.host.trim().isNotEmpty() && state.host.trim().equals(state.jumpHost.trim(), ignoreCase = true)
                val hint = when {
                    sameAsJump -> R.string.edit_host_same_as_jump
                    viaJump -> R.string.edit_host_via_jump_supporting
                    else -> R.string.edit_host_supporting
                }
                Text(state.hostError?.let { stringResource(it.messageRes()) } ?: stringResource(hint))
            },
            isError = state.hostError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.port,
            onValueChange = actions.onPort,
            label = { Text(stringResource(R.string.edit_port)) },
            supportingText = { Text(state.portError?.let { stringResource(it.messageRes()) } ?: stringResource(R.string.edit_port_supporting)) },
            isError = state.portError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = actions.onName,
            label = { Text(stringResource(R.string.edit_name)) },
            supportingText = { Text(stringResource(R.string.edit_name_supporting)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.servers_auto_connect)) },
            supportingContent = { Text(stringResource(R.string.edit_auto_connect_supporting)) },
            trailingContent = { Switch(checked = state.autoConnect, onCheckedChange = actions.onAutoConnect) },
        )

        AnimatedVisibility(state.duplicateOf != null) {
            Text(
                stringResource(R.string.edit_error_duplicate, state.duplicateOf.orEmpty()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        TestResult(state.test)
        (state.test as? TestState.UntrustedHostKey)?.let { pending ->
            HostKeyDialog(pending.key, pending.changed, state.jumpHost.trim(), actions.onTrustHostKey, actions.onRejectHostKey)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            OutlinedButton(onClick = actions.onTest, enabled = state.test != TestState.Running) {
                if (state.test == TestState.Running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.edit_testing), Modifier.padding(start = 8.dp))
                } else {
                    Text(stringResource(R.string.edit_test))
                }
            }
            Button(onClick = actions.onSave, enabled = !state.saving) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@Composable
private fun TestResult(test: TestState) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        AnimatedVisibility(test is TestState.Success) {
            val result = (test as? TestState.Success)?.result ?: return@AnimatedVisibility
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Column {
                        Text(stringResource(R.string.edit_test_success_title), style = MaterialTheme.typography.titleMedium)
                        val name = result.serverHostname ?: result.serverName ?: "OpenRGB"
                        Text(
                            stringResource(
                                R.string.edit_test_success_text,
                                name,
                                result.protocolVersion,
                                pluralStringResource(R.plurals.device_count, result.deviceCount, result.deviceCount),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(test is TestState.Failure) {
            val failure = test as? TestState.Failure ?: return@AnimatedVisibility
            ProblemCard(failure.problem, failure.detail, onRetry = null)
        }
    }
}

internal fun HostError.messageRes(): Int = when (this) {
    HostError.EMPTY -> R.string.edit_error_host_empty
    HostError.CONTAINS_SPACES -> R.string.edit_error_host_spaces
    HostError.INVALID -> R.string.edit_error_host_invalid
}

internal fun PortError.messageRes(): Int = when (this) {
    PortError.EMPTY -> R.string.edit_error_port_empty
    PortError.NOT_A_NUMBER -> R.string.edit_error_port_nan
    PortError.OUT_OF_RANGE -> R.string.edit_error_port_range
}
