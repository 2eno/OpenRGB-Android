package io.github.openrgbremote.feature.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.openrgbremote.core.domain.model.ColorLayout
import io.github.openrgbremote.core.domain.model.Profile
import io.github.openrgbremote.core.domain.usecase.ApplyProfile
import io.github.openrgbremote.core.domain.usecase.GetProfile
import io.github.openrgbremote.core.domain.usecase.ProfileError
import io.github.openrgbremote.core.domain.usecase.SaveProfile
import io.github.openrgbremote.core.domain.usecase.SaveProfileResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileEditUiState(
    val loading: Boolean = true,
    val profile: Profile = Profile(name = "", colors = listOf(0x0A84FF, 0xBF5AF2)),
    val nameError: Boolean = false,
    /** The profile to edit was deleted meanwhile. */
    val missing: Boolean = false,
    val saved: Boolean = false,
    /** Result of "try on devices": number of devices reached, null before the first try. */
    val tried: Int? = null,
) {
    val isNew: Boolean get() = profile.id == 0L
    val canAddColor: Boolean get() = profile.colors.size < Profile.MAX_COLORS
    val canRemoveColor: Boolean get() = profile.colors.size > 1
}

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getProfile: GetProfile,
    private val saveProfile: SaveProfile,
    private val applyProfile: ApplyProfile,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileEditUiState())
    val state: StateFlow<ProfileEditUiState> = _state.asStateFlow()

    init {
        val id = savedStateHandle.get<Long>("profileId") ?: 0L
        if (id == 0L) {
            _state.update { it.copy(loading = false) }
        } else {
            viewModelScope.launch {
                val profile = getProfile(id)?.takeIf { !it.isBuiltIn }
                _state.update { if (profile == null) it.copy(loading = false, missing = true) else it.copy(loading = false, profile = profile) }
            }
        }
    }

    private fun edit(transform: (Profile) -> Profile) = _state.update { it.copy(profile = transform(it.profile), tried = null) }

    fun setName(name: String) = _state.update { it.copy(profile = it.profile.copy(name = name.take(Profile.MAX_NAME_LENGTH)), nameError = false) }

    fun setLayout(layout: ColorLayout) = edit { it.copy(layout = layout) }

    fun setBrightness(brightness: Float) = edit { it.copy(brightness = brightness.coerceIn(0f, 1f)) }

    fun setColor(index: Int, rgb: Int) = edit { p -> if (index in p.colors.indices) p.copy(colors = p.colors.toMutableList().also { it[index] = rgb }) else p }

    /** Appends a copy of the last color, which the user then adjusts. */
    fun addColor() = edit { p -> if (p.colors.size < Profile.MAX_COLORS) p.copy(colors = p.colors + p.colors.last()) else p }

    fun removeColor(index: Int) = edit { p -> if (p.colors.size > 1 && index in p.colors.indices) p.copy(colors = p.colors.filterIndexed { i, _ -> i != index }) else p }

    /** Shows the unsaved profile on all connected devices. */
    fun tryOnDevices() {
        val profile = _state.value.profile
        viewModelScope.launch {
            val reached = applyProfile(profile)
            _state.update { it.copy(tried = reached) }
        }
    }

    fun triedShown() = _state.update { it.copy(tried = null) }

    fun save() {
        viewModelScope.launch {
            when (val result = saveProfile(_state.value.profile)) {
                is SaveProfileResult.Saved -> _state.update { it.copy(saved = true) }
                is SaveProfileResult.Invalid -> _state.update { it.copy(nameError = result.error == ProfileError.NAME_EMPTY) }
            }
        }
    }
}
