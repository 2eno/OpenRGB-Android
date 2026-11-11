package dev.eno2.openrgbremote.feature.servers

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.domain.model.SshAuth
import dev.eno2.openrgbremote.core.domain.model.SshHostKey
import dev.eno2.openrgbremote.core.domain.usecase.UsernameError
import kotlinx.coroutines.launch

/** SSH host, port, user and login of the jump host. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JumpHostFields(state: ServerEditUiState, actions: ServerFormActions, onCopied: (String) -> Unit) {
    val errors = state.jumpErrors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.jumpHost,
            onValueChange = actions.onJumpHost,
            label = { Text(stringResource(R.string.edit_jump_host)) },
            supportingText = { Text(errors?.host?.let { stringResource(it.messageRes()) } ?: stringResource(R.string.edit_jump_host_supporting)) },
            isError = errors?.host != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.jumpUser,
                onValueChange = actions.onJumpUser,
                label = { Text(stringResource(R.string.edit_jump_user)) },
                supportingText = errors?.username?.let { error -> { Text(stringResource(error.messageRes())) } },
                isError = errors?.username != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = state.jumpPort,
                onValueChange = actions.onJumpPort,
                label = { Text(stringResource(R.string.edit_jump_port)) },
                supportingText = { Text(errors?.port?.let { stringResource(it.messageRes()) } ?: stringResource(R.string.edit_jump_port_supporting)) },
                isError = errors?.port != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
        }

        Text(stringResource(R.string.edit_jump_auth), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(SshAuth.APP_KEY to R.string.edit_jump_auth_key, SshAuth.PASSWORD to R.string.edit_jump_auth_password)
            options.forEachIndexed { index, (auth, label) ->
                SegmentedButton(
                    selected = state.jumpAuth == auth,
                    onClick = { actions.onJumpAuth(auth) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(stringResource(label)) }
            }
        }
        when (state.jumpAuth) {
            SshAuth.PASSWORD -> PasswordField(state, actions)
            SshAuth.APP_KEY -> AppKeyCard(state, onCopied)
        }

        state.jumpHostKey?.let { key ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.edit_jump_fingerprint, key.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PasswordField(state: ServerEditUiState, actions: ServerFormActions) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val supporting = when {
        state.passwordError -> R.string.edit_jump_password_missing
        state.hasSavedPassword -> R.string.edit_jump_password_saved
        else -> R.string.edit_jump_password_new
    }
    OutlinedTextField(
        value = state.jumpPassword,
        onValueChange = actions.onJumpPassword,
        label = { Text(stringResource(R.string.edit_jump_password)) },
        supportingText = { Text(stringResource(supporting)) },
        isError = state.passwordError,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.edit_jump_password_hide else R.string.edit_jump_password_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The app's public key as a ready-to-run command, restricted to the OpenRGB tunnel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppKeyCard(state: ServerEditUiState, onCopied: (String) -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.edit_jump_key_copied)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, contentDescription = null)
                Text(stringResource(R.string.edit_jump_key_title), style = MaterialTheme.typography.titleSmall)
            }
            val line = state.authorizedKeyLine
            if (line == null) {
                Text(stringResource(R.string.edit_jump_key_loading), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val user = state.jumpUser.trim().ifEmpty { "…" }
            val target = state.host.trim().ifEmpty { "127.0.0.1" } + ":" + state.port.trim()
            Text(stringResource(R.string.edit_jump_key_text, user, target), style = MaterialTheme.typography.bodyMedium)
            val command = authorizedKeysCommand(line)
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("authorized_keys", command)))
                        onCopied(copied)
                    }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.edit_jump_key_copy))
                }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, command)
                    context.startActivity(Intent.createChooser(send, null))
                }) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.edit_jump_key_share))
                }
            }
        }
    }
}

/**
 * Trust on first use, as `ssh` asks "Are you sure you want to continue connecting?". The user
 * compares the fingerprint with `ssh-keygen -lf` on the jump host. A changed key is a warning.
 */
@Composable
internal fun HostKeyDialog(key: SshHostKey, changed: Boolean, jumpHost: String, onTrust: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(if (changed) Icons.Outlined.WarningAmber else Icons.Outlined.VerifiedUser, contentDescription = null) },
        iconContentColor = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        title = { Text(stringResource(if (changed) R.string.hostkey_title_changed else R.string.hostkey_title_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (changed) R.string.hostkey_text_changed else R.string.hostkey_text_new, jumpHost))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(key.type, style = MaterialTheme.typography.labelMedium)
                        Text(key.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.hostkey_compare), style = MaterialTheme.typography.bodySmall)
                Text("ssh-keygen -lf /etc/ssh/${hostKeyFile(key.type)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onTrust,
                colors = if (changed) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(stringResource(if (changed) R.string.hostkey_trust_changed else R.string.hostkey_trust)) }
        },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** OpenSSH's file name of the host key with this algorithm. */
internal fun hostKeyFile(type: String): String = when {
    type == "ssh-ed25519" -> "ssh_host_ed25519_key.pub"
    type.startsWith("ecdsa-") -> "ssh_host_ecdsa_key.pub"
    type.startsWith("rsa-") || type == "ssh-rsa" -> "ssh_host_rsa_key.pub"
    else -> "ssh_host_*_key.pub"
}

internal fun UsernameError.messageRes(): Int = when (this) {
    UsernameError.EMPTY -> R.string.edit_error_user_empty
    UsernameError.INVALID -> R.string.edit_error_user_invalid
}
