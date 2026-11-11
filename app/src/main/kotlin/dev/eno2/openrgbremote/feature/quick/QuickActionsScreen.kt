package dev.eno2.openrgbremote.feature.quick

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.SectionHeader
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.ProfileRenderer
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.repository.QuickActionRepository
import dev.eno2.openrgbremote.core.domain.usecase.ObserveProfiles
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.feature.effects.EffectPreview
import dev.eno2.openrgbremote.feature.effects.effectNameRes
import dev.eno2.openrgbremote.feature.profiles.ProfilePreview
import dev.eno2.openrgbremote.feature.profiles.profileDisplayName
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuickActionsUiState(
    val slots: List<QuickAction?> = List(QuickAction.SLOTS) { null },
    /** Custom profiles first, then the built-in presets. */
    val profiles: List<Profile> = emptyList(),
)

@HiltViewModel
class QuickActionsViewModel @Inject constructor(
    private val quickActions: QuickActionRepository,
    observeProfiles: ObserveProfiles,
) : ViewModel() {
    val state: StateFlow<QuickActionsUiState> = combine(quickActions.observeSlots(), observeProfiles()) { slots, lists ->
        QuickActionsUiState(slots, lists.custom + lists.builtIn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuickActionsUiState())

    fun setSlot(index: Int, action: QuickAction?) {
        viewModelScope.launch { quickActions.setSlot(index, action) }
    }
}

/** Number of slots that also have a Quick Settings tile. */
private const val TILE_SLOTS = 3

@Composable
fun QuickActionsRoute(onBack: () -> Unit, viewModel: QuickActionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    QuickActionsScreen(state, onBack, viewModel::setSlot)
}

/** Name of an action as shown in lists; a deleted profile reads as "not set". */
@Composable
fun quickActionName(action: QuickAction?, profiles: List<Profile>): String = when (action) {
    null -> stringResource(R.string.quick_empty)
    QuickAction.Off -> stringResource(R.string.quick_off)
    is QuickAction.StartEffect -> stringResource(effectNameRes(action.effect))
    is QuickAction.ApplyProfile -> profiles.firstOrNull { it.id == action.profileId }?.let { profileDisplayName(it) } ?: stringResource(R.string.quick_empty)
}

/** Small color strip for an action. */
@Composable
fun QuickActionPreview(action: QuickAction?, profiles: List<Profile>, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    when (action) {
        is QuickAction.StartEffect -> EffectPreview(action.effect, EffectParams(), shape, modifier)
        is QuickAction.ApplyProfile -> profiles.firstOrNull { it.id == action.profileId }?.let { ProfilePreview(it, shape, modifier) }
        QuickAction.Off -> ProfilePreview(Profile(name = "", colors = listOf(0)), shape, modifier)
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsScreen(state: QuickActionsUiState, onBack: () -> Unit, onSetSlot: (Int, QuickAction?) -> Unit) {
    var picking by remember { mutableStateOf<Int?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quick_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            items(QuickAction.SLOTS) { index ->
                val action = state.slots.getOrNull(index)
                Card(
                    onClick = { picking = index },
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        overlineContent = {
                            Text(stringResource(if (index < TILE_SLOTS) R.string.quick_slot_tile else R.string.quick_slot_widget, index + 1))
                        },
                        headlineContent = { Text(quickActionName(action, state.profiles)) },
                        trailingContent = {
                            QuickActionPreview(action, state.profiles, Modifier.width(72.dp).height(24.dp))
                        },
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.quick_howto),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 640.dp).padding(vertical = 16.dp),
                )
            }
        }
    }
    picking?.let { index ->
        ActionPickerDialog(
            title = stringResource(R.string.quick_choose, index + 1),
            current = state.slots.getOrNull(index),
            profiles = state.profiles,
            allowEffects = true,
            allowNone = true,
            onDismiss = { picking = null },
            onPick = { action -> onSetSlot(index, action); picking = null },
        )
    }
}

/** Radio list of every possible action: none, all off, profiles and (optionally) effects. */
@Composable
fun ActionPickerDialog(
    title: String,
    current: QuickAction?,
    profiles: List<Profile>,
    allowEffects: Boolean,
    allowNone: Boolean,
    onDismiss: () -> Unit,
    onPick: (QuickAction?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (allowNone) Option(stringResource(R.string.quick_none), current == null) { onPick(null) }
                Option(stringResource(R.string.quick_off), current == QuickAction.Off) { onPick(QuickAction.Off) }
                SectionHeader(stringResource(R.string.quick_section_profiles))
                // Schedules cannot start effects, so dynamic profiles are left out there too.
                profiles.filter { allowEffects || !it.isDynamic }.forEach { profile ->
                    val action = QuickAction.ApplyProfile(profile.id)
                    Option(profileDisplayName(profile), current == action, colors = ProfileRenderer.preview(profile, 6).toList()) { onPick(action) }
                }
                if (allowEffects) {
                    SectionHeader(stringResource(R.string.quick_section_effects))
                    EffectId.entries.forEach { effect ->
                        val action = QuickAction.StartEffect(effect)
                        Option(stringResource(effectNameRes(effect)), current == action) { onPick(action) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Option(label: String, selected: Boolean, colors: List<Int> = emptyList(), onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
        if (colors.isNotEmpty()) {
            ProfilePreview(Profile(name = "", colors = colors), MaterialTheme.shapes.extraSmall, Modifier.size(width = 40.dp, height = 16.dp))
        }
    }
}
