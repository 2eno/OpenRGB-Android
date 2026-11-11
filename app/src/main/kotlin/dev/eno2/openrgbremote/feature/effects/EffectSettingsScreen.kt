package dev.eno2.openrgbremote.feature.effects

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.SectionHeader
import dev.eno2.openrgbremote.core.designsystem.component.icon
import dev.eno2.openrgbremote.core.designsystem.component.rgbColor
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.ui.common.ColorPickerDialog
import kotlin.math.roundToInt

@Composable
fun EffectSettingsRoute(onBack: () -> Unit, viewModel: EffectSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var micDenied by rememberSaveable { mutableStateOf(false) }
    fun micGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // The notification shows the stop action; the effect also runs if it is declined.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.start() }
    fun startWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start()
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) startWithNotification()
    }

    EffectSettingsScreen(
        state = state,
        microphone = when {
            !state.config.effect.needsMicrophone -> MicrophoneState.NOT_NEEDED
            LocalInspectionMode.current -> MicrophoneState.NEEDS_PERMISSION
            micGranted() -> MicrophoneState.GRANTED
            micDenied -> MicrophoneState.DENIED
            else -> MicrophoneState.NEEDS_PERMISSION
        },
        actions = EffectSettingsActions(
            onBack = onBack,
            onSpeed = viewModel::setSpeed,
            onReverse = viewModel::setReverse,
            onBrightness = viewModel::setBrightness,
            onColor = viewModel::setColor,
            onAddColor = viewModel::addColor,
            onRemoveColor = viewModel::removeColor,
            onResetColors = viewModel::resetColors,
            onToggleDevice = viewModel::toggleDevice,
            onStart = {
                if (state.config.effect.needsMicrophone && !micGranted()) {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    startWithNotification()
                }
            },
            onStop = viewModel::stop,
            onSaveAsProfile = viewModel::saveAsProfile,
            onSavedShown = viewModel::savedShown,
            onOpenAppSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ),
    )
}

enum class MicrophoneState { NOT_NEEDED, NEEDS_PERMISSION, GRANTED, DENIED }

data class EffectSettingsActions(
    val onBack: () -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onReverse: (Boolean) -> Unit = {},
    val onBrightness: (Float) -> Unit = {},
    val onColor: (Int, Int) -> Unit = { _, _ -> },
    val onAddColor: () -> Unit = {},
    val onRemoveColor: (Int) -> Unit = {},
    val onResetColors: () -> Unit = {},
    val onToggleDevice: (DeviceKey) -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    /** Argument: the localized effect name, used as the profile name. */
    val onSaveAsProfile: (String) -> Unit = {},
    val onSavedShown: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EffectSettingsScreen(state: EffectSettingsUiState, microphone: MicrophoneState, actions: EffectSettingsActions) {
    val effect = state.config.effect
    val params = state.config.params
    val canStart = state.devices.any { it.selected }
    var editing by remember { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val savedText = state.savedAs?.let { stringResource(R.string.effect_saved_as_profile, it) }
    LaunchedEffect(savedText) {
        if (savedText != null) {
            actions.onSavedShown()
            snackbar.showSnackbar(savedText)
        }
    }
    val effectName = stringResource(effectNameRes(effect))

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(effectName) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    // Keeps the settings beyond an app restart and puts them on the widget or a tile.
                    if (state.canSaveAsProfile) {
                        OutlinedButton(onClick = { actions.onSaveAsProfile(effectName) }) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.effect_save_as_profile))
                        }
                    }
                    if (state.running) {
                        Button(onClick = actions.onStop) { Text(stringResource(R.string.action_stop)) }
                    } else {
                        Button(onClick = actions.onStart, enabled = canStart) { Text(stringResource(R.string.action_start)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                EffectPreview(effect, params, MaterialTheme.shapes.medium, Modifier.fillMaxWidth().height(64.dp))
                Text(
                    stringResource(if (state.running) R.string.effect_live_hint else effectDescriptionRes(effect)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                if (microphone != MicrophoneState.NOT_NEEDED && microphone != MicrophoneState.GRANTED) {
                    MicrophoneCard(microphone, actions)
                }

                val speedLabel = stringResource(R.string.effect_speed)
                val speedText = stringResource(R.string.effect_speed_value, String.format(LocalConfiguration.current.locales[0], "%.1f", params.speed))
                LabeledValue(speedLabel, speedText)
                Slider(
                    value = params.speed,
                    onValueChange = actions.onSpeed,
                    valueRange = EffectParams.MIN_SPEED..EffectParams.MAX_SPEED,
                    modifier = Modifier.semantics {
                        contentDescription = speedLabel
                        stateDescription = speedText
                    },
                )

                if (effect.hasDirection) {
                    SectionHeader(stringResource(R.string.effect_direction))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !params.reverse, onClick = { actions.onReverse(false) }, label = { Text(stringResource(R.string.effect_forward)) })
                        FilterChip(selected = params.reverse, onClick = { actions.onReverse(true) }, label = { Text(stringResource(R.string.effect_backward)) })
                    }
                }

                if (effect.usesColors) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(stringResource(R.string.effect_colors), Modifier.weight(1f))
                        if (params.colors.isNotEmpty()) {
                            IconButton(onClick = actions.onResetColors) {
                                Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.effect_colors_reset))
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.palette.forEachIndexed { index, rgb ->
                            val label = stringResource(R.string.profile_color_edit, index + 1, "#%06X".format(rgb))
                            Box(
                                Modifier.size(48.dp).clip(CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = label) { editing = index }
                                    .semantics { contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(38.dp).clip(CircleShape).background(rgbColor(rgb)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                            }
                        }
                        if (state.palette.size < EffectParams.MAX_COLORS) {
                            IconButton(onClick = actions.onAddColor) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.mode_color_add)) }
                        }
                    }
                }

                val brightnessLabel = stringResource(R.string.mode_brightness)
                val brightnessText = stringResource(dev.eno2.openrgbremote.core.designsystem.R.string.picker_percent, (params.brightness * 100).roundToInt())
                LabeledValue(brightnessLabel, brightnessText)
                Slider(
                    value = params.brightness,
                    onValueChange = actions.onBrightness,
                    modifier = Modifier.semantics {
                        contentDescription = brightnessLabel
                        stateDescription = brightnessText
                    },
                )

                SectionHeader(stringResource(R.string.effect_devices))
                if (state.devices.isEmpty() && !state.loading) {
                    Text(stringResource(R.string.effect_no_per_led), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.devices.forEach { device ->
                            FilterChip(
                                selected = device.selected,
                                onClick = { actions.onToggleDevice(device.key) },
                                label = { Text(device.label) },
                                leadingIcon = {
                                    Icon(if (device.selected) Icons.Filled.Check else device.type.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { index ->
        val palette = state.palette
        if (index in palette.indices) {
            ColorPickerDialog(
                title = stringResource(R.string.color_dialog_title, index + 1),
                initial = palette[index],
                onDismiss = { editing = null },
                onConfirm = { rgb -> actions.onColor(index, rgb); editing = null },
                onRemove = if (palette.size > 1) ({ actions.onRemoveColor(index); editing = null }) else null,
            )
        }
    }
}

@Composable
internal fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MicrophoneCard(state: MicrophoneState, actions: EffectSettingsActions) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Mic, contentDescription = null)
                Text(stringResource(R.string.effect_mic_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(if (state == MicrophoneState.DENIED) R.string.effect_mic_denied else R.string.effect_mic_text), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.effect_mic_limit), style = MaterialTheme.typography.bodySmall)
            if (state == MicrophoneState.DENIED) {
                OutlinedButton(onClick = actions.onOpenAppSettings, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.effect_mic_open_settings)) }
            } else {
                TextButton(onClick = actions.onStart, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.effect_mic_allow)) }
            }
        }
    }
}
