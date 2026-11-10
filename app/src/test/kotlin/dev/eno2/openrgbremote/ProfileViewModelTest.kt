package dev.eno2.openrgbremote

import androidx.lifecycle.SavedStateHandle
import dev.eno2.openrgbremote.core.data.profile.JsonProfileCodec
import dev.eno2.openrgbremote.core.domain.model.BuiltInProfiles
import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.Server
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
import dev.eno2.openrgbremote.core.domain.usecase.ObserveProfiles
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.core.domain.usecase.SaveProfile
import dev.eno2.openrgbremote.feature.profiles.ProfileEditViewModel
import dev.eno2.openrgbremote.feature.profiles.ProfilesMessage
import dev.eno2.openrgbremote.feature.profiles.ProfilesViewModel
import dev.eno2.openrgbremote.feature.profiles.fileNameFor
import dev.eno2.openrgbremote.sdk.testing.RealDevices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val servers = FakeServerRepository(listOf(Server(1, "PC", "h")))
    private val connections = FakeConnectionRepository().apply {
        statuses.value = mapOf(1L to ConnectionStatus.Connected(6, null, null))
        devices.value = mapOf(1L to RealDevices.all().take(2))
    }
    private val lighting = FakeLightingRepository()
    private val repo = FakeProfileRepository(listOf(Profile(1, "Desk", listOf(0xFF0000, 0x0000FF))))
    private val overviews = ObserveServerOverviews(servers, connections)
    private val save = SaveProfile(repo)
    private val codec = JsonProfileCodec()

    private fun TestScope.listViewModel(): ProfilesViewModel {
        val vm = ProfilesViewModel(ObserveProfiles(repo), overviews, repo, ApplyProfile(overviews, lighting, FakeEffectController()), DuplicateProfile(repo, save), ImportProfiles(repo, codec, save), codec)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    private fun editViewModel(id: Long) = ProfileEditViewModel(SavedStateHandle(mapOf("profileId" to id)), GetProfile(repo), save, ApplyProfile(overviews, lighting, FakeEffectController()))

    @Test
    fun `list shows presets and custom profiles and applies globally or per device`() = runTest {
        val vm = listViewModel()
        assertEquals(8, vm.state.value.builtIn.size)
        assertEquals(listOf("Desk"), vm.state.value.custom.map { it.name })
        assertEquals(listOf("Debug Keyboard", "Debug Motherboard"), vm.state.value.devices.map { it.label })

        vm.apply(BuiltInProfiles.all[0])
        assertEquals(ProfilesMessage.Applied(2), vm.state.value.message)
        assertEquals(listOf("frame Debug Keyboard 145", "frame Debug Motherboard 11"), lighting.calls)

        lighting.calls.clear()
        vm.apply(BuiltInProfiles.all[0], setOf(vm.state.value.devices[1].key))
        assertEquals(listOf("frame Debug Motherboard 11"), lighting.calls)

        vm.apply(BuiltInProfiles.all[0], setOf(DeviceKey(9, "gone", "", "")))
        assertEquals(ProfilesMessage.NotReached, vm.state.value.message)
        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `duplicate uses the localized name and delete removes the profile`() = runTest {
        val vm = listViewModel()
        vm.duplicate(BuiltInProfiles.all[0], "Ozean")
        vm.duplicate(vm.state.value.custom.first { it.name == "Desk" }, "Desk")
        assertEquals(listOf("Desk", "Ozean", "Desk 2"), repo.profiles.value.map { it.name })
        vm.delete(1)
        assertFalse(vm.state.value.custom.any { it.id == 1L })
    }

    @Test
    fun `export produces json that imports again`() = runTest {
        val vm = listViewModel()
        val json = assertNotNull(vm.exportJson(null))
        assertTrue("Desk" in json)
        vm.import(json)
        assertEquals(ProfilesMessage.Imported(1), vm.state.value.message)
        assertEquals(listOf("Desk", "Desk 2"), repo.profiles.value.map { it.name })

        val single = assertNotNull(vm.exportJson(BuiltInProfiles.all[1].copy(name = "Sonnenuntergang")))
        assertTrue("Sonnenuntergang" in single && "Desk" !in single)
    }

    @Test
    fun `import and export problems are reported`() = runTest {
        val vm = listViewModel()
        vm.import("no json")
        assertEquals(ProfilesMessage.ImportFailed(ProfileImportError.NOT_JSON), vm.state.value.message)
        vm.import(null)
        assertEquals(ProfilesMessage.ImportUnreadable, vm.state.value.message)
        vm.onExportFinished(false)
        assertEquals(ProfilesMessage.ExportFailed, vm.state.value.message)
        vm.delete(1)
        assertNull(vm.exportJson(null))
        assertEquals(ProfilesMessage.NothingToExport, vm.state.value.message)
    }

    @Test
    fun `file names are safe`() {
        assertEquals("schreibtisch-abend.json", fileNameFor("Schreibtisch / Abend!"))
        assertEquals("profile.json", fileNameFor("???"))
        assertEquals("weiß.json", fileNameFor("Weiß"))
    }

    @Test
    fun `editor creates a new profile`() = runTest {
        val vm = editViewModel(0)
        assertTrue(vm.state.value.isNew)
        vm.save()
        assertTrue(vm.state.value.nameError, "name is required")
        assertFalse(vm.state.value.saved)

        vm.setName("Neon")
        vm.setColor(0, 0x00FF00)
        vm.addColor()
        vm.setColor(2, 0xFF00FF)
        vm.removeColor(1)
        vm.setLayout(ColorLayout.BLOCKS)
        vm.setBrightness(0.5f)
        vm.save()
        assertTrue(vm.state.value.saved)
        val stored = repo.profiles.value.first { it.name == "Neon" }
        assertEquals(listOf(0x00FF00, 0xFF00FF), stored.colors)
        assertEquals(ColorLayout.BLOCKS, stored.layout)
        assertEquals(0.5f, stored.brightness)
    }

    @Test
    fun `editor updates an existing profile and keeps at least one color`() = runTest {
        val vm = editViewModel(1)
        assertEquals("Desk", vm.state.value.profile.name)
        vm.removeColor(0)
        vm.removeColor(0)
        assertEquals(listOf(0x0000FF), vm.state.value.profile.colors)
        assertFalse(vm.state.value.canRemoveColor)
        vm.setName("Desk night")
        vm.save()
        assertEquals(listOf(Profile(1, "Desk night", listOf(0x0000FF))), repo.profiles.value.map { it.copy(createdAtMillis = 0) })
    }

    @Test
    fun `editor limits colors, tries the draft on devices and handles deleted or built-in ids`() = runTest {
        val vm = editViewModel(0)
        repeat(30) { vm.addColor() }
        assertEquals(Profile.MAX_COLORS, vm.state.value.profile.colors.size)
        assertFalse(vm.state.value.canAddColor)

        vm.tryOnDevices()
        assertEquals(2, vm.state.value.tried)
        assertEquals(2, lighting.calls.size)
        assertTrue(repo.profiles.value.none { it.name.isEmpty() }, "trying does not save")

        assertTrue(editViewModel(77).state.value.missing)
        assertTrue(editViewModel(-1).state.value.missing, "presets are not editable")
    }
}
