package dev.eno2.openrgbremote.core.data

import dev.eno2.openrgbremote.core.data.connection.ConnectionManager
import dev.eno2.openrgbremote.core.data.connection.NetworkMonitor
import dev.eno2.openrgbremote.core.data.effects.AudioLevelSource
import dev.eno2.openrgbremote.core.data.effects.EffectEngine
import dev.eno2.openrgbremote.core.data.lighting.LightingController
import dev.eno2.openrgbremote.core.domain.model.BuiltInProfiles
import dev.eno2.openrgbremote.core.domain.model.ColorTarget
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.model.ProfileAnimation
import dev.eno2.openrgbremote.core.domain.model.ProfileRenderer
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.repository.EffectLauncher
import dev.eno2.openrgbremote.core.domain.testing.FakeServerRepository
import dev.eno2.openrgbremote.core.domain.usecase.ApplyProfile
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.core.effects.AudioLevels
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.sdk.client.OpenRgbClient
import dev.eno2.openrgbremote.sdk.client.OpenRgbClientConfig
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The whole data layer against a real OpenRGB server: the app's ConnectionManager, the conflating
 * LED streamers, profiles (static and dynamic) and the effect engine. A second, independent SDK
 * client reads the colors back from the server, so the test sees what the hardware would get.
 *
 * Runs when `OPENRGB_TEST_SERVER=host:port` is set (debug devices, see tools/capture_fixtures.py).
 * It changes the colors of all devices on that server. Do not run it in the same Gradle call as
 * the SDK's RealServerIntegrationTest: that one ends with a rescan, which resets all LEDs.
 */
class RealServerEndToEndTest {

    private val target: Pair<String, Int>? = System.getenv("OPENRGB_TEST_SERVER")?.let {
        val (host, port) = it.split(':')
        host to port.toInt()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private class NoMicrophone : AudioLevelSource {
        override val isAvailable = false
        override fun levels(): Flow<AudioLevels> = emptyFlow()
    }

    @Test
    fun `profiles and effects reach a real server`() = runBlocking {
        assumeTrue("OPENRGB_TEST_SERVER not set", target != null)
        val (host, port) = target!!
        withTimeout(60_000) {
            val servers = FakeServerRepository(listOf(Server(1, "Real", host, port)))
            val network = object : NetworkMonitor { override val isOnline = MutableStateFlow(true) }
            val manager = ConnectionManager(servers, network, scope, OpenRgbClientConfig(clientName = "OpenRGB Remote end-to-end test"))
            val lighting = LightingController(manager, scope)
            val overviews = ObserveServerOverviews(servers, manager)
            val engine = EffectEngine(
                devices = overviews().map { list -> list.flatMap { it.devices } },
                lighting = lighting,
                keepAlive = manager::acquireKeepAlive,
                targetFps = MutableStateFlow(30),
                audio = NoMicrophone(),
                scope = scope,
            )
            val launcher = object : EffectLauncher {
                override fun start(config: EffectConfig) = engine.start(config)
                override fun stop() = engine.stop()
            }
            val apply = ApplyProfile(overviews, lighting, launcher)

            val connected = assertIs<ConnectionStatus.Connected>(manager.statuses.first { it[1L] is ConnectionStatus.Connected }[1L])
            assertEquals(6, connected.protocolVersion, "OpenRGB 1.0 speaks protocol 6")
            val devices = manager.devices.first { !it[1L].isNullOrEmpty() }.getValue(1L)
            val perLed = devices.filter { it.ledCount > 0 && it.directModeIndex() != null }
            assertTrue(perLed.isNotEmpty(), "no per-LED device on the server")

            OpenRgbClient(host, port, OpenRgbClientConfig(clientName = "OpenRGB Remote verifier")).use { verifier ->
                verifier.connect()
                suspend fun colorsOf(device: OpenRgbDevice): List<Int> {
                    val id = verifier.refreshDevices().first { it.name == device.name && it.location == device.location }.id
                    return verifier.refreshDevice(id)!!.colors.toIntArray().toList()
                }
                suspend fun awaitColors(device: OpenRgbDevice, expected: List<Int>) {
                    var last = emptyList<Int>()
                    repeat(50) {
                        last = colorsOf(device)
                        if (last == expected) return
                        delay(100)
                    }
                    assertEquals(expected, last, "colors of ${device.name}")
                }

                // 1. A static preset reaches every device, rendered per LED where possible.
                val ocean = BuiltInProfiles.all.first { it.presetKey == "ocean" }
                assertEquals(devices.size, apply(ocean))
                for (device in perLed) awaitColors(device, ProfileRenderer.renderDevice(ocean, device).toList())

                // 2. A single color from the color picker.
                val keyboard = perLed.first()
                val key = overviews().first().single().devices.first { it.device.name == keyboard.name }.key
                assertEquals(1, lighting.setColor(listOf(ColorTarget.Device(key)), 0x00FF40, preview = false))
                awaitColors(keyboard, List(keyboard.ledCount) { 0x00FF40 })

                // 3. An effect streams frames: the colors keep changing at about the target rate.
                engine.start(EffectConfig(EffectId.GRADIENT_FLOW))
                delay(1_500)
                val first = colorsOf(keyboard)
                delay(400)
                assertNotEquals(first, colorsOf(keyboard), "the effect animates")
                val running = assertIs<EffectState.Running>(engine.state.value)
                assertEquals(perLed.size, running.deviceCount)
                assertTrue(running.stats.fps >= 20, "fps ${running.stats.fps}")

                // 4. A static profile stops the effect, and its frame is not painted over afterwards.
                val off = BuiltInProfiles.all.first { it.presetKey == "off" }
                apply(off)
                assertEquals(EffectState.Idle, engine.state.value)
                for (device in perLed) awaitColors(device, List(device.ledCount) { 0 })
                delay(500)
                assertEquals(List(keyboard.ledCount) { 0 }, colorsOf(keyboard), "no late effect frame")

                // 5. A dynamic profile starts its effect with the profile's palette.
                val fire = Profile(name = "Fire", colors = listOf(0x400000, 0xFF8000), animation = ProfileAnimation(EffectId.FIRE))
                assertEquals(devices.size, apply(fire))
                assertEquals(EffectId.FIRE, assertIs<EffectState.Running>(engine.state.value).config.effect)
                delay(1_000)
                assertTrue(colorsOf(keyboard).any { it != 0 }, "fire is burning")
                engine.stop()
            }
        }
    }
}
