package dev.eno2.openrgbremote.sdk.protocol

import dev.eno2.openrgbremote.sdk.Fixtures
import dev.eno2.openrgbremote.sdk.Fixtures.toHex
import dev.eno2.openrgbremote.sdk.model.ControllerFlags
import dev.eno2.openrgbremote.sdk.model.DeviceType
import dev.eno2.openrgbremote.sdk.model.ModeFlags
import dev.eno2.openrgbremote.sdk.model.ZoneFlags
import dev.eno2.openrgbremote.sdk.model.ZoneType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes packets captured from a real OpenRGB 1.0 server (`openrgb --server` with debug devices,
 * see `tools/capture_fixtures.py`) for every protocol version 0..6.
 */
class RealServerFixturesTest {

    private fun device(version: Int, index: Int) =
        DeviceCodec.decodeControllerData(Fixtures.bytes("v${version}_device$index.bin"), version, index, index)

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
    fun `re-encoding every captured controller reproduces the server bytes exactly`(version: Int) {
        for (index in 0..5) {
            val original = Fixtures.bytes("v${version}_device$index.bin")
            val decoded = DeviceCodec.decodeControllerData(original, version, index, index)
            val encoded = DeviceCodec.encodeControllerData(decoded, version)
            assertEquals(original.toHex(), encoded.toHex(), "v$version device $index")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
    fun `keyboard structure is identical across protocol versions`(version: Int) {
        val kb = device(version, 0)
        assertEquals(DeviceType.KEYBOARD, kb.type)
        assertEquals("Debug Keyboard", kb.name)
        assertEquals("Debug Keyboard Device", kb.description)
        assertEquals(145, kb.leds.size)
        assertEquals(145, kb.colors.size)
        assertEquals(listOf("Single Zone", "Linear Zone", "Keyboard Zone", "Underglow Zone", "Resizable Zone"), kb.zones.map { it.name })
        assertEquals(listOf(0, 1, 11, 115, 145), kb.zones.map { it.startIndex })
        assertEquals(listOf(1, 10, 104, 30, 0), kb.zones.map { it.ledsCount })

        val keyboardZone = kb.zones[2]
        assertEquals(ZoneType.MATRIX, keyboardZone.type)
        val matrix = assertNotNull(keyboardZone.matrixMap)
        assertEquals(6, matrix.height)
        assertEquals(21, matrix.width)
        // Every LED index of the zone appears exactly once in the matrix.
        val cells = (0 until matrix.height).flatMap { r -> (0 until matrix.width).mapNotNull { c -> matrix.ledAt(r, c) } }
        assertEquals((0 until 104).toList(), cells.sorted())

        val resizable = kb.zones[4]
        assertEquals(0L, resizable.ledsMin)
        assertEquals(100L, resizable.ledsMax)
        assertTrue(resizable.isResizable)

        val direct = kb.modes.single()
        assertEquals("Direct", direct.name)
        assertEquals(ModeFlags.HAS_PER_LED_COLOR, direct.flags)
        assertEquals(0, kb.directModeIndex())
    }

    @Test
    fun `version specific fields appear only where the protocol defines them`() {
        assertEquals("", device(0, 0).vendor)
        assertEquals("Debug Keyboard Vendor String", device(1, 0).vendor)

        // Controller flags, zone flags and LED display names arrived with protocol 5.
        assertEquals(0, device(4, 0).flags)
        assertEquals(0, device(4, 0).zones[4].flags)
        assertTrue(device(4, 0).ledDisplayNames.isEmpty())
        val v5 = device(5, 0)
        assertTrue(v5.flags and ControllerFlags.LOCAL != 0)
        assertTrue(v5.zones[4].flags and ZoneFlags.MANUALLY_CONFIGURABLE_SIZE != 0)
        assertEquals(145, v5.ledDisplayNames.size)

        // Configuration JSON and display names arrived with protocol 6.
        assertEquals("", v5.configuration)
        val v6 = device(6, 0)
        assertTrue(v6.configuration.startsWith("{\"configuration\""), v6.configuration.take(40))
        assertEquals(-1, v6.zones[0].activeMode)
    }

    @Test
    fun `controller count replies use indices before protocol 6 and unique ids afterwards`() {
        for (version in 0..5) {
            val packet = Packet(0, PacketId.REQUEST_CONTROLLER_COUNT, Fixtures.bytes("v${version}_controller_count.bin"))
            assertEquals(4, packet.payload.size, "v$version")
            assertEquals((0..5).toList(), Responses.controllerIds(packet, version))
        }
        val v6 = Packet(0, PacketId.REQUEST_CONTROLLER_COUNT, Fixtures.bytes("v6_controller_count.bin"))
        assertEquals(4 + 6 * 4, v6.payload.size)
        assertEquals(6, Responses.controllerIds(v6, 6).size)
    }

    @Test
    fun `our UpdateLEDs request is accepted and echoed by the server as SIGNALUPDATE`() {
        val request = Fixtures.bytes("v6_updateleds_request_device0.bin")
        val ledCount = LittleEndianReader(request, 4).u16()
        val colors = LittleEndianReader(request, 6).let { r -> IntArray(ledCount) { r.rgbColor() } }

        // The capture script built the request by hand; our builder must produce the same bytes.
        assertEquals(request.toHex(), Requests.updateLeds(0, colors).payload.toHex())

        val echo = Packet(0, PacketId.RGBCONTROLLER_SIGNALUPDATE, Fixtures.bytes("v6_signalupdate_leds_device0.bin"))
        val update = Responses.signalUpdate(echo, 6, 0)
        update as SignalUpdate.Colors
        assertEquals(colors.toList(), update.colors.toIntArray().toList())
        assertEquals(UpdateReason.UPDATE_LEDS, update.reason)
    }

    @Test
    fun `devices without matrix have no matrix map`() {
        assertNull(device(6, 2).zones.first().matrixMap)
        assertEquals(DeviceType.LEDSTRIP, device(6, 2).type)
    }
}
