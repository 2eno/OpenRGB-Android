package dev.eno2.openrgbremote.core.domain.usecase

import dev.eno2.openrgbremote.core.domain.model.BuiltInProfiles
import dev.eno2.openrgbremote.core.domain.model.ColorTarget
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.ProfileRenderer
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.domain.repository.LightingRepository
import dev.eno2.openrgbremote.core.domain.repository.ProfileCodec
import dev.eno2.openrgbremote.core.domain.repository.ProfileDecodeResult
import dev.eno2.openrgbremote.core.domain.repository.ProfileImportError
import dev.eno2.openrgbremote.core.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ProfileLists(val builtIn: List<Profile>, val custom: List<Profile>)

class ObserveProfiles(private val profiles: ProfileRepository) {
    operator fun invoke(): Flow<ProfileLists> = profiles.observeProfiles().map { ProfileLists(BuiltInProfiles.all, it) }
}

/** Finds a profile by id: negative ids are built-in, positive ones are stored. */
class GetProfile(private val profiles: ProfileRepository) {
    suspend operator fun invoke(id: Long): Profile? = if (id < 0) BuiltInProfiles.byId(id) else profiles.getProfile(id)
}

/**
 * Applies a profile to all connected devices or to [only] the given ones and returns how many
 * were reached. Devices without a per-LED mode get the profile's first color.
 */
class ApplyProfile(
    private val overviews: ObserveServerOverviews,
    private val lighting: LightingRepository,
    private val effects: EffectController,
) {
    suspend operator fun invoke(profile: Profile, only: Set<DeviceKey>? = null): Int {
        // A running effect would paint over the profile with its next frame.
        effects.stop()
        val devices = overviews().first().flatMap { it.devices }.filter { only == null || it.key in only }
        var reached = 0
        for (remote in devices) {
            val device = remote.device
            val ok = if (device.directModeIndex() != null && device.ledCount > 0) {
                lighting.setFrame(remote.key, ProfileRenderer.renderDevice(profile, device))
            } else {
                lighting.setColor(listOf(ColorTarget.Device(remote.key)), ProfileRenderer.dominantColor(profile), preview = false) > 0
            }
            if (ok) reached++
        }
        return reached
    }
}

enum class ProfileError { NAME_EMPTY, NO_COLORS }

sealed interface SaveProfileResult {
    data class Saved(val id: Long) : SaveProfileResult
    data class Invalid(val error: ProfileError) : SaveProfileResult
}

/** Validates and stores a custom profile; never overwrites a built-in one. */
class SaveProfile(private val profiles: ProfileRepository, private val clock: () -> Long = System::currentTimeMillis) {
    suspend operator fun invoke(profile: Profile): SaveProfileResult {
        val name = profile.name.trim().take(Profile.MAX_NAME_LENGTH)
        if (name.isEmpty()) return SaveProfileResult.Invalid(ProfileError.NAME_EMPTY)
        if (profile.colors.isEmpty()) return SaveProfileResult.Invalid(ProfileError.NO_COLORS)
        val existing = if (profile.id > 0) profiles.getProfile(profile.id) else null
        val stored = profile.copy(
            id = existing?.id ?: 0,
            name = name,
            colors = profile.colors.take(Profile.MAX_COLORS).map { it and 0xFFFFFF },
            brightness = profile.brightness.coerceIn(0f, 1f),
            presetKey = null,
            createdAtMillis = existing?.createdAtMillis ?: clock(),
        )
        return SaveProfileResult.Saved(profiles.save(stored))
    }
}

/** Copies any profile (also a built-in one) into an editable custom profile with a unique name. */
class DuplicateProfile(private val profiles: ProfileRepository, private val save: SaveProfile) {
    suspend operator fun invoke(source: Profile, displayName: String = source.name): Long? {
        val taken = profiles.observeProfiles().first().map { it.name }.toSet()
        val result = save(source.copy(id = 0, name = uniqueName(displayName, taken), presetKey = null))
        return (result as? SaveProfileResult.Saved)?.id
    }

    companion object {
        /** "Ocean" -> "Ocean 2" -> "Ocean 3"; an existing number is counted up instead of appended to. */
        fun uniqueName(name: String, taken: Set<String>): String {
            val base = name.trim()
            if (base !in taken) return base
            val match = Regex("^(.*\\S)\\s+(\\d+)$").find(base)
            val stem = match?.groupValues?.get(1) ?: base
            var number = (match?.groupValues?.get(2)?.toIntOrNull() ?: 1) + 1
            while (true) {
                val suffix = " $number"
                val candidate = stem.take(Profile.MAX_NAME_LENGTH - suffix.length) + suffix
                if (candidate !in taken) return candidate
                number++
            }
        }
    }
}

sealed interface ImportProfilesResult {
    data class Imported(val count: Int) : ImportProfilesResult
    data class Failed(val error: ProfileImportError) : ImportProfilesResult
}

/** Reads profiles from JSON and stores them as new custom profiles with unique names. */
class ImportProfiles(private val profiles: ProfileRepository, private val codec: ProfileCodec, private val save: SaveProfile) {
    suspend operator fun invoke(json: String): ImportProfilesResult {
        val decoded = when (val result = codec.decode(json)) {
            is ProfileDecodeResult.Failure -> return ImportProfilesResult.Failed(result.error)
            is ProfileDecodeResult.Success -> result.profiles
        }
        val taken = profiles.observeProfiles().first().map { it.name }.toMutableSet()
        var count = 0
        for (profile in decoded) {
            val name = DuplicateProfile.uniqueName(profile.name, taken)
            if (save(profile.copy(id = 0, name = name)) is SaveProfileResult.Saved) {
                taken += name
                count++
            }
        }
        return if (count == 0) ImportProfilesResult.Failed(ProfileImportError.NO_PROFILES) else ImportProfilesResult.Imported(count)
    }
}
