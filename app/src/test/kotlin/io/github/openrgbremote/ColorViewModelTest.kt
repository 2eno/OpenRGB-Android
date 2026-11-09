package io.github.openrgbremote

import androidx.lifecycle.SavedStateHandle
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.DeviceKey
import io.github.openrgbremote.core.domain.model.HsvColor
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.core.domain.testing.FakeConnectionRepository
import io.github.openrgbremote.core.domain.testing.FakeFavoriteColorRepository
import io.github.openrgbremote.core.domain.testing.FakeLightingRepository
import io.github.openrgbremote.core.domain.testing.FakeServerRepository
import io.github.openrgbremote.core.domain.usecase.ObserveDevice
import io.github.openrgbremote.core.domain.usecase.ObserveServerOverviews
import io.github.openrgbremote.feature.color.ColorMessage
import io.github.openrgbremote.feature.color.ColorViewModel
import io.github.openrgbremote.feature.devicedetail.DetailMessage
import io.github.openrgbremote.feature.devicedetail.DeviceDetailViewModel
import io.github.openrgbremote.feature.devicedetail.colorSourceOptions
import io.github.openrgbremote.feature.devicedetail.directionOptions
import io.github.openrgbremote.feature.devicedetail.fractionOf
import io.github.openrgbremote.feature.devicedetail.valueAt
import io.github.openrgbremote.sdk.model.ColorArray
import io.github.openrgbremote.sdk.model.ColorMode
import io.github.openrgbremote.sdk.model.DeviceMode
import io.github.openrgbremote.sdk.model.ModeDirection
import io.github.openrgbremote.sdk.model.ModeFlags
import io.github.openrgbremote.sdk.model.OpenRgbDevice
import io.github.openrgbremote.sdk.testing.RealDevices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ColorViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val servers = FakeServerRepository(listOf(Server(1, "PC", "192.168.1.30")))
    private val connections = FakeConnectionRepository()
    private val lighting = FakeLightingRepository()
    private val favorites = FakeFavoriteColorRepository(listOf(0x112233))

    /** Keyboard (5 zones, 145 LEDs) and ARGB controller decoded from real OpenRGB 1.0 packets. */
    private val keyboard: OpenRgbDevice = RealDevices.all()[0].let { it.copy(colors = ColorArray.of(IntArray(it.ledCount) { 0x00FF00 })) }
    private val strip: OpenRgbDevice = RealDevices.all()[2]
    private val keyboardKey = DeviceKey.of(1, keyboard)

    init {
        connections.statuses.value = mapOf(1L to ConnectionStatus.Connected(6, null, null))
        connections.devices.value = mapOf(1L to listOf(keyboard, strip))
    }

    private fun TestScope.colorViewModel(vararg args: Pair<String, Any?>): ColorViewModel {
        val vm = ColorViewModel(SavedStateHandle(mapOf(*args)), ObserveServerOverviews(servers, connections), lighting, favorites)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun `all devices are targeted by default and dragging sends previews then a final frame`() = runTest {
        val vm = colorViewModel()
        assertEquals(listOf(true, true), vm.state.value.devices.map { it.selected })
        assertEquals(0x00FF00, vm.state.value.rgb, "starts with the current color of the first device")

        vm.onColorChange(HsvColor.fromRgb(0xFF0000))
        vm.onColorChange(HsvColor.fromRgb(0x0000FF))
        vm.onChangeFinished()
        assertEquals(
            listOf(
                "color FF0000 preview Debug Keyboard,Debug ARGB Controller",
                "color 0000FF preview Debug Keyboard,Debug ARGB Controller",
                "color 0000FF final Debug Keyboard,Debug ARGB Controller",
            ),
            lighting.calls,
        )
        assertTrue(vm.state.value.canUndo)
    }

    @Test
    fun `without live preview nothing is sent until apply`() = runTest {
        val vm = colorViewModel()
        vm.setLivePreview(false)
        vm.onColorChange(HsvColor.fromRgb(0xFF0000))
        vm.onChangeFinished()
        vm.onPickColor(0x123456)
        assertTrue(lighting.calls.isEmpty())
        vm.apply()
        assertEquals(listOf("color 123456 final Debug Keyboard,Debug ARGB Controller"), lighting.calls)
    }

    @Test
    fun `device and zone selection narrows the targets`() = runTest {
        val vm = colorViewModel("device" to keyboardKey.encode())
        assertEquals(listOf(true, false), vm.state.value.devices.map { it.selected })
        assertEquals(5, vm.state.value.zones.size)
        assertEquals("Debug Keyboard", vm.state.value.singleDeviceLabel)

        vm.toggleZone(0)
        vm.toggleZone(4)
        vm.onPickColor(0xABCDEF)
        assertEquals("color ABCDEF final Debug Keyboard[1, 2, 3]", lighting.calls.last())

        // Selecting a second device drops the zone filter: zones belong to one device.
        vm.toggleDevice(DeviceKey.of(1, strip))
        assertTrue(vm.state.value.zones.isEmpty())
        vm.onPickColor(0x000001)
        assertEquals("color 000001 final Debug Keyboard,Debug ARGB Controller", lighting.calls.last())
    }

    @Test
    fun `opened for a zone or a single LED`() = runTest {
        val zoneVm = colorViewModel("device" to keyboardKey.encode(), "zone" to 2)
        assertEquals(listOf(false, false, true, false, false), zoneVm.state.value.zones.map { it.selected })
        zoneVm.onPickColor(0x010203)
        assertEquals("color 010203 final Debug Keyboard[2]", lighting.calls.last())

        val ledVm = colorViewModel("device" to keyboardKey.encode(), "led" to 12)
        assertEquals(keyboard.ledLabel(12), ledVm.state.value.ledLabel)
        assertTrue(ledVm.state.value.devices.isEmpty(), "target is fixed")
        ledVm.onPickColor(0x040506)
        assertEquals("color 040506 final Debug Keyboard#12", lighting.calls.last())
    }

    @Test
    fun `deselecting everything asks for a selection instead of sending`() = runTest {
        val vm = colorViewModel("device" to keyboardKey.encode())
        vm.toggleDevice(keyboardKey)
        vm.onPickColor(0xFFFFFF)
        assertTrue(lighting.calls.isEmpty())
        assertEquals(ColorMessage.NONE_SELECTED, vm.state.value.message)
        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `unreachable devices are reported`() = runTest {
        lighting.reachable = 0
        val vm = colorViewModel()
        vm.onPickColor(0xFFFFFF)
        assertEquals(ColorMessage.NOT_REACHED, vm.state.value.message)
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun `undo restores the colors from before the first change`() = runTest {
        val vm = colorViewModel("device" to keyboardKey.encode())
        vm.onPickColor(0xFF0000)
        vm.onPickColor(0x0000FF)
        vm.undo()
        assertEquals("frame Debug Keyboard 145", lighting.calls.last())
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun `favorites can be toggled for the current color`() = runTest {
        val vm = colorViewModel()
        vm.setLivePreview(false)
        vm.onPickColor(0x112233)
        assertTrue(vm.state.value.isFavorite)
        vm.toggleFavorite()
        assertEquals(emptyList(), favorites.colors.value)
        vm.toggleFavorite()
        assertEquals(listOf(0x112233), vm.state.value.favorites)
    }

    @Test
    fun `detail screen switches and saves modes`() = runTest {
        val rainbow = DeviceMode("Rainbow", flags = ModeFlags.HAS_SPEED or ModeFlags.MANUAL_SAVE, speedMin = 1, speedMax = 9, speed = 3)
        connections.devices.value = mapOf(1L to listOf(keyboard.copy(modes = keyboard.modes + rainbow)))
        val vm = DeviceDetailViewModel(
            SavedStateHandle(mapOf("key" to keyboardKey.encode())),
            ObserveDevice(ObserveServerOverviews(servers, connections)),
            lighting,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(keyboardKey.encode(), vm.state.value.encodedKey)

        vm.selectMode(1)
        assertEquals("mode Debug Keyboard 1 Rainbow speed=3 brightness=0 dir=0 colors=0", lighting.calls.last())
        vm.updateActiveMode(vm.state.value.device!!.modes[0].copy(brightness = 7))
        assertEquals("mode Debug Keyboard 0 Direct speed=0 brightness=7 dir=0 colors=0", lighting.calls.last())
        vm.saveActiveMode()
        assertEquals("save Debug Keyboard 0", lighting.calls.last())
        assertEquals(DetailMessage.MODE_SAVED, vm.state.value.message)
    }

    @Test
    fun `mode parameter helpers handle inverted ranges and flags`() {
        // OpenRGB devices often use "smaller value = faster": min 10, max 1.
        assertEquals(0f, fractionOf(10, 10, 1))
        assertEquals(1f, fractionOf(1, 10, 1))
        assertEquals(1L, valueAt(1f, 10, 1))
        assertEquals(4L, valueAt(2f / 3f, 10, 1))
        assertEquals(0f, fractionOf(5, 5, 5), "degenerate range")
        assertEquals(50L, valueAt(0.5f, 0, 100))

        val mode = DeviceMode("x", flags = ModeFlags.HAS_DIRECTION_LR or ModeFlags.HAS_DIRECTION_HV or ModeFlags.HAS_PER_LED_COLOR or ModeFlags.HAS_RANDOM_COLOR)
        assertEquals(listOf(ModeDirection.LEFT, ModeDirection.RIGHT, ModeDirection.HORIZONTAL, ModeDirection.VERTICAL), directionOptions(mode))
        assertEquals(listOf(ColorMode.PER_LED, ColorMode.RANDOM), colorSourceOptions(mode))
    }
}
