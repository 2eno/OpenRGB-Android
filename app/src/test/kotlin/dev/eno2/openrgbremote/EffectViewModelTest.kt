package dev.eno2.openrgbremote

import androidx.lifecycle.SavedStateHandle
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.testing.FakeConnectionRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeEffectController
import dev.eno2.openrgbremote.core.domain.testing.FakeServerRepository
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.feature.effects.EffectSettingsViewModel
import dev.eno2.openrgbremote.feature.effects.EffectsViewModel
import dev.eno2.openrgbremote.service.EffectLauncher
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EffectViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val effects = FakeEffectController()
    private val launcher = object : EffectLauncher {
        override fun start(config: EffectConfig) = effects.start(config)
        override fun stop() = effects.stop()
    }
    private val keyboard = RealDevices.all()[0]
    private val strip = RealDevices.all()[2]
    private val hardwareOnly = strip.copy(name = "RAM", modes = strip.modes.map { it.copy(flags = 0) })
    private val connections = FakeConnectionRepository().apply {
        statuses.value = mapOf(1L to ConnectionStatus.Connected(6, null, null))
        devices.value = mapOf(1L to listOf(keyboard, strip, hardwareOnly))
    }
    private val overviews = ObserveServerOverviews(FakeServerRepository(listOf(Server(1, "PC", "h"))), connections)

    private fun TestScope.settings(effect: EffectId): EffectSettingsViewModel {
        val vm = EffectSettingsViewModel(SavedStateHandle(mapOf("effect" to effect.name)), overviews, effects, launcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun `list marks the running effect and stops it`() = runTest {
        val vm = EffectsViewModel(effects, launcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertNull(vm.state.value.running)
        assertEquals(EffectId.entries.size, vm.state.value.cards.size)

        effects.start(EffectConfig(EffectId.FIRE))
        assertEquals(EffectId.FIRE, vm.state.value.running?.config?.effect)
        assertEquals(listOf(EffectId.FIRE), vm.state.value.cards.filter { it.running }.map { it.effect })
        vm.stop()
        assertNull(vm.state.value.running)
    }

    @Test
    fun `only per-LED capable devices are offered and the draft starts on Start`() = runTest {
        val vm = settings(EffectId.AURORA)
        assertEquals(listOf("Debug Keyboard", "Debug ARGB Controller"), vm.state.value.devices.map { it.label })
        assertEquals(EffectId.AURORA.defaultColors, vm.state.value.palette)

        vm.setSpeed(9f)
        vm.setReverse(true)
        assertTrue(effects.calls.isEmpty(), "nothing is sent before Start")
        vm.start()
        assertEquals(listOf("start AURORA speed=3.0 targets=null"), effects.calls)
        assertTrue(vm.state.value.running)
    }

    @Test
    fun `changes apply live while the effect runs`() = runTest {
        val vm = settings(EffectId.PULSE)
        vm.start()
        vm.setSpeed(0.5f)
        vm.toggleDevice(DeviceKey.of(1, strip))
        assertEquals("start PULSE speed=0.5 targets=[Debug Keyboard]", effects.calls.last())
        vm.stop()
        assertFalse(vm.state.value.running)
        vm.setSpeed(2f)
        assertEquals("stop", effects.calls.last(), "a stopped effect is not restarted by edits")
    }

    @Test
    fun `palette edits start from the defaults and can be reset`() = runTest {
        val vm = settings(EffectId.BREATHING)
        vm.setColor(0, 0x123456)
        assertEquals(listOf(0x123456, EffectId.BREATHING.defaultColors[1]), vm.state.value.palette)
        vm.addColor()
        assertEquals(3, vm.state.value.palette.size)
        vm.removeColor(2)
        vm.removeColor(1)
        vm.removeColor(0)
        assertEquals(listOf(0x123456), vm.state.value.palette, "at least one color stays")
        repeat(20) { vm.addColor() }
        assertEquals(EffectParams.MAX_COLORS, vm.state.value.palette.size)
        vm.resetColors()
        assertEquals(EffectId.BREATHING.defaultColors, vm.state.value.palette)
    }

    @Test
    fun `settings remember the running configuration`() = runTest {
        effects.start(EffectConfig(EffectId.GRADIENT_FLOW, EffectParams(speed = 1.5f)))
        val vm = settings(EffectId.GRADIENT_FLOW)
        assertEquals(1.5f, vm.state.value.config.params.speed)
        assertTrue(vm.state.value.running)
        assertTrue(effects.state.value is EffectState.Running)
    }
}
