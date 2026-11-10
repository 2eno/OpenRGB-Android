package dev.eno2.openrgbremote.core.domain

import dev.eno2.openrgbremote.core.domain.model.BuiltInProfiles
import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.ProfileRenderer
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.repository.ProfileCodec
import dev.eno2.openrgbremote.core.domain.repository.ProfileDecodeResult
import dev.eno2.openrgbremote.core.domain.repository.ProfileImportError
import dev.eno2.openrgbremote.core.domain.testing.FakeConnectionRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeEffectController
import dev.eno2.openrgbremote.core.domain.testing.FakeLightingRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeProfileRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeServerRepository
import dev.eno2.openrgbremote.core.domain.usecase.ApplyProfile
import dev.eno2.openrgbremote.core.domain.usecase.DuplicateProfile
import dev.eno2.openrgbremote.core.domain.usecase.GetProfile
import dev.eno2.openrgbremote.core.domain.usecase.ImportProfiles
import dev.eno2.openrgbremote.core.domain.usecase.ImportProfilesResult
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.core.domain.usecase.ProfileError
import dev.eno2.openrgbremote.core.domain.usecase.SaveProfile
import dev.eno2.openrgbremote.core.domain.usecase.SaveProfileResult
import dev.eno2.openrgbremote.sdk.model.ColorArray
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.sdk.model.DeviceType
import dev.eno2.openrgbremote.sdk.model.MatrixMap
import dev.eno2.openrgbremote.sdk.model.ModeFlags
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.model.Zone
import dev.eno2.openrgbremote.sdk.model.ZoneType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileTest {

    private fun profile(layout: ColorLayout, vararg colors: Int, brightness: Float = 1f) = Profile(name = "p", colors = colors.toList(), layout = layout, brightness = brightness)

    @Test
    fun `gradient hits the first and last color exactly and blends in between`() {
        val p = profile(ColorLayout.GRADIENT, 0xFF0000, 0x0000FF)
        assertEquals(listOf(0xFF0000, 0x800080, 0x0000FF), ProfileRenderer.preview(p, 3).toList())
        assertEquals(0xFF0000, ProfileRenderer.colorAt(p, 0, 1), "single LED shows the first color")
        val three = profile(ColorLayout.GRADIENT, 0xFF0000, 0x00FF00, 0x0000FF)
        assertEquals(listOf(0xFF0000, 0x808000, 0x00FF00, 0x008080, 0x0000FF), ProfileRenderer.preview(three, 5).toList())
    }

    @Test
    fun `blocks and alternating layouts`() {
        val blocks = profile(ColorLayout.BLOCKS, 1, 2, 3)
        assertEquals(listOf(1, 1, 2, 2, 3, 3), ProfileRenderer.preview(blocks, 6).toList())
        assertEquals(listOf(1, 1, 2, 3), ProfileRenderer.preview(blocks, 4).toList(), "uneven split still uses every color")
        val alternating = profile(ColorLayout.ALTERNATING, 1, 2, 3)
        assertEquals(listOf(1, 2, 3, 1, 2), ProfileRenderer.preview(alternating, 5).toList())
    }

    @Test
    fun `brightness scales every channel`() {
        assertEquals(0x804000, ProfileRenderer.colorAt(profile(ColorLayout.GRADIENT, 0xFF8000, brightness = 0.5f), 0, 1))
        assertEquals(0x000000, ProfileRenderer.dominantColor(profile(ColorLayout.GRADIENT, 0xFFFFFF, brightness = 0f)))
    }

    private val device = OpenRgbDevice(
        id = 1, index = 0, type = DeviceType.KEYBOARD, rawType = 5, name = "Keyboard", location = "usb", serial = "1",
        modes = listOf(DeviceMode("Direct", flags = ModeFlags.HAS_PER_LED_COLOR)),
        zones = listOf(
            Zone("Logo", ZoneType.SINGLE, 0, 1, 1, 1, startIndex = 0),
            // 2 rows x 3 columns; LED order runs column-wise on purpose: 0 3 / 1 - / 2 4
            Zone("Keys", ZoneType.MATRIX, 2, 5, 5, 5, matrixMap = MatrixMap(2, 3, intArrayOf(0, 1, 2, 3, MatrixMap.NO_LED, 4)), startIndex = 1),
        ),
        colors = ColorArray.of(IntArray(6)),
    )
    private val ramWithoutDirect = OpenRgbDevice(
        id = 2, index = 1, type = DeviceType.DRAM, rawType = 1, name = "RAM", location = "smbus", serial = "2",
        modes = listOf(DeviceMode("Static", flags = ModeFlags.HAS_MODE_SPECIFIC_COLOR)),
        colors = ColorArray.of(IntArray(4)),
    )

    @Test
    fun `matrix zones are colored by column and every zone shows the whole profile`() {
        val p = profile(ColorLayout.BLOCKS, 0xA, 0xB, 0xC)
        val frame = ProfileRenderer.renderDevice(p, device)
        // Logo, then matrix LEDs 0..4: columns 0,1,2 of row 0 and columns 0,2 of row 1.
        assertEquals(listOf(0xA, 0xA, 0xB, 0xC, 0xA, 0xC), frame.toList())
    }

    @Test
    fun `built-in profiles are complete and unique`() {
        assertEquals(listOf("ocean", "sunset", "cyberpunk", "forest", "gaming_red", "warm_white", "night", "off"), BuiltInProfiles.all.map { it.presetKey })
        assertTrue(BuiltInProfiles.all.all { it.id < 0 && it.colors.isNotEmpty() && it.isBuiltIn })
        assertEquals(8, BuiltInProfiles.all.map { it.id }.toSet().size)
        assertEquals(0, ProfileRenderer.dominantColor(BuiltInProfiles.all.last()), "Off is black")
    }

    @Test
    fun `apply reaches per-LED devices with a frame and others with the first color`() = runTest {
        val connections = FakeConnectionRepository()
        connections.statuses.value = mapOf(1L to ConnectionStatus.Connected(6, null, null))
        connections.devices.value = mapOf(1L to listOf(device, ramWithoutDirect))
        val lighting = FakeLightingRepository()
        val effects = FakeEffectController()
        val apply = ApplyProfile(ObserveServerOverviews(FakeServerRepository(listOf(Server(1, "PC", "h"))), connections), lighting, effects)

        val p = profile(ColorLayout.GRADIENT, 0xFF0000, 0x0000FF)
        assertEquals(2, apply(p))
        assertEquals(listOf("frame Keyboard 6", "color FF0000 final RAM"), lighting.calls)
        assertEquals(0xFF0000, lighting.frames.getValue("Keyboard")[1])
        assertEquals(listOf("stop"), effects.calls, "a running effect would overwrite the profile")

        lighting.calls.clear()
        assertEquals(1, apply(p, only = setOf(DeviceKey.of(1, ramWithoutDirect))))
        assertEquals(listOf("color FF0000 final RAM"), lighting.calls)
    }

    @Test
    fun `save validates, normalizes and never stores a preset key`() = runTest {
        val repo = FakeProfileRepository()
        val save = SaveProfile(repo) { 42 }
        assertEquals(SaveProfileResult.Invalid(ProfileError.NAME_EMPTY), save(Profile(name = "  ", colors = listOf(1))))
        assertEquals(SaveProfileResult.Invalid(ProfileError.NO_COLORS), save(Profile(name = "x", colors = emptyList())))

        val saved = assertIs<SaveProfileResult.Saved>(save(BuiltInProfiles.all[0].copy(name = " Mine ", brightness = 3f)))
        val stored = repo.getProfile(saved.id)!!
        assertEquals("Mine", stored.name)
        assertEquals(1f, stored.brightness)
        assertNull(stored.presetKey)
        assertEquals(42, stored.createdAtMillis)

        SaveProfile(repo) { 99 }(stored.copy(name = "Renamed"))
        assertEquals(Profile(saved.id, "Renamed", stored.colors, stored.layout, 1f, null, 42), repo.getProfile(saved.id))
    }

    @Test
    fun `duplicates get unique names`() = runTest {
        assertEquals("Ocean", DuplicateProfile.uniqueName("Ocean", emptySet()))
        assertEquals("Ocean 2", DuplicateProfile.uniqueName("Ocean", setOf("Ocean")))
        assertEquals("Ocean 4", DuplicateProfile.uniqueName("Ocean 2", setOf("Ocean", "Ocean 2", "Ocean 3")))
        assertEquals(Profile.MAX_NAME_LENGTH, DuplicateProfile.uniqueName("x".repeat(60), setOf("x".repeat(60))).length)

        val repo = FakeProfileRepository()
        val save = SaveProfile(repo)
        val duplicate = DuplicateProfile(repo, save)
        val first = duplicate(BuiltInProfiles.all[1], "Sonnenuntergang")!!
        val second = duplicate(repo.getProfile(first)!!)!!
        assertEquals(listOf("Sonnenuntergang", "Sonnenuntergang 2"), repo.profiles.value.map { it.name })
        assertEquals(BuiltInProfiles.all[1].colors, GetProfile(repo)(second)!!.colors)
        assertEquals("Sunset", GetProfile(repo)(-2)!!.name)
    }

    @Test
    fun `import stores decoded profiles with unique names and reports failures`() = runTest {
        val repo = FakeProfileRepository(listOf(Profile(1, "Desk", listOf(1))))
        val codec = object : ProfileCodec {
            var result: ProfileDecodeResult = ProfileDecodeResult.Success(listOf(Profile(name = "Desk", colors = listOf(2)), Profile(name = "Desk", colors = listOf(3)), Profile(name = "", colors = listOf(4))))
            override fun encode(profiles: List<Profile>) = ""
            override fun decode(json: String) = result
        }
        val import = ImportProfiles(repo, codec, SaveProfile(repo))
        assertEquals(ImportProfilesResult.Imported(2), import("{}"))
        assertEquals(listOf("Desk", "Desk 2", "Desk 3"), repo.profiles.value.map { it.name })

        codec.result = ProfileDecodeResult.Failure(ProfileImportError.NEWER_VERSION)
        assertEquals(ImportProfilesResult.Failed(ProfileImportError.NEWER_VERSION), import("{}"))
        codec.result = ProfileDecodeResult.Success(emptyList())
        assertEquals(ImportProfilesResult.Failed(ProfileImportError.NO_PROFILES), import("{}"))
    }
}
