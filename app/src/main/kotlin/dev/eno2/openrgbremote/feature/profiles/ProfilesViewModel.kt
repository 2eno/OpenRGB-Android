package dev.eno2.openrgbremote.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.repository.ProfileCodec
import dev.eno2.openrgbremote.core.domain.repository.ProfileImportError
import dev.eno2.openrgbremote.core.domain.repository.ProfileRepository
import dev.eno2.openrgbremote.core.domain.usecase.ApplyProfile
import dev.eno2.openrgbremote.core.domain.usecase.DuplicateProfile
import dev.eno2.openrgbremote.core.domain.usecase.ImportProfiles
import dev.eno2.openrgbremote.core.domain.usecase.ImportProfilesResult
import dev.eno2.openrgbremote.core.domain.usecase.ObserveProfiles
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.sdk.model.DeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApplyTargetOption(val key: DeviceKey, val label: String, val type: DeviceType)

sealed interface ProfilesMessage {
    data class Applied(val devices: Int) : ProfilesMessage
    data object NotReached : ProfilesMessage
    data class Imported(val count: Int) : ProfilesMessage
    data class ImportFailed(val error: ProfileImportError) : ProfilesMessage
    data object ImportUnreadable : ProfilesMessage
    data object Exported : ProfilesMessage
    data object ExportFailed : ProfilesMessage
    data object NothingToExport : ProfilesMessage
}

data class ProfilesUiState(
    val loading: Boolean = true,
    val builtIn: List<Profile> = emptyList(),
    val custom: List<Profile> = emptyList(),
    val devices: List<ApplyTargetOption> = emptyList(),
    val message: ProfilesMessage? = null,
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    observeProfiles: ObserveProfiles,
    observeServerOverviews: ObserveServerOverviews,
    private val profiles: ProfileRepository,
    private val applyProfile: ApplyProfile,
    private val duplicateProfile: DuplicateProfile,
    private val importProfiles: ImportProfiles,
    private val codec: ProfileCodec,
) : ViewModel() {

    private val message = MutableStateFlow<ProfilesMessage?>(null)

    val state: StateFlow<ProfilesUiState> = combine(observeProfiles(), observeServerOverviews(), message) { lists, servers, msg ->
        ProfilesUiState(
            loading = false,
            builtIn = lists.builtIn,
            custom = lists.custom,
            devices = servers.flatMap { it.devices }.map { ApplyTargetOption(it.key, it.device.label, it.device.type) },
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    /** Applies to every connected device, or only to [only]. */
    fun apply(profile: Profile, only: Set<DeviceKey>? = null) {
        viewModelScope.launch {
            val reached = applyProfile(profile, only)
            message.value = if (reached > 0) ProfilesMessage.Applied(reached) else ProfilesMessage.NotReached
        }
    }

    /** [displayName] is the localized name shown to the user (presets are stored in English). */
    fun duplicate(profile: Profile, displayName: String) {
        viewModelScope.launch { duplicateProfile(profile, displayName) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { profiles.delete(id) }
    }

    /** JSON for one profile or, with null, for all custom profiles; null if there is nothing to export. */
    fun exportJson(profile: Profile?): String? {
        val list = profile?.let { listOf(it) } ?: state.value.custom
        if (list.isEmpty()) {
            message.value = ProfilesMessage.NothingToExport
            return null
        }
        return codec.encode(list)
    }

    fun onExportFinished(success: Boolean) {
        message.value = if (success) ProfilesMessage.Exported else ProfilesMessage.ExportFailed
    }

    /** [json] is null when the chosen file could not be read. */
    fun import(json: String?) {
        if (json == null) {
            message.value = ProfilesMessage.ImportUnreadable
            return
        }
        viewModelScope.launch {
            message.value = when (val result = importProfiles(json)) {
                is ImportProfilesResult.Imported -> ProfilesMessage.Imported(result.count)
                is ImportProfilesResult.Failed -> ProfilesMessage.ImportFailed(result.error)
            }
        }
    }

    fun messageShown() {
        message.value = null
    }
}
