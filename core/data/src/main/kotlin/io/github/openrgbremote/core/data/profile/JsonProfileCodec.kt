package io.github.openrgbremote.core.data.profile

import io.github.openrgbremote.core.domain.model.ColorLayout
import io.github.openrgbremote.core.domain.model.Profile
import io.github.openrgbremote.core.domain.repository.ProfileCodec
import io.github.openrgbremote.core.domain.repository.ProfileDecodeResult
import io.github.openrgbremote.core.domain.repository.ProfileImportError
import io.github.openrgbremote.sdk.model.RgbColor
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Human-readable exchange format:
 *
 * ```json
 * {
 *   "format": "openrgb-remote-profiles",
 *   "schemaVersion": 1,
 *   "profiles": [
 *     { "name": "Ocean", "type": "static", "layout": "gradient", "brightness": 1.0,
 *       "colors": ["#001A66", "#0066FF"] }
 *   ]
 * }
 * ```
 *
 * Unknown fields are ignored so newer minor additions stay readable; a higher `schemaVersion`
 * is rejected instead of being half-understood. Entries of an unknown `type` (e.g. dynamic
 * profiles written by a newer app) and entries without valid colors are skipped.
 */
class JsonProfileCodec @Inject constructor() : ProfileCodec {

    @Serializable
    private data class FileDto(val format: String = "", val schemaVersion: Int = 1, val profiles: List<ProfileDto> = emptyList())

    @Serializable
    private data class ProfileDto(
        val name: String = "",
        val type: String = TYPE_STATIC,
        val layout: String = "gradient",
        val brightness: Float = 1f,
        val colors: List<String> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        isLenient = true
    }

    override fun encode(profiles: List<Profile>): String = json.encodeToString(
        FileDto.serializer(),
        FileDto(
            format = FORMAT,
            schemaVersion = SCHEMA_VERSION,
            profiles = profiles.map { p ->
                ProfileDto(p.name, TYPE_STATIC, p.layout.name.lowercase(), p.brightness, p.colors.map { "#%06X".format(it and 0xFFFFFF) })
            },
        ),
    )

    override fun decode(json: String): ProfileDecodeResult {
        val file = try {
            this.json.decodeFromString(FileDto.serializer(), json)
        } catch (_: SerializationException) {
            return ProfileDecodeResult.Failure(ProfileImportError.NOT_JSON)
        } catch (_: IllegalArgumentException) {
            return ProfileDecodeResult.Failure(ProfileImportError.NOT_JSON)
        }
        if (file.format != FORMAT) return ProfileDecodeResult.Failure(ProfileImportError.WRONG_FORMAT)
        if (file.schemaVersion > SCHEMA_VERSION) return ProfileDecodeResult.Failure(ProfileImportError.NEWER_VERSION)
        val profiles = file.profiles.mapNotNull { dto ->
            if (dto.type != TYPE_STATIC) return@mapNotNull null
            val colors = dto.colors.mapNotNull { RgbColor.parseHex(it)?.rgb }.take(Profile.MAX_COLORS)
            val name = dto.name.trim().take(Profile.MAX_NAME_LENGTH)
            if (colors.isEmpty() || name.isEmpty()) return@mapNotNull null
            Profile(
                name = name,
                colors = colors,
                layout = ColorLayout.entries.firstOrNull { it.name.equals(dto.layout, ignoreCase = true) } ?: ColorLayout.GRADIENT,
                brightness = if (dto.brightness.isNaN()) 1f else dto.brightness.coerceIn(0f, 1f),
            )
        }
        return if (profiles.isEmpty()) ProfileDecodeResult.Failure(ProfileImportError.NO_PROFILES) else ProfileDecodeResult.Success(profiles)
    }

    companion object {
        const val FORMAT = "openrgb-remote-profiles"
        const val SCHEMA_VERSION = 1
        private const val TYPE_STATIC = "static"
    }
}
