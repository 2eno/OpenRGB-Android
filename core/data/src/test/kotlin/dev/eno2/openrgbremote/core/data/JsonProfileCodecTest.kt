package dev.eno2.openrgbremote.core.data

import dev.eno2.openrgbremote.core.data.db.ProfileEntity
import dev.eno2.openrgbremote.core.data.profile.JsonProfileCodec
import dev.eno2.openrgbremote.core.domain.model.BuiltInProfiles
import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.repository.ProfileDecodeResult
import dev.eno2.openrgbremote.core.domain.repository.ProfileImportError
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
}
