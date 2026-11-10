package dev.eno2.openrgbremote.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.EmptyState
import dev.eno2.openrgbremote.core.designsystem.component.SectionHeader
import dev.eno2.openrgbremote.core.designsystem.component.rgbColor
import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import dev.eno2.openrgbremote.ui.common.ColorPickerDialog
import dev.eno2.openrgbremote.ui.common.LoadingRow
import kotlin.math.roundToInt

@Composable
fun ProfileEditRoute(onDone: () -> Unit, viewModel: ProfileEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    ProfileEditScreen(
        state,
        ProfileEditActions(
            onBack = onDone,
            onName = viewModel::setName,
            onLayout = viewModel::setLayout,
            onBrightness = viewModel::setBrightness,
            onColor = viewModel::setColor,
            onAddColor = viewModel::addColor,
            onRemoveColor = viewModel::removeColor,
            onTry = viewModel::tryOnDevices,
            onTriedShown = viewModel::triedShown,
            onSave = viewModel::save,
        ),
    )
}

data class ProfileEditActions(
    val onBack: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onLayout: (ColorLayout) -> Unit = {},
    val onBrightness: (Float) -> Unit = {},
    val onColor: (Int, Int) -> Unit = { _, _ -> },
    val onAddColor: () -> Unit = {},
    val onRemoveColor: (Int) -> Unit = {},
    val onTry: () -> Unit = {},
    val onTriedShown: () -> Unit = {},
    val onSave: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreen(state: ProfileEditUiState, actions: ProfileEditActions) {
    val snackbar = remember { SnackbarHostState() }
    val tried = state.tried
    val triedText = when {
        tried == null -> null
        tried > 0 -> pluralStringResource(R.plurals.profile_applied, tried, tried)
        else -> stringResource(R.string.profile_not_applied)
    }
    LaunchedEffect(tried) {
        if (triedText != null) {
            snackbar.currentSnackbarData?.dismiss()
            actions.onTriedShown()
            snackbar.showSnackbar(triedText)
        }
    }
    var editing by remember { mutableStateOf<Int?>(null) }
    val profile = state.profile

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.profile_edit_title_new else R.string.profile_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingRow(stringResource(R.string.devices_loading), Modifier.padding(padding))
            state.missing -> Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                EmptyState(Icons.Outlined.LinkOff, stringResource(R.string.profile_missing), "")
            }
            else -> Column(
                Modifier.padding(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                    OutlinedTextField(
                        value = profile.name,
                        onValueChange = actions.onName,
                        label = { Text(stringResource(R.string.profile_name)) },
                        isError = state.nameError,
                        supportingText = if (state.nameError) ({ Text(stringResource(R.string.profile_error_name)) }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SectionHeader(stringResource(R.string.profile_preview))
                    ProfilePreview(profile, MaterialTheme.shapes.medium, Modifier.fillMaxWidth().height(56.dp))

                    SectionHeader(stringResource(R.string.profile_colors))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        profile.colors.forEachIndexed { index, rgb ->
                            val label = stringResource(R.string.profile_color_edit, index + 1, "#%06X".format(rgb))
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = label) { editing = index }
                                    .semantics { contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(38.dp).clip(CircleShape).background(rgbColor(rgb)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                            }
                        }
                        if (state.canAddColor) {
                            IconButton(onClick = actions.onAddColor) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.mode_color_add)) }
                        }
                    }

                    if (profile.colors.size > 1) {
                        SectionHeader(stringResource(R.string.profile_layout))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorLayout.entries.forEach { layout ->
                                FilterChip(
                                    selected = profile.layout == layout,
                                    onClick = { actions.onLayout(layout) },
                                    label = { Text(stringResource(layout.labelRes())) },
                                )
                            }
                        }
                    }

                    val brightnessLabel = stringResource(R.string.mode_brightness)
                    val percent = stringResource(dev.eno2.openrgbremote.core.designsystem.R.string.picker_percent, (profile.brightness * 100).roundToInt())
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(brightnessLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(percent, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = profile.brightness,
                        onValueChange = actions.onBrightness,
                        modifier = Modifier.semantics {
                            contentDescription = brightnessLabel
                            stateDescription = percent
                        },
                    )

                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        OutlinedButton(onClick = actions.onTry) { Text(stringResource(R.string.profile_try)) }
                        Button(onClick = actions.onSave) { Text(stringResource(R.string.action_save)) }
                    }
                }
            }
        }
    }

    editing?.let { index ->
        if (index in profile.colors.indices) {
            ColorPickerDialog(
                title = stringResource(R.string.color_dialog_title, index + 1),
                initial = profile.colors[index],
                onDismiss = { editing = null },
                onConfirm = { rgb -> actions.onColor(index, rgb); editing = null },
                onRemove = if (state.canRemoveColor) ({ actions.onRemoveColor(index); editing = null }) else null,
            )
        }
    }
}

private fun ColorLayout.labelRes(): Int = when (this) {
    ColorLayout.GRADIENT -> R.string.profile_layout_gradient
    ColorLayout.BLOCKS -> R.string.profile_layout_blocks
    ColorLayout.ALTERNATING -> R.string.profile_layout_alternating
}
