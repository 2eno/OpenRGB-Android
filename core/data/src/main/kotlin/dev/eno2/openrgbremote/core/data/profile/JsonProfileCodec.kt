package dev.eno2.openrgbremote.core.data.profile

import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.ProfileAnimation
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.core.domain.repository.ProfileCodec
import dev.eno2.openrgbremote.core.domain.repository.ProfileDecodeResult
import dev.eno2.openrgbremote.core.domain.repository.ProfileImportError
import dev.eno2.openrgbremote.sdk.model.RgbColor
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
 *       "colors": ["#001A66", "#0066FF"] },
 *     { "name": "Lagerfeuer", "type": "dynamic", "effect": "fire", "speed": 1.2, "reverse": false,
 *       "brightness": 0.8, "colors": ["#600000", "#FF2000", "#FFD24A"] }
 *   ]
 * }
 * ```
 *
 * Unknown fields are ignored so newer minor additions stay readable; a higher `schemaVersion`
 * is rejected instead of being half-understood. Entries of an unknown `type` or `effect` (written
 * by a newer app) and entries without valid colors are skipped. Version 1 of this app knew only
 * `static`; it skips `dynamic` entries, so adding them needed no new schema version.
 * `effect` is the lower-case [EffectId] name; `layout` is kept for dynamic profiles as well, so
 * a profile turned back into a static one keeps it.
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
        val effect: String? = null,
        val speed: Float = 1f,
        val reverse: Boolean = false,
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
                val animation = p.animation
                ProfileDto(
                    name = p.name,
                    type = if (animation == null) TYPE_STATIC else TYPE_DYNAMIC,
                    layout = p.layout.name.lowercase(),
                    brightness = p.brightness,
                    colors = p.colors.map { "#%06X".format(it and 0xFFFFFF) },
                    effect = animation?.effect?.name?.lowercase(),
                    speed = animation?.speed ?: 1f,
                    reverse = animation?.reverse ?: false,
                )
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
            val animation = when (dto.type) {
                TYPE_STATIC -> null
                TYPE_DYNAMIC -> {
                    val effect = EffectId.entries.firstOrNull { it.name.equals(dto.effect, ignoreCase = true) } ?: return@mapNotNull null
                    val speed = if (dto.speed.isNaN()) 1f else dto.speed.coerceIn(EffectParams.MIN_SPEED, EffectParams.MAX_SPEED)
                    ProfileAnimation(effect, speed, dto.reverse)
                }
                else -> return@mapNotNull null
            }
            val parsed = dto.colors.mapNotNull { RgbColor.parseHex(it)?.rgb }.take(Profile.MAX_COLORS)
            // An effect without a palette (the rainbow) needs no colors in the file.
            val colors = if (parsed.isEmpty() && animation != null && !animation.effect.usesColors) listOf(DEFAULT_COLOR) else parsed
            val name = dto.name.trim().take(Profile.MAX_NAME_LENGTH)
            if (colors.isEmpty() || name.isEmpty()) return@mapNotNull null
            Profile(
                name = name,
                colors = colors,
                layout = ColorLayout.entries.firstOrNull { it.name.equals(dto.layout, ignoreCase = true) } ?: ColorLayout.GRADIENT,
                brightness = if (dto.brightness.isNaN()) 1f else dto.brightness.coerceIn(0f, 1f),
                animation = animation,
            )
        }
        return if (profiles.isEmpty()) ProfileDecodeResult.Failure(ProfileImportError.NO_PROFILES) else ProfileDecodeResult.Success(profiles)
    }

    companion object {
        const val FORMAT = "openrgb-remote-profiles"
        const val SCHEMA_VERSION = 1
        private const val TYPE_STATIC = "static"
        private const val TYPE_DYNAMIC = "dynamic"
        private const val DEFAULT_COLOR = 0xFFFFFF
    }
}
