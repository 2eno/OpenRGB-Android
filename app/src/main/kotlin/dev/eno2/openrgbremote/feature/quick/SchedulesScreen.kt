package dev.eno2.openrgbremote.feature.quick

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.EmptyState
import dev.eno2.openrgbremote.core.designsystem.component.SectionHeader
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.model.Schedule
import dev.eno2.openrgbremote.core.domain.repository.ScheduleRepository
import dev.eno2.openrgbremote.core.domain.usecase.ObserveProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import javax.inject.Inject

// ----------------------------------------------------------------------------------- list

data class SchedulesUiState(val loading: Boolean = true, val schedules: List<Schedule> = emptyList(), val profiles: List<Profile> = emptyList())

@HiltViewModel
class SchedulesViewModel @Inject constructor(
    private val schedules: ScheduleRepository,
    observeProfiles: ObserveProfiles,
) : ViewModel() {
    val state: StateFlow<SchedulesUiState> = combine(schedules.observeSchedules(), observeProfiles()) { list, profiles ->
        SchedulesUiState(false, list, profiles.custom + profiles.builtIn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SchedulesUiState())

    fun setEnabled(schedule: Schedule, enabled: Boolean) {
        viewModelScope.launch { schedules.save(schedule.copy(enabled = enabled)) }
    }
}

@Composable
fun SchedulesRoute(onBack: () -> Unit, onEdit: (Long) -> Unit, viewModel: SchedulesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SchedulesScreen(state, onBack, onEdit, viewModel::setEnabled)
}

/** "Mo, Di, Fr", or "Täglich"/"Werktags"/"Wochenende" for the usual sets. */
@Composable
fun daysLabel(days: Set<DayOfWeek>): String {
    val locale = LocalConfiguration.current.locales[0]
    val weekdays = DayOfWeek.entries.take(5).toSet()
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    return when (days) {
        DayOfWeek.entries.toSet() -> stringResource(R.string.schedule_every_day)
        weekdays -> stringResource(R.string.schedule_weekdays)
        weekend -> stringResource(R.string.schedule_weekend)
        else -> DayOfWeek.entries.filter { it in days }.joinToString { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}

@Composable
private fun timeLabel(time: LocalTime): String =
    time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0]))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(state: SchedulesUiState, onBack: () -> Unit, onEdit: (Long) -> Unit, onEnabled: (Schedule, Boolean) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedules_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(0) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.schedule_add)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.loading && state.schedules.isEmpty()) {
                item { EmptyState(Icons.Outlined.Add, stringResource(R.string.schedules_title), stringResource(R.string.schedules_empty)) }
            }
            items(state.schedules, key = { it.id }) { schedule ->
                val time = timeLabel(schedule.time)
                val days = daysLabel(schedule.days)
                val action = quickActionName(schedule.action, state.profiles)
                val description = stringResource(R.string.schedule_description, time, days, action)
                Card(
                    onClick = { onEdit(schedule.id) },
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 6.dp).animateItem(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                        headlineContent = { Text(time, style = MaterialTheme.typography.headlineSmall) },
                        supportingContent = { Text("$days · $action") },
                        trailingContent = { Switch(checked = schedule.enabled, onCheckedChange = { onEnabled(schedule, it) }) },
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.schedules_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 640.dp).padding(vertical = 16.dp),
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------------- editor

data class ScheduleEditUiState(
    val schedule: Schedule = Schedule(time = LocalTime.of(23, 0), days = DayOfWeek.entries.toSet(), action = QuickAction.Off),
    val profiles: List<Profile> = emptyList(),
    val daysError: Boolean = false,
    val done: Boolean = false,
) {
    val isNew: Boolean get() = schedule.id == 0L
}

@HiltViewModel
class ScheduleEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val schedules: ScheduleRepository,
    private val alarms: ScheduleAlarmCanceller,
    observeProfiles: ObserveProfiles,
) : ViewModel() {
    private val local = MutableStateFlow(ScheduleEditUiState())

    val state: StateFlow<ScheduleEditUiState> = combine(local, observeProfiles()) { l, profiles -> l.copy(profiles = profiles.custom + profiles.builtIn) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        val id = savedStateHandle.get<Long>("scheduleId") ?: 0L
        if (id != 0L) viewModelScope.launch { schedules.getSchedule(id)?.let { s -> local.update { it.copy(schedule = s) } } }
    }

    private fun edit(transform: (Schedule) -> Schedule) = local.update { it.copy(schedule = transform(it.schedule), daysError = false) }

    fun setTime(hour: Int, minute: Int) = edit { it.copy(time = LocalTime.of(hour, minute)) }

    fun toggleDay(day: DayOfWeek) = edit { s -> s.copy(days = if (day in s.days) s.days - day else s.days + day) }

    fun setAction(action: QuickAction) {
        if (action !is QuickAction.StartEffect) edit { it.copy(action = action) }
    }

    fun setEnabled(enabled: Boolean) = edit { it.copy(enabled = enabled) }

    fun save() {
        val schedule = local.value.schedule
        if (schedule.days.isEmpty()) {
            local.update { it.copy(daysError = true) }
            return
        }
        viewModelScope.launch {
            schedules.save(schedule)
            local.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val id = local.value.schedule.id
        viewModelScope.launch {
            if (id != 0L) {
                alarms.cancel(id)
                schedules.delete(id)
            }
            local.update { it.copy(done = true) }
        }
    }
}

/** Cancelling is separate from [dev.eno2.openrgbremote.quick.ScheduleAlarms] so tests need no AlarmManager. */
fun interface ScheduleAlarmCanceller {
    fun cancel(id: Long)
}

@Composable
fun ScheduleEditRoute(onDone: () -> Unit, viewModel: ScheduleEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    ScheduleEditScreen(
        state,
        ScheduleEditActions(onDone, viewModel::setTime, viewModel::toggleDay, viewModel::setAction, viewModel::setEnabled, viewModel::save, viewModel::delete),
    )
}

data class ScheduleEditActions(
    val onBack: () -> Unit = {},
    val onTime: (Int, Int) -> Unit = { _, _ -> },
    val onToggleDay: (DayOfWeek) -> Unit = {},
    val onAction: (QuickAction) -> Unit = {},
    val onEnabled: (Boolean) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditScreen(state: ScheduleEditUiState, actions: ScheduleEditActions) {
    val schedule = state.schedule
    var pickTime by remember { mutableStateOf(false) }
    var pickAction by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.schedule_new_title else R.string.schedule_edit_title)) },
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                SectionHeader(stringResource(R.string.schedule_time))
                OutlinedCard(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(timeLabel(schedule.time), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(16.dp))
                }

                SectionHeader(stringResource(R.string.schedule_days))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in schedule.days,
                            onClick = { actions.onToggleDay(day) },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                        )
                    }
                }
                if (state.daysError) {
                    Text(stringResource(R.string.schedule_no_days), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                SectionHeader(stringResource(R.string.schedule_action))
                AssistChip(onClick = { pickAction = true }, label = { Text(quickActionName(schedule.action, state.profiles)) })

                ListItem(
                    modifier = Modifier.padding(top = 8.dp),
                    headlineContent = { Text(stringResource(R.string.schedule_enabled)) },
                    trailingContent = { Switch(checked = schedule.enabled, onCheckedChange = actions.onEnabled) },
                )
                Text(stringResource(R.string.schedules_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = actions.onSave) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }

    if (pickTime) {
        val context = LocalContext.current
        val timeState = rememberTimePickerState(schedule.time.hour, schedule.time.minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(timeState) },
            confirmButton = { TextButton(onClick = { actions.onTime(timeState.hour, timeState.minute); pickTime = false }) { Text(stringResource(R.string.action_done)) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (pickAction) {
        ActionPickerDialog(
            title = stringResource(R.string.schedule_action),
            current = schedule.action,
            profiles = state.profiles,
            allowEffects = false,
            allowNone = false,
            onDismiss = { pickAction = false },
            onPick = { action -> action?.let(actions.onAction); pickAction = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.schedule_delete_title)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDelete() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
