package dev.eno2.openrgbremote.core.data

import dev.eno2.openrgbremote.core.data.effects.AudioLevelSource
import dev.eno2.openrgbremote.core.data.effects.EffectEngine
import dev.eno2.openrgbremote.core.data.effects.EffectParamsStore
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.model.RemoteDevice
import dev.eno2.openrgbremote.core.domain.testing.FakeLightingRepository
import dev.eno2.openrgbremote.core.effects.AudioLevels
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectLoop
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.sdk.model.ColorArray
import dev.eno2.openrgbremote.sdk.testing.RealDevices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.Closeable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EffectEngineTest {

    private val keyboard = RealDevices.all()[0]
    private val strip = RealDevices.all()[2]
    private val devices = MutableStateFlow(listOf(RemoteDevice(1, keyboard), RemoteDevice(1, strip)))
    private val lighting = FakeLightingRepository()
    private val fps = MutableStateFlow(30)
    private var keepAlives = 0

    private class FakeAudio(var available: Boolean) : AudioLevelSource {
        var collecting = 0
        override val isAvailable get() = available
        override fun levels(): Flow<AudioLevels> = flow {
            collecting++
            try {
                while (true) {
                    emit(AudioLevels(FloatArray(AudioLevels.BANDS) { 1f }, 1f))
                    kotlinx.coroutines.delay(10)
                }
            } finally {
                collecting--
            }
        }
    }

    private fun TestScope.engine(audio: AudioLevelSource = FakeAudio(true)) = EffectEngine(
        devices = devices,
        lighting = lighting,
        keepAlive = { keepAlives++; Closeable { keepAlives-- } },
        targetFps = fps,
        audio = audio,
        scope = backgroundScope,
        loop = EffectLoop { testScheduler.currentTime * 1_000_000 },
    )

    private fun frames(name: String) = lighting.calls.count { it.startsWith("frame $name ") }

    @Test
    fun `streams frames to all per-LED devices while running and keeps connections alive`() = runTest {
        val engine = engine()
        engine.start(EffectConfig(EffectId.RAINBOW_WAVE))
        advanceTimeBy(1_000)
        assertTrue(frames("Debug Keyboard") in 29..31, lighting.calls.take(3).toString())
        assertEquals(frames("Debug Keyboard"), frames("Debug ARGB Controller"))
        assertEquals(1, keepAlives)
        val running = assertIs<EffectState.Running>(engine.state.value)
        assertEquals(2, running.deviceCount)
        assertEquals(30, running.stats.targetFps)

        engine.stop()
        val stopped = lighting.calls.size
        advanceTimeBy(1_000)
        assertEquals(stopped, lighting.calls.size)
        assertEquals(0, keepAlives)
        assertEquals(EffectState.Idle, engine.state.value)
    }

    @Test
    fun `targets, parameters and frame rate change without restarting`() = runTest {
        val engine = engine()
        engine.start(EffectConfig(EffectId.GRADIENT_FLOW, targets = setOf(DeviceKey.of(1, strip))))
        advanceTimeBy(500)
        assertEquals(0, frames("Debug Keyboard"))
        assertTrue(frames("Debug ARGB Controller") > 10)

        fps.value = 60
        engine.start(EffectConfig(EffectId.COLOR_CYCLE, EffectParams(brightness = 0f)))
        val before = frames("Debug Keyboard")
        advanceTimeBy(1_000)
        assertTrue(frames("Debug Keyboard") - before in 59..61, "60 fps for the newly selected device")
        assertTrue(lighting.frames.getValue("Debug Keyboard").all { it == 0 }, "new parameters used")
        assertEquals(1, keepAlives, "still one keep-alive: the loop was not restarted")
        assertEquals(EffectId.COLOR_CYCLE, engine.lastConfig(EffectId.COLOR_CYCLE).effect)
        assertEquals(setOf(DeviceKey.of(1, strip)), engine.lastConfig(EffectId.GRADIENT_FLOW).targets)
    }

    @Test
    fun `echoed colors do not rebuild targets but new devices do`() = runTest {
        val engine = engine()
        engine.start(EffectConfig(EffectId.RAINBOW_WAVE))
        advanceTimeBy(200)
        // The server echoes each frame: same structure, new colors.
        devices.value = devices.value.map { it.copy(device = it.device.copy(colors = ColorArray.of(IntArray(it.device.ledCount) { 7 }))) }
        runCurrent()
        assertEquals(2, (engine.state.value as EffectState.Running).deviceCount)

        val mouse = RealDevices.all()[4]
        devices.value = devices.value + RemoteDevice(1, mouse)
        advanceTimeBy(200)
        assertTrue(frames("Debug Mouse") > 0)
        engine.stop()
    }

    @Test
    fun `devices without per-LED mode are skipped`() = runTest {
        val noDirect = strip.copy(name = "Hardware only", modes = strip.modes.map { it.copy(flags = 0) })
        devices.value = listOf(RemoteDevice(1, noDirect))
        val engine = engine()
        engine.start(EffectConfig(EffectId.RAINBOW_WAVE))
        advanceTimeBy(500)
        assertEquals(0, lighting.calls.size)
        assertEquals(0, (engine.state.value as EffectState.Running).deviceCount)
        engine.stop()
    }

    @Test
    fun `microphone is only recorded while the music effect runs`() = runTest {
        val audio = FakeAudio(true)
        val engine = engine(audio)
        engine.start(EffectConfig(EffectId.RAINBOW_WAVE))
        advanceTimeBy(100)
        assertEquals(0, audio.collecting)
        engine.start(EffectConfig(EffectId.MUSIC))
        advanceTimeBy(100)
        assertEquals(1, audio.collecting)
        assertTrue(lighting.frames.getValue("Debug ARGB Controller").any { it != 0 })
        engine.start(EffectConfig(EffectId.AURORA))
        advanceTimeBy(100)
        assertEquals(0, audio.collecting, "switching away releases the microphone")
        engine.start(EffectConfig(EffectId.MUSIC))
        advanceTimeBy(100)
        engine.stop()
        advanceTimeBy(100)
        assertEquals(0, audio.collecting, "stopping releases the microphone")
    }

    @Test
    fun `missing microphone permission is reported`() = runTest {
        val engine = engine(FakeAudio(false))
        engine.start(EffectConfig(EffectId.MUSIC))
        advanceTimeBy(1_100)
        assertTrue((engine.state.value as EffectState.Running).microphoneMissing)
        assertFalse(lighting.calls.isEmpty(), "still renders (dim) so the user sees it is running")
        engine.stop()
    }

    private class MemoryStore(var stored: Map<EffectId, EffectParams> = emptyMap()) : EffectParamsStore {
        var writes = 0
        override suspend fun load() = stored
        override suspend fun save(effect: EffectId, params: EffectParams) {
            writes++
            stored = stored + (effect to params)
        }
    }

    @Test
    fun `effect settings survive a restart and slider drags are written once`() = runTest {
        val store = MemoryStore(mapOf(EffectId.AURORA to EffectParams(speed = 2f, colors = listOf(0x00FF00))))
        val engine = EffectEngine(devices, lighting, { Closeable {} }, fps, FakeAudio(true), backgroundScope, EffectLoop { testScheduler.currentTime * 1_000_000 }, store)
        runCurrent()
        assertEquals(EffectParams(speed = 2f, colors = listOf(0x00FF00)), engine.lastConfig(EffectId.AURORA).params, "restored from the store")
        assertEquals(EffectParams(), engine.lastConfig(EffectId.FIRE).params)

        for (step in 1..20) {
            engine.start(EffectConfig(EffectId.FIRE, EffectParams(speed = 1f + step / 10f)))
            advanceTimeBy(50)
        }
        engine.stop()
        advanceTimeBy(1_500)
        assertEquals(1, store.writes, "debounced")
        assertEquals(3f, store.stored.getValue(EffectId.FIRE).speed)
    }

    @Test
    fun `stored parameters are parsed defensively`() {
        val params = EffectParams(speed = 1.5f, reverse = true, colors = listOf(0xFF0000, 0x00FF00), brightness = 0.4f)
        assertEquals(params, EffectParamsStore.decode(EffectParamsStore.encode(params)))
        assertEquals(EffectParams(), EffectParamsStore.decode(EffectParamsStore.encode(EffectParams())))
        assertEquals(EffectParams.MAX_SPEED, EffectParamsStore.decode("99;false;1.0;")?.speed)
        for (bad in listOf("", "1;2;3", "x;false;1;", "1;maybe;1;", "1;false;NaN;", "1;false;1;ZZ")) {
            assertEquals(null, EffectParamsStore.decode(bad), bad)
        }
    }
}
