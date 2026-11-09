package io.github.openrgbremote.feature.profiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.openrgbremote.R
import io.github.openrgbremote.core.designsystem.component.SectionHeader
import io.github.openrgbremote.core.designsystem.component.icon
import io.github.openrgbremote.core.domain.model.DeviceKey
import io.github.openrgbremote.core.domain.model.Profile
import io.github.openrgbremote.core.domain.repository.ProfileImportError
import io.github.openrgbremote.ui.common.LoadingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Imports larger than this are certainly not profile files. */
private const val MAX_IMPORT_BYTES = 1_000_000

@Composable
fun ProfilesRoute(onEdit: (Long) -> Unit, viewModel: ProfilesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExport
        pendingExport = null
        if (uri != null && json != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null }.getOrDefault(false)
                }
                viewModel.onExportFinished(ok)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            // Manual loop: InputStream.readNBytes needs API 33.
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (out.size() <= MAX_IMPORT_BYTES) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                            }
                            if (out.size() > MAX_IMPORT_BYTES) null else out.toString(Charsets.UTF_8.name())
                        }
                    }.getOrNull()
                }
                viewModel.import(text)
            }
        }
    }

    ProfilesScreen(
        state = state,
        actions = ProfilesActions(
            onApply = viewModel::apply,
            onNew = { onEdit(0) },
            onEdit = onEdit,
            onDuplicate = viewModel::duplicate,
            onDelete = viewModel::delete,
            onExport = { profile, fileName ->
                viewModel.exportJson(profile)?.let {
                    pendingExport = it
                    exportLauncher.launch(fileName)
                }
            },
            onImport = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
            onMessageShown = viewModel::messageShown,
        ),
    )
}

data class ProfilesActions(
    val onApply: (Profile, Set<DeviceKey>?) -> Unit = { _, _ -> },
    val onNew: () -> Unit = {},
    val onEdit: (Long) -> Unit = {},
    val onDuplicate: (Profile, String) -> Unit = { _, _ -> },
    val onDelete: (Long) -> Unit = {},
    /** Profile null = all custom profiles; the string is the suggested file name. */
    val onExport: (Profile?, String) -> Unit = { _, _ -> },
    val onImport: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Composable
private fun messageText(message: ProfilesMessage?): String? = when (message) {
    null -> null
    is ProfilesMessage.Applied -> pluralStringResource(R.plurals.profile_applied, message.devices, message.devices)
    ProfilesMessage.NotReached -> stringResource(R.string.profile_not_applied)
    is ProfilesMessage.Imported -> pluralStringResource(R.plurals.profile_imported, message.count, message.count)
    is ProfilesMessage.ImportFailed -> stringResource(
        when (message.error) {
            ProfileImportError.NOT_JSON -> R.string.profile_import_not_json
            ProfileImportError.WRONG_FORMAT -> R.string.profile_import_wrong_format
            ProfileImportError.NEWER_VERSION -> R.string.profile_import_newer
            ProfileImportError.NO_PROFILES -> R.string.profile_import_empty
        },
    )
    ProfilesMessage.ImportUnreadable -> stringResource(R.string.profile_import_unreadable)
    ProfilesMessage.Exported -> stringResource(R.string.profile_exported)
    ProfilesMessage.ExportFailed -> stringResource(R.string.profile_export_failed)
    ProfilesMessage.NothingToExport -> stringResource(R.string.profile_export_nothing)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(state: ProfilesUiState, actions: ProfilesActions) {
    val snackbar = remember { SnackbarHostState() }
    val text = messageText(state.message)
    LaunchedEffect(state.message) {
        if (text != null) {
            // Dismiss the previous message so quick taps on several profiles do not queue up.
            snackbar.currentSnackbarData?.dismiss()
            actions.onMessageShown()
            snackbar.showSnackbar(text)
        }
    }
    var menu by remember { mutableStateOf(false) }
    var applyTo by remember { mutableStateOf<Profile?>(null) }
    var toDelete by remember { mutableStateOf<Profile?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_profiles)) },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profile_import)) },
                                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                                onClick = { menu = false; actions.onImport() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profile_export_all)) },
                                leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                                onClick = { menu = false; actions.onExport(null, "openrgb-profiles.json") },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onNew,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.profile_new)) },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingRow(stringResource(R.string.devices_loading), Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.profiles_custom)) }
                if (state.custom.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.profiles_custom_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.custom, key = { it.id }) { profile ->
                    ProfileCard(profile, actions, onApplyTo = { applyTo = profile }, onDelete = { toDelete = profile }, modifier = Modifier.animateItem())
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.profiles_builtin)) }
                items(state.builtIn, key = { it.id }) { profile ->
                    ProfileCard(profile, actions, onApplyTo = { applyTo = profile }, onDelete = {})
                }
            }
        }
    }

    applyTo?.let { profile ->
        ApplyToDialog(
            title = stringResource(R.string.profile_apply_title, profileDisplayName(profile)),
            devices = state.devices,
            onDismiss = { applyTo = null },
            onConfirm = { keys -> actions.onApply(profile, keys); applyTo = null },
        )
    }
    toDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.profile_delete_title, profile.name)) },
            text = { Text(stringResource(R.string.profile_delete_text)) },
            confirmButton = { TextButton(onClick = { actions.onDelete(profile.id); toDelete = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Tap applies the profile to all devices; everything else is in the overflow menu. */
@Composable
private fun ProfileCard(profile: Profile, actions: ProfilesActions, onApplyTo: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val name = profileDisplayName(profile)
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            actions.onApply(profile, null)
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            ProfilePreview(profile, MaterialTheme.shapes.medium, Modifier.fillMaxWidth().height(64.dp))
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more) + ": " + name) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_apply_to)) },
                            leadingIcon = { Icon(Icons.Outlined.Devices, contentDescription = null) },
                            onClick = { menu = false; onApplyTo() },
                        )
                        if (!profile.isBuiltIn) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = { menu = false; actions.onEdit(profile.id) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_duplicate)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = { menu = false; actions.onDuplicate(profile, name) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_export)) },
                            leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                            onClick = { menu = false; actions.onExport(profile.copy(name = name), fileNameFor(name)) },
                        )
                        if (!profile.isBuiltIn) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                onClick = { menu = false; onDelete() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Mein Profil!" -> "mein-profil.json": safe on every file system. */
internal fun fileNameFor(name: String): String {
    val slug = name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').replace(Regex("-+"), "-")
    return (slug.ifEmpty { "profile" }).take(40) + ".json"
}

@Composable
private fun ApplyToDialog(title: String, devices: List<ApplyTargetOption>, onDismiss: () -> Unit, onConfirm: (Set<DeviceKey>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<DeviceKey>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (devices.isEmpty()) {
                Text(stringResource(R.string.profile_apply_none))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    devices.forEach { device ->
                        val checked = device.key in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(value = checked, role = Role.Checkbox) { selected = if (it) selected + device.key else selected - device.key }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Icon(device.type.icon, contentDescription = null)
                            Text(device.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
