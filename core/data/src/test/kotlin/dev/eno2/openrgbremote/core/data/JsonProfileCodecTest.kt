package dev.eno2.openrgbremote.core.data

import dev.eno2.openrgbremote.core.data.db.ProfileEntity
import dev.eno2.openrgbremote.core.data.profile.JsonProfileCodec
import dev.eno2.openrgbremote.core.domain.model.BuiltInProfiles
import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.ProfileAnimation
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.core.domain.repository.ProfileDecodeResult
import dev.eno2.openrgbremote.core.domain.repository.ProfileImportError
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonProfileCodecTest {
    private val codec = JsonProfileCodec()

    private fun error(json: String) = assertIs<ProfileDecodeResult.Failure>(codec.decode(json)).error

    @Test
    fun `export and import round trip`() {
        val profiles = listOf(
            Profile(name = "Schreibtisch \"Abend\"", colors = listOf(0xFF8000, 0x000000, 0x00FFAA), layout = ColorLayout.BLOCKS, brightness = 0.25f),
            Profile(name = "Weiß", colors = listOf(0xFFFFFF), layout = ColorLayout.ALTERNATING),
        )
        val json = codec.encode(profiles)
        assertTrue("\"format\": \"openrgb-remote-profiles\"" in json, json)
        assertTrue("\"#FF8000\"" in json)
        assertEquals(profiles, assertIs<ProfileDecodeResult.Success>(codec.decode(json)).profiles)
    }

    @Test
    fun `built-in profiles are exported without their preset identity`() {
        val decoded = assertIs<ProfileDecodeResult.Success>(codec.decode(codec.encode(BuiltInProfiles.all))).profiles
        assertEquals(BuiltInProfiles.all.map { it.name to it.colors }, decoded.map { it.name to it.colors })
        assertTrue(decoded.none { it.isBuiltIn })
    }

    @Test
    fun `hand-written files are read leniently`() {
        val json = """
            { "format": "openrgb-remote-profiles", "futureField": true,
              "profiles": [
                { "name": "  Minimal  ", "colors": ["f00", "#00ff00", "nonsense"] },
                { "name": "Too bright", "brightness": 7, "layout": "BLOCKS", "colors": ["#123456"] },
                { "name": "No colors", "colors": [] },
                { "name": "", "colors": ["#FFFFFF"] },
                { "name": "From the future", "type": "dynamic", "colors": ["#FFFFFF"] }
              ] }
        """.trimIndent()
        val profiles = assertIs<ProfileDecodeResult.Success>(codec.decode(json)).profiles
        assertEquals(
            listOf(
                Profile(name = "Minimal", colors = listOf(0xFF0000, 0x00FF00)),
                Profile(name = "Too bright", colors = listOf(0x123456), layout = ColorLayout.BLOCKS, brightness = 1f),
            ),
            profiles,
        )
    }

    @Test
    fun `unusable files are rejected with a reason`() {
        assertEquals(ProfileImportError.NOT_JSON, error("this is not json"))
        assertEquals(ProfileImportError.NOT_JSON, error("[1, 2, 3]"))
        assertEquals(ProfileImportError.WRONG_FORMAT, error("""{"name": "some other json"}"""))
        assertEquals(ProfileImportError.NEWER_VERSION, error("""{"format": "openrgb-remote-profiles", "schemaVersion": 2, "profiles": []}"""))
        assertEquals(ProfileImportError.NO_PROFILES, error("""{"format": "openrgb-remote-profiles", "profiles": []}"""))
    }

    @Test
    fun `database entity keeps colors and layout`() {
        val profile = Profile(7, "Desk", listOf(0x0000FF, 0xFF00FF), ColorLayout.ALTERNATING, 0.5f, null, 123)
        val entity = ProfileEntity.from(profile)
        assertEquals("0000FF,FF00FF", entity.colors)
        assertEquals(profile, entity.toDomain())
        assertEquals(ColorLayout.GRADIENT, entity.copy(layout = "UNKNOWN").toDomain().layout)
    }

    @Test
    fun `dynamic profiles round trip and are readable by hand`() {
        val fire = Profile(name = "Lagerfeuer", colors = listOf(0x600000, 0xFFD24A), brightness = 0.8f, animation = ProfileAnimation(EffectId.FIRE, 1.25f))
        val rainbow = Profile(name = "Regenbogen", colors = listOf(0xFFFFFF), animation = ProfileAnimation(EffectId.RAINBOW_WAVE, 0.5f, reverse = true))
        val json = codec.encode(listOf(fire, rainbow))
        assertTrue("\"type\": \"dynamic\"" in json && "\"effect\": \"fire\"" in json, json)
        assertEquals(listOf(fire, rainbow), assertIs<ProfileDecodeResult.Success>(codec.decode(json)).profiles)

        val handWritten = """{"format": "openrgb-remote-profiles", "profiles": [
            {"name": "Rainbow", "type": "dynamic", "effect": "RAINBOW_WAVE", "speed": 99},
            {"name": "Future", "type": "dynamic", "effect": "laser_show", "colors": ["#FF0000"]},
            {"name": "No effect", "type": "dynamic", "colors": ["#FF0000"]},
            {"name": "Mode", "type": "hardware", "colors": ["#FF0000"]}
        ]}"""
        val read = assertIs<ProfileDecodeResult.Success>(codec.decode(handWritten)).profiles.single()
        assertEquals("Rainbow", read.name)
        assertEquals(ProfileAnimation(EffectId.RAINBOW_WAVE, EffectParams.MAX_SPEED), read.animation, "speed is clamped")
        assertEquals(1, read.colors.size, "an effect without palette needs no colors")
    }

    @Test
    fun `database entity keeps the animation and drops unknown effects`() {
        val profile = Profile(3, "Aurora", listOf(0x00FF7F), animation = ProfileAnimation(EffectId.AURORA, 2f, reverse = true))
        val entity = ProfileEntity.from(profile)
        assertEquals("AURORA", entity.effect)
        assertEquals(profile, entity.toDomain())
        assertNull(ProfileEntity.from(profile.copy(animation = null)).effect)
        assertNull(entity.copy(effect = "LASER_SHOW").toDomain().animation, "a newer app's effect falls back to static")
    }
}
